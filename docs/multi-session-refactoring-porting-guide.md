# 多会话身份重构 —— VS Code 插件移植指南

> 本文件是 `opencode-idea-gui` 侧重构的**实施痕迹**，用于把同一套改造搬到
> `opencode-vscode-plugin`。
>
> 配套文档：
> - 诊断（为什么）：`docs/multi-session-isolation-architecture.md`
> - 目标架构（改成什么样）：`docs/multi-session-identity-refactoring.md`
>
> 一句话原则：**会话身份是一等字段，不是推断产物。** 空 id 是"不可归属"，
> 绝不等于"当前激活的会话"。任何一层拿不到身份，就**丢弃并计数**，不许猜。

---

## 0. 三层的现状对照（移植前必读）

| 层 | idea-gui（已改造） | vscode-plugin（待移植） |
|---|---|---|
| core（daemon / marker） | **共享，已改完** | 同一份（`node_modules/opencode-buddy-core` 是 symlink）→ **自动生效** |
| 宿主 | Java：`DaemonBridge` / `SessionCallbackAdapter` / `MarkerParser` | TS：`src/host/provider/OpenCodeDaemonBridge.ts` / `src/host/session/SessionCallbackAdapter.ts` / `src/host/session/MarkerParser.ts` |
| webview | 有多会话 tab（`__sessionTabsStore`），回调已是 `(sessionId, payload)` 约定 | **还没有多会话 tab**；`onContentDelta(delta)` 只收一个参数 |

**关键结论**：core 已经把身份送到门口了。vscode 侧要做的第一件事是**别把身份丢掉**，
第二件事才是加多会话 UI。顺序不能反。

---

## 1. core 层已改了什么（vscode 无需再改，但必须知道）

位置：`D:/source/repos/opencode-buddy-core`

| 文件 | 改动 | 对 vscode 的影响 |
|---|---|---|
| `request-context.js` | 上下文从 `{id}` → `{id, sessionId, turnId, seq}`；导出 `currentContext()` | 无感 |
| `daemon.js` | NDJSON 信封 `{id, line}` → `{id, session, turn, seq, line}`；**删除**全局单槽 `backgroundStreamRequestId`；stdout 劫持加单行快速路径 | **有感知**：宿主必须读 `session` 字段，见 §2.1 |
| `utils/ndjson-envelope.js`（新） | 信封构造/校验的单一入口 | 可选使用 |
| `utils/marker-protocol.js` | 新增 `emitTurnEnd`；`endStream({sessionId})` | **有感知**：宿主 parser 需新增 `[TURN_END]` |
| `services/opencode/opencode-daemon-service.js` | `runWithContext` 无条件带身份；`_createTurn(requestId, sessionId)`；`turnId` 取 SSE 的 `durable.seq`；`_settleTurn` 发 `[TURN_END]`；`abortCurrentTurn` 本地立即结算 | 无感（行为更可靠） |

> **协议要点**：`id` 回答"这条是哪个请求的回执"（HTTP 配对），`session` 回答
> "这条内容属于哪个会话"（数据归属）。二者必须分开；旧实现把两件事压进同一个字符串。

---

## 2. 宿主侧移植清单（vscode：TS）

### 2.1 信封解析与按 session 路由

**idea 对应实现**：`src/main/java/com/opencodebuddy/provider/common/DaemonBridge.java`
（搜索锚点：`handleDaemonOutput`、`bufferOrphanLine`、`unregisterSessionSink`）
与新增的 `src/main/java/com/opencodebuddy/session/SessionStreamRouter.java`。

**vscode 落点**：`src/host/provider/OpenCodeDaemonBridge.ts:759 handleDaemonOutput`
（`:834` 处 `if (typeof obj.line === 'string') pending.callback.onLine(obj.line);`）。

要做的三件事：

1. 解析 `obj.session`（以及 `obj.turn` / `obj.seq`），和 `obj.id` 分开保存；
2. 数据面板按 `session` 路由到该会话的 parser；只有当 `session` 缺失时才退回按 `id`
   找 pending handler（并把缺失**计入** `unattributed` 计数，打一条 warn）；
3. 提供 `registerSessionSink(sessionId, sink)` / `unregisterSessionSink(sessionId)`，
   并在会话关闭时**对称注销**（否则泄漏）。

> idea 侧的 `SessionStreamRouter` 就是"按 session 找 parser"的那层，可以直接照抄语义。

### 2.2 `[TURN_END]` —— 权威、幂等的回合终态

**idea**：`OpenCodeMarkerParser.java:136`（`[TURN_END]` → `onMessage("turn_end", …)`）
→ `OpenCodeMessageHandler.handleTurnEnd` → `SessionCallbackAdapter.onTurnEnd` → JS `onTurnEnd`。

**vscode 落点**：`src/host/session/MarkerParser.ts`（参考 `:107` 的 `[STREAM_END]` 分支）。

要点：
- 终态携带 `sessionId` + `turnId`；`turnId` 由 core 从服务端 `durable.seq` 取，**宿主不要自造计数器**；
- 终态必须发到 payload 指定的会话，不是"当前 tab"；
- 收到后要清 loading / streaming / thinking，且**按 `(sessionId, turnId)` 幂等**。

### 2.3 权限/提问带上 sessionId

**idea**：`permission/PermissionRequest.java` 增加 `sessionId` 字段；
`OpenCodeMessageHandler` 用 marker payload 的 `sessionId` 填实（不再从可空字段借）；
`handler/PermissionHandler.java` 删除"`targetSessionId == null` → 借当前会话"的回落。

**vscode 落点**：`src/host/session/MessageHandler.ts`（权限分支）+ 对应的权限宿主模块。

> **必删清单**：`sessionId == null → context.getSession().getSessionId()` 这类兜底。
> 它正是"会话 B 的权限弹窗显示在会话 A"和"弹窗压根不出现"的共同来源。

### 2.4 中断不能静默

**idea**：`handler/SessionHandler.java handleInterruptSession` —— 查不到会话时**先释放前端**
（`onStreamEnd` + `showLoading false`）再返回，而不是什么都不做。

**vscode 落点**：vscode 的 interrupt/sendAbort（参考 `OpenCodeDaemonBridge.sendAbort`
已有 `sessionId` 参数，:535）。

### 2.5 待决权限表溢出策略

**idea**：`session/OpencodePermissionRegistry.java` —— 溢出时**逐条淘汰最旧**（原来是整表清空，
会连带丢掉别的会话的待决请求）；新增 `clearSession(sessionId)`，在 `closeSession` 里调用。

---

## 3. webview 侧移植清单

### 3.1 先照搬：身份解析中枢

**新文件**（idea 侧）：`webview/src/utils/sessionIdentity.ts`

```ts
resolveSessionId(targetSessionId, kind): string | null   // 空 → null + 计数 + 告警
droppedCount(kind) / resetDroppedCounts()                 // 测试与遥测 seam
```

**vscode 落点**：新建同名文件；把 idea 侧的
`webview/src/utils/sessionIdentity.test.ts` 一起搬过去（6 个用例，纯函数，零依赖）。

### 3.2 回调改成 `(sessionId, payload)` 约定

vscode 目前是单参数（`onContentDelta(delta)`）。移植步骤：

1. `window.onContentDelta = (arg0, arg1?) => { const delta = arg1 !== undefined ? arg1 : arg0; ... }`
   —— 先**兼容两种形态**（宿主可能还没改完），但**只在有身份时才写入**；
2. 宿主改完后删掉兼容分支。
3. `global.d.ts` 里把 `onContentDelta?: (delta: string) => void` 改成
   `(sessionId: string, delta: string) => void`。

同改：`onThinkingDelta`、`onStreamEnd`、`showLoading`、`updateMessages`、`updateMessageTail`、
`onBlockReset`、`onStreamingHeartbeat`。

### 3.3 `onTurnEnd`（新回调）

**idea 实现**：`webview/src/hooks/windowCallbacks/registerCallbacks/streamingCallbacks.ts`
（文件末尾 `window.onTurnEnd`）。语义：
- 无身份 → 丢；
- 后台会话 → 写 tab 快照的 `loading/streamingActive/isThinking = false`；
- 前台 → 清全局 loading/streaming/thinking + `finalizeUnresolvedToolUses()`；
- 按 `${sessionId}#${turnId}` 幂等，集合上限 256 后清空。

**vscode 落点**：同一个 `streamingCallbacks.ts`。测试照搬
`webview/src/hooks/windowCallbacks/registerCallbacks/turnEnd.test.ts`（7 个用例）。

### 3.4 禁止"空 id → 当前会话"

**idea 已删**：`const effectiveSessionId = targetSessionId || options.currentSessionIdRef?.current`
（3 处，全在 `messageCallbacks.ts`）→ 改为 `targetSessionId ?? null`。

vscode 侧现在还没有这个模式，但**加多会话 UI 时最容易重新引入**，请直接写成
`resolveSessionId(...)` 并在 `null` 时 `return`。

### 3.5 序号屏障 per-session 化

**idea**：`webview/src/hooks/windowCallbacks/sequenceBarrier.ts`
- `getMinAcceptedSequence` **删除全局基线兜底**（未知会话返回 0，取"放行"方向）；
- `isForegroundSession(null)` 从 `true` 改成 `false`。

理由：单一全局基线会让"会话 A 的高序号"永久卡死"刚加载的会话 B 的快照"。
vscode 目前只有 `parseSequence.ts`，加屏障时**直接按 per-session 写**，别先写全局再改。

### 3.6 `updateBackgroundTab` 必须返回 boolean

若 vscode 也引入后台 tab 快照：

```ts
updateBackgroundTab(predicate, updater): boolean   // 是否有 tab 命中
```

调用方**必须检查返回值**：`false` 表示这条写无处可去。
- 权限/提问弹窗：命中不到 tab 时**照常弹出**（丢失提示比弹错位置更糟，且回复是
  按 permissionId 路由的，不受显示位置影响）；
- 消息快照：命中不到 tab 时**回落到前台路径**，不要丢。

### 3.7 `updateBackgroundTab` 的性能写法

**不要**用 `setTabs(prev => prev.map(...))`：多会话并行时每个 delta 都会新建数组、
遍历全部 tab、让所有 tab 组件重渲染。正确做法（idea 侧 `useSessionTabs.ts`）：
先用 `tabsRef.current` 同步查出命中的 tab id，命中才 `setTabs`；不命中**返回原数组**，
零重渲染。

---

## 4. 平台差异（idea ≠ vscode）

| 维度 | idea-gui | vscode-plugin | 移植注意 |
|---|---|---|---|
| **Node** | 需要 `NodeDetector` 扫描候选路径（Windows/Linux/macOS + WSL），产物注入 `OPENCODEBUDDY_NODE_BIN` | **直接用 VS Code 自带的 node** | 不移植 `NodeDetector`；`OPENCODEBUDDY_NODE_BIN` 取 VS Code 的 node 可执行路径即可（不要扫描、不要弹选择框）。opencode CLI 路径解析（`utils/cli-path.js` 的 L0–L4）仍然共用 |
| **存储层** | IntelliJ `PropertiesComponent` / `PersistentStateComponent` | VS Code `Memento`（`globalState` / `workspaceState`） | 会话-标签映射、per-session 偏好等落盘位置不同；**身份模型的内存结构可以照抄，持久化适配各自 API** |
| **宿主→webview 通道** | JCEF `executeJavaScript`，有 `WebviewEventQueue` 做批量/合并/背压 | `postMessage`，无 JCEF | §5 的性能一节中"JCEF 批量"那部分 vscode 不适用；但"**不要每帧全量序列化**"仍然成立 |
| **进程管理** | `OSProcessHandler` / `GeneralCommandLine` | `child_process` spawn | daemon 启停与端口复用逻辑共用 core，宿主侧各自实现 |
| **UI 容器** | ToolWindow + Content/tab | Webview Panel / View | 多会话 tab 的容器不同；`TabManager.ts`（vscode `src/host/tabs/`）是落点 |

---

## 5. 性能相关（idea 侧已修，vscode 加多会话时要避免重犯）

1. **批量出队 O(n²)**：idea `WebviewEventQueue.takeBatch` 曾对每个 call 都扫一遍已累积的
   batch。改为循环外维护一个 `boolean`。vscode 若做批量队列，直接按增量标记写。
2. **后台 tab 写入 O(tabs)**：见 §3.7。
3. **长会话每帧全量序列化**：`StreamMessageCoalescer` 已有 150ms 节流 + tail 传输；
   新增的约束是——**coalescer 没有 sessionId 时直接不推**（空 id 的快照会被前端丢弃或误归属）。
4. **泄漏对称关闭**：`registerSessionSink` / `unregisterSessionSink`、`clearSession`、
   `disposeRuntime` 必须与创建成对出现。idea 侧曾只 put 不 remove。

---

## 6. 建议移植顺序

1. **core**（已完成，vscode 自动生效）→ 先跑一遍 vscode 插件确认**没有回归**
   （旧宿主忽略不认识的字段和 tag，理论上不会坏；重点看 `[TURN_END]` 是否被当未知 tag 忽略）。
2. **宿主信封解析**（§2.1）——先只解析并记录 `session`，**暂不改变路由**，加 `unattributed` 计数。
3. **宿主 `[TURN_END]` + 终态链路**（§2.2、§2.3、§2.4）。
4. **webview 身份中枢 + 回调改签名**（§3.1、§3.2）。
5. **webview `onTurnEnd`**（§3.3）+ 测试。
6. **多会话 tab UI**（最后做；此时身份链路已经就位，UI 只是消费端）。

每一阶段的验收：
- 单会话：行为与改造前**完全一致**（这是底线）；
- 多会话：A、B 同时流式，A 的文本不出现在 B；停止 A 后 A 的 spinner 立刻停、B 不受影响；
  后台会话的权限/提问弹窗**能弹出**（而不是直接显示"已取消"）；
- 控制台 `unattributed` 计数应为 0（非 0 说明上游漏了身份）。

---

## 7. 锚点清单（便于 grep 对齐两个仓库）

| 概念 | idea-gui | vscode-plugin |
|---|---|---|
| 信封解析 | `provider/common/DaemonBridge.java` | `src/host/provider/OpenCodeDaemonBridge.ts`（`handleDaemonOutput`） |
| 按 session 路由 | `session/SessionStreamRouter.java`（新） | 待建 |
| marker 解析 | `provider/opencode/OpenCodeMarkerParser.java` | `src/host/session/MarkerParser.ts` |
| 会话回调适配 | `session/SessionCallbackAdapter.java`（`hasIdentity` / `getTargetSessionId`） | `src/host/session/SessionCallbackAdapter.ts` |
| 消息处理 | `session/OpenCodeMessageHandler.java` | `src/host/session/MessageHandler.ts` |
| 权限宿主 | `handler/PermissionHandler.java` | 待确认（vscode 的权限宿主模块） |
| 会话管理 | `session/SessionLifecycleManager.java` + `ui/toolwindow/OpencodeBuddyChatWindow.java` | `src/host/session/SessionManager.ts` + `src/host/tabs/TabManager.ts` |
| 消息合并 | `session/MessageMerger.java` | `src/host/session/MessageMerger.ts` |
| 流式合并/节流 | `session/StreamMessageCoalescer.java` | `src/host/session/StreamMessageCoalescer.ts` |
| webview 身份中枢 | `webview/src/utils/sessionIdentity.ts`（新） | 待建 |
| webview 流式回调 | `webview/src/hooks/windowCallbacks/registerCallbacks/streamingCallbacks.ts` | 同名文件 |
| webview 消息回调 | `.../messageCallbacks.ts` | 同名文件 |
| webview 权限回调 | `.../permissionCallbacks.ts` | 同名文件 |
| 序号屏障 | `webview/src/hooks/windowCallbacks/sequenceBarrier.ts` | 待建（现只有 `parseSequence.ts`） |
| 多会话 tab | `webview/src/hooks/useSessionTabs.ts` | 无（待建） |

---

## 8. 回归测试锚点（vscode 移植时照抄这批用例）

idea 侧为这次重构补了 19 个 Java 用例 + 31 个 webview 用例，全部通过
（Java 709/709，webview 1389/1389）。vscode 移植时应一一对应复刻，否则
「改完看起来能跑」无法证明身份链路真的接对了。

### 8.1 Java（`opencode-idea-gui/src/test/java`）

| 文件 | 守住的属性 |
|---|---|
| `session/MultiSessionStreamIsolationTest.java`（6） | 两个 coalescer 交替 enqueue/flush，每帧快照只盖自己的 sessionId；`null` / 空白 sessionId 一律丢弃快照但 flush 回调仍送达（否则标签页永远 loading）；`setSessionId` 可补挂身份；两个会话并发 `loadFromServer` 各自只读自己的 transcript、只回调自己的前端；一个会话加载失败不污染另一个 |
| `handler/PermissionDialogIdentityTest.java`（8） | 权限/提问弹窗身份取自请求自身（哪怕调用方传了另一个 target）；没有请求身份时可用调用方 target，但**绝不猜活跃会话**；两者都没有时仍然弹窗（回复按 id 走注册表），只是 payload 不带 sessionId；`pendingQuestions` 记录请求自己的 sessionId 供回复路由 |
| `session/OpencodePermissionRegistryMultiSessionTest.java`（5） | `consume` 取回注册它的那个会话；`clearSession` 只清该会话；溢出逐条淘汰最旧（不是整表 clear）；非 `per_` 前缀不注册 |

### 8.2 webview（`webview/src`）

| 文件 | 守住的属性 |
|---|---|
| `hooks/windowCallbacks/registerCallbacks/permissionPromptRouting.test.ts`（13） | 后台会话的权限/提问只写**持有该 sessionId 的 tab**；重复 channelId 去重；**没有任何 tab 持有该会话时仍在前台弹出**（回归「已取消却没有对话框」）；无身份时不抛；`forceClose*` 从后台 tab 摘掉；注册时 drain 挂起的 prompt |
| `hooks/windowCallbacks/registerCallbacks/messageCallbacks.historyRouting.test.ts`（7） | 后台会话快照只写自己 tab、前台 transcript 一个字符不动；`clearMessages(sessionId)` 只清指定 tab；后台 `historyLoadComplete` 不释放前台切换守卫；**没有 tab 持有该会话时落到当前视图而不是丢掉**（回归「打开了却是空的」）；前台完整加载序列 clear → snapshot → complete；切换守卫期间拒绝旧会话的旧序号帧与其他会话的帧 |
| `hooks/useSessionTabsMultiSession.test.ts`（11） | `isActiveSession(null/undefined/'')` 为 false；**未绑定 tab 不认领任意 id**；`updateBackgroundTab` 只写命中 tab 且返回 true；未命中返回 false 且不触发重渲染（数组引用不变）；可按 tab id 命中；`openSessionInTab` 复用空白草稿 tab / 为第二个历史会话新建 tab / 已打开的会话只跳转不重复加载；每个历史会话的 transcript 各归各 tab，切回去恢复的是自己的 |

### 8.3 环境注意（跑这些用例前必读）

- **轻量单测里 `ApplicationManager.getApplication()` 是 null**，而
  `StreamMessageCoalescer` 的推送走 `executeOnPooledThread` + `invokeLater`。
  `MultiSessionStreamIsolationTest` 用 `Proxy` 装了一个「同步执行」的 Application
  存根，并且**每个用例前都重新确认一次**（平台框架会在类与方法之间重置它），
  `@AfterClass` 还原。vscode 侧没有这个问题（Node 自带事件循环）。
- Java 侧跑测试要先同步影子 class：
  `cp -rf build/classes/java/{main,test}/. build/instrumented/instrument{Code,TestCode}/`，
  否则跑的是过期 class，报错信息会和源码对不上。

---

## 9. 共享查询不带会话标签（2026-10-17 线上故障，移植必读）

**故障**：点击历史会话 → 空白页，**无任何报错**。日志证据：daemon 正常播报
N 条 entry，宿主 `updateMessages(sessionId, [], seq)` + `historyLoadComplete(sessionId, 0)`。

**根因（架构层）**：core 的 `requestContextRecord()` 对**所有**入站请求都从
`params.sessionId` 取会话身份 → 共享查询（`listMessages` / `getSessionInfo` /
`preconnect` / `revert` / `fork` / `getContextUsage`…）的响应行被盖上 session 戳
→ 宿主 `deliverDataLine` 见 session 命中已注册 sink 就只投 sink 并 return
→ 查询收集器颗粒无收 → 空结果照常「成功」返回。

**逻辑教训**：`sessionId` 回答「这段内容**属于哪个会话**」，不回答「这条消息**该送给
谁**」。应答的投递指令是请求 `id`（谁在等这个答案），事件的投递指令才是 sessionId
（这是谁的流）。**把上下文当投递指令，就会把答案投进错误的房间**——而那个房间
（会话的 marker 解析器）只认 `[TAG]` 格式，一行都读不懂，静默丢弃。

**规则（已落在 core `utils/request-identity.js`，两插件共享、自动生效）**：
只有**回合命令**（`send`/`shell`）从 params 取会话身份；共享查询的输出是它自己的
响应载荷，必须保持 `id` 可归属。宿主侧配套加「播报条数 vs 实收条数」对账
（`OpenCodeSDKBridge.listMessages` 读 `messagesStart.count`，不符即抛错）——
把「加载失败伪装成空会话」变成明确报错。

**移植要点**：core 已共享（vscode 自动生效）；宿主侧的对账逻辑与下列回归测试
需要随移植带上：
- `provider/common/OpenCodeListMessagesRoutingTest.java`（3）：sink 已注册时共享查询
  响应仍达收集器；播报 3 实收 0 ⇒ 抛错；播报 0 ⇒ 正常空列表。
- `session/HistoryLoadPipelineIntegrationTest.java`（7）：daemon 形状条目 → 真实
  converter/parser/state/adapter/coalescer → 线上字节的端到端管线。
- webview `messageCallbacks.historyRouting.test.ts` 里的 `patchMessageUuid` 三例：
  宿主三参约定 `(sessionId, content, uuid)`；后台会话补丁写对应 tab；无身份即丢。

