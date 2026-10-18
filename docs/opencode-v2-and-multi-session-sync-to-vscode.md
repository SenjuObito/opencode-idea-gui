# opencode v1/v2 兼容 + 多会话功能 —— 同步至 opencode-vscode-plugin 完整方案

> 源仓库：`opencode-idea-gui`（D:/source/repos/opencode-idea-gui，当前工作区）
> 目标仓库：`opencode-vscode-plugin`（D:/source/repos/opencode-vscode-plugin）
> 共享核心：`opencode-buddy-core`（两仓库 node_modules 均 symlink 至 `../../opencode-buddy-core`，改一处两端生效）
> 撰写时间：2026-10-17。本文只给方案，不含实施。

---

## 0. 结论先行

1. **core 层无需同步，已实时共享**：v2 引擎解析（`utils/cli-path.js` 的 L0–L4）、能力探测（`utils/engine-probe.js`）、三态引擎注册表（`services/opencode/engine-registry.js`）、`daemon.js` 的身份信封与 `engine_status` 事件——全部在共享 core 里，vscode 通过 symlink 自动生效。**但 vscode `package.json:113` 把 core pin 在 `github:...#v0.1.0`**，本地 link 开发没问题，**发布/CI 会丢掉这些改动**，必须发新 tag 后重新 pin（前置项 P0-3）。
2. **真正要同步的是"宿主胶水层 + webview 身份链路 + 引擎状态展示"**。vscode 已自带：v2 路由兼容（core 承担 + f8d8071）、原生标签多会话（TabManager）、`updateDaemonStatus` 通道、`additionalEnv` 注入（但用的是旧契约 `OPENCODE_BIN`）。
3. **vscode 当前缺失（已逐条代码核实）**：
   - 引擎状态链路全缺：`onDaemonEvent` 只处理 `log` 事件（WindowEventHandler.ts:467），无 `engine_status`；`updateDaemonStatus` payload 无 `engine` 字段；webview 无 `daemonEngine` 概念。
   - CLI 规格注入是旧契约：`extension.ts:56-66` 注入 `OPENCODE_BIN`（L1），不是 core 现行的 L0 契约 `OPENCODEBUDDY_CLI_PATH` / `OPENCODEBUDDY_NODE_BIN`。
   - 宿主丢弃会话身份：`handleDaemonOutput`（OpenCodeDaemonBridge.ts:759-850）只读 `obj.id` + `obj.line`，**不读 `obj.session/turn/seq`**；无 pending 请求的行直接 `return`（静默丢弃）。
   - 无 `[TURN_END]`：MarkerParser 只有 `[STREAM_END]`（:107）。
   - 权限链路"借当前会话"：`PermissionHandler.ts:78` `request.sessionId ?? this.context.getSession()?.state.getSessionId() ?? null` —— 正是 idea 侧明令删除的缺陷模式。
   - 无 `sessionIdentity` / `sequenceBarrier` 等价物；流式回调全是单参（`callJavaScript('onContentDelta', delta)`）。
4. **已由用户手动同步、无需重复搬运的**：额度用完功能（`StatusNoticeBar.test.tsx` / `errorMatcher.ts` / `errorPatterns.json` 两仓已逐字节一致；`StatusNoticeBar.tsx` 仅差一行签名注释——idea 版是 `(sessionId, json)` 双参，属 Phase 3 回调签名升级的尾料）。
5. **一个必须由你决策的架构分歧**：idea 的多会话是 **webview 内标签栏**（一个 webview N 个 tab，后台 tab 持续流式）；vscode 是 **原生编辑器标签**（一个 WebviewPanel 一个会话，且 `SessionManager.createNewSession/switchToSession` 会**中断并 dispose 旧会话**，"不保留后台执行"，见 79779fb）。本方案默认**保留 vscode 原生标签架构、只移植共享机制**（Phase 4 选项 A）；是否反过来移植 idea 的 webview 内标签栏列为选项 B，需你拍板。
6. **四个前置项（P0-1 ~ P0-4）不解决就会踩坑**，其中最重要的是：idea 侧的引擎与多会话身份改动**大部分还在工作区未提交**（`CliSpecResolver.java`、`SessionStreamRouter.java`、`sessionIdentity.ts`、`sequenceBarrier.ts` 改动、7 份 docs 都是未跟踪/已修改状态），"按提交历史同步"只能覆盖到 `ee61e5e`；vscode 侧同样有未提交改动（StatusNoticeBar、useHistoryLoader.test.ts 等）不能被覆盖。

---

## 1. 范围界定：idea 提交历史 → 同步内容映射

| idea 提交 | 内容 | 与 vscode 的关系 | 本方案处理 |
|---|---|---|---|
| `768d992` 支持 opencode v2 / 问题弹窗 / 权限弹窗最小化 | webview 弹窗 UI + Java tab 基建 | vscode 已有对应物（f8d8071 弹窗、TabManager） | 不同步（架构等价） |
| `60e318a` 兼容 opencode v2（问题/权限/bash/opencode 命令/会话目录） | `docs/opencode-v2-adaptation-guide.md` + 一批 Java/webview | vscode 已有 v2 适配（f8d8071 + core） | 仅补引擎状态链路（Phase 1） |
| `b4be81d` / `0c66fa7` 插件内多标签页会话 / 多会话架构调整 | webview 内标签（`useSessionTabs`/`SessionTabBar`）+ Java 多会话基建 | **架构分歧**（原生标签 vs webview 内标签） | Phase 4 决策点 |
| `7134f91` 修复多会话各种问题 | **身份链路**：`sessionIdentity`、`sequenceBarrier`、`SessionStreamRouter`、`[TURN_END]`、权限 sessionId、注册表淘汰策略等（大部分未提交） | **完全缺失** | **Phase 2 + 3（本次同步主体）** |
| `ee61e5e` 额度用完错误信息展示加单测 | `RetryStatusBar.test.tsx` | vscode 已手动同步为 StatusNoticeBar | 仅对齐签名注释 |
| 工作区未提交 | 引擎模式全套（`CliSpecResolver`/`EnvironmentConfigurator`/`DaemonBridge` engine_status/`DaemonStatusHandler` payload.engine/`useUsageTracking.daemonEngine`）+ 7 份架构文档 | **完全缺失** | **Phase 1（本次同步主体）** |

---

## 2. 前置项（P0）—— 开工前必须清掉

### P0-1 idea 侧未提交改动的定性
以下文件在 idea 工作区处于未提交状态，却是本次同步的**主体内容**：

- 未跟踪：`src/main/java/com/opencodebuddy/bridge/CliSpecResolver.java`（+Test）、`session/SessionStreamRouter.java`、`webview/src/utils/sessionIdentity.ts`（+test）、`webview/src/components/StatusNoticeBar.*`、5 个新测试文件、7 份 docs
- 已修改未提交：`DaemonBridge.java`（engine_status）、`DaemonStatusHandler.java`（payload.engine）、`EnvironmentConfigurator.java`、`OpenCodeSDKBridge.java`、`OpencodeSession.java`、`useUsageTracking.ts`（daemonEngine）、`sequenceBarrier.ts`、`messageCallbacks.ts`、`permissionCallbacks.ts`、`streamingCallbacks.ts` 等

**需决策**：先在 idea 仓库提交这些改动（推荐，提交历史才与"同步"语义一致），还是允许我以工作区快照为准同步。未提交前，任何"按提交历史"的表述都不覆盖它们。

### P0-2 vscode 侧未提交改动的保护
vscode 工作区现有未提交改动：`StatusNoticeBar.*`（新）、`errorMatcher.ts`、`errorPatterns.json`、10 个 i18n locale、`ChatScreen.tsx`、`MessageItem.tsx`、`main.tsx`、`variables.less`、`useHistoryLoader.test.ts`（新）、删除 `RetryStatusBar.*`。
**同步前先在 vscode 仓库 commit 或 stash**，否则文件级覆盖会丢你的在途工作。memory 中记录的"HistoryHandler.ts / useHistoryLoader.ts 是用户进行中改动"本次 status 未见，以实际 status 为准。

### P0-3 core 的发布链
vscode `package.json:113` pin `#v0.1.0`；本地 `node_modules/opencode-buddy-core` 是 symlink（有人跑过 `pnpm run link:core`）。core 仓库自身也有未提交改动（daemon.js、cli-path.js、channel-manager.js 等）。**发布前**：core 提交 + 打新 tag → vscode pin 新 tag → `pnpm run unlink:core` 打包验证。开发期保持 link 即可。

### P0-4 架构决策（影响 Phase 4 范围）
- **选项 A（推荐）**：保留 vscode 原生标签。每个 webview 单会话，身份链路就位后并发安全；工作量集中在 Phase 1–3。
- **选项 B**：移植 idea 的 webview 内标签栏（`useSessionTabs` + `SessionTabBar` + `SessionTabContextMenu` + header.less 约 420 行 + `useSessionManagement` tab 化 + 删 `skipNewSessionConfirm` + i18n 调整 + TabStateStore 持久化）。注意：这与 vscode `79779fb`「移除旧会话运行功能，不保留后台执行」的既定方向**冲突**（idea 多标签依赖后台流式），且 vscode 旧方案文档 `docs/multi-tab-and-opencode-v2-sync-plan.md` 的 Phase 3 正是此选项、至今未实施。选 B 等于推翻 79779fb 的产品决策。

---

## 3. Phase 1 — v1/v2 引擎兼容同步（宿主 + webview，独立于多会话）

> 依据：idea `docs/cli-path-and-engine-mode-architecture.md` + `docs/engine-resolution-architecture.md`（随同步复制到 vscode docs/）。
> 原则：**判定在 core，宿主只做"注入设置 + 上报状态"，禁止宿主猜引擎版本。**

### 1.1 宿主：daemon 事件接入（`src/host/provider/OpenCodeDaemonBridge.ts`）
- `handleDaemonEvent`（:846 起）增 `engine_status` 分支：缓存快照 `{state, locked, cliPath, cliSource, cliVersion, reasonKind, reasonMessage, blocking...}`，提供 `getEngineStatus()`。
- `ready` 事件折叠 `engineMode`/`cliVersion` 头字段为初始快照（对齐 idea `DaemonBridge.java:1196-1221`：daemon 先于 engine_status 上报时的 seed）。
- daemon 重启时清空快照（对齐 idea `DaemonBridge.java:1271`）。
- 监听机制已具备（`addEventListener({onDaemonEvent})`，WindowEventHandler.ts:467 已在用），无需新建。

### 1.2 宿主：状态推送带 engine（`src/host/provider/DaemonStatus.ts` + `handlers/WindowEventHandler.ts`）
- `updateDaemonStatus` payload 增加 `engine` 字段（对齐 idea `DaemonStatusHandler.java:224-230`：`payload.add("engine", engineStatus.deepCopy())`；engine 为 null 是正常态——"尚未探测"不等于"是 v1"）。
- 三处广播点统一带上当前 engine 快照：`WindowEventHandler.ts:762/767/773/794/802/805`、`OpenCodeDaemonBridge.ts:786`、`extension.ts:262`。
- 建议抽一个 `buildDaemonStatusPayload()` 单一构造函数，避免六处各写各的。

### 1.3 webview：daemonEngine 状态（`webview/src/hooks/providers/useUsageTracking.ts`）
- 照搬 idea 未提交版：`DaemonEngineStatus` 接口（state/locked/blocking/cliPath/cliSource/cliVersion/reasonKind/reasonMessage）、`updateDaemonStatus` 处理器解析 `data.engine`、`unknown`/`blocking` 时 `console.warn` 可见化。
- 测试照搬 `useUsageTracking.test.ts` 的 engine 四例（:102-148）。
- vscode 的 `useUsageTracking` 与 idea 同名同构，可直接 diff 后合并（当前两仓该文件已差 43/73 行，需逐段核对，只取 engine 相关段）。

### 1.4 webview：未知/阻断态可见化（UI）
- idea 侧现状也仅到 `console.warn` + 类型（EnvironmentTab 尚未展示引擎信息）——**建议 vscode 做到 idea 尚未做的程度作为增强，或与 idea 保持齐平**：二选一，写入实施记录。
- 若做：`StatusNoticeBar.tsx`（已存在）复用为"引擎模式未确定/不可用"告警条（带"重新检测"出口）；`EnvironmentTab.tsx` 展示 opencode 路径/版本/引擎模式/来源；i18n 10 语言。

### 1.5 宿主：CLI 规格注入契约升级（`src/extension.ts:56-66`）
现状：
```ts
additionalEnv: () => ({ ...(tuiPath ? { OPENCODE_BIN: tuiPath.trim() } : {}), ...(production ? {AI_BRIDGE_LOG_LEVEL:'error'} : {}) })
```
目标（对齐 core `cli-path.js` L0 契约）：
| 变量 | 值 | 说明 |
|---|---|---|
| `OPENCODEBUDDY_CLI_PATH` | 设置 `opencode.tuiPath` 原值 | **L0 权威**：core 中文件不存在即**阻断式失败**（不静默降级），这正是要的行为 |
| `OPENCODEBUDDY_NODE_BIN` | `process.execPath`（VS Code 自带 node） | 平台差异：idea 需要 NodeDetector 扫描，vscode **不移植 NodeDetector**，直接用宿主 node |

- **保留** `OPENCODE_BIN` 注入作为 L1 兼容（core 仍读它），或按 idea 语义二选一——建议保留，成本零。
- 设置保存后重启 daemon：`SettingsHandler.ts:308` 已有重启入口，确认 `opencode.tuiPath` 变更触发它。
- **平台差异点**：idea 刻意不做 `Files.exists` 预校验（WSL 命名空间会让宿主持有的路径判死）；vscode 无此问题，**应在设置保存时校验路径存在性并提示**（vscode 侧可以比 idea 更严）。
- 顺带对齐 D12：vscode 的 `CliStatusHandler`/`OpenCodeCliLocator.ts`（设置页"已安装"展示）与 core `cli-path.js` 候选规则保持一致——至少让设置页展示的 version/path 与 daemon 实际用的同源。

### 1.6 验收
- 仅装 v2 → `state=v2`；仅装 v1 → `v1`；都不装 → `unknown` + 告警条/console.warn，**绝不静默按 v1 路由**；
- 设置指向无效路径 → 阻断式提示（不 fallback）；
- 改设置 → daemon 重启 → 重新解析锁定；
- 回归：Windows + npm 全局 `.cmd` + v2 服务（core 单测已覆盖，vscode 跑 core 单测确认 symlink 生效）。

---

## 4. Phase 2 — 多会话身份链路：宿主侧（本次同步主体）

> 依据：idea `docs/multi-session-refactoring-porting-guide.md` §2（随同步复制）+ `docs/multi-session-identity-refactoring.md`。
> 一句话原则：**会话身份是一等字段，不是推断产物；空 id 是"不可归属"，绝不等于"当前激活会话"；拿不到身份就丢弃并计数，不许猜。**

### 2.1 信封解析与按 session 路由（`OpenCodeDaemonBridge.ts:759-850`）
1. 解析 `obj.session`（以及 `obj.turn`/`obj.seq`），与 `obj.id` 分开保存；
2. 数据面板**按 `session` 路由**到该会话的 parser/sink（新建 `SessionStreamRouter` 等价物，语义照搬 idea `session/SessionStreamRouter.java`：每会话一个 marker parser + message handler 常驻实例，out-of-turn 事件——标题、todo、`message.removed`、prompt close、迟到的 error——不再无家可归）；
3. 只有 `session` 缺失时才退回按 `id` 找 pending handler，并把缺失**计入 `unattributed` 计数** + warn；
4. 提供 `registerSessionSink(sessionId, sink)` / `unregisterSessionSink(sessionId)`，会话关闭对称注销（防泄漏）。

> **过渡策略**（照搬 idea P1→P1.5 gate）：先只解析+计数+不改变路由，控制台 `unattributed` 归零后再翻转到按 session 路由。

### 2.2 `[TURN_END]` 权威幂等终态（`src/host/session/MarkerParser.ts`）
- 新增 `[TURN_END]` 分支（参考 :107 `[STREAM_END]`），终态携带 `sessionId` + `turnId`（turnId 由 core 从服务端 `durable.seq` 取，**宿主不自造计数器**）；
- 终态必须发到 payload 指定的会话；按 `(sessionId, turnId)` 幂等；清 loading/streaming/thinking。

### 2.3 权限/提问带上 sessionId（`handlers/PermissionHandler.ts` + `session/MessageHandler.ts`）
- **必删**：`PermissionHandler.ts:78` 的 `request.sessionId ?? this.context.getSession()?.state.getSessionId() ?? null` 兜底——它是"会话 B 的权限弹窗显示在会话 A"和"弹窗压根不出现"的共同来源；
- `PermissionRequest.sessionId`（types.ts:33 已存在但可选）改为**必填**，由 marker payload 填充；
- `MessageHandler.ts:895` 同类借用法一并清除。

### 2.4 中断不能静默（`SessionHandler` / `sendAbort` 路径）
- 查不到目标会话时**先释放前端**（`onStreamEnd` + `showLoading false`）再返回，而不是什么都不做（对齐 idea `SessionHandler.handleInterruptSession`）。

### 2.5 待决权限表溢出策略（vscode 权限注册表等价物）
- 溢出时**逐条淘汰最旧**（原来是整表清空，会连带丢掉别的会话的待决请求）；新增 `clearSession(sessionId)`，在 `closeSession` 调用。

### 2.6 验收
- 控制台 `unattributed` 计数为 0（非 0 说明上游漏身份）；
- A、B 两个原生标签同时流式：A 的文本不出现在 B；
- 停止 A：A 的 spinner 立刻停、B 不受影响；
- 后台/空闲态权限弹窗能弹出（而不是直接显示"已取消"）。

---

## 5. Phase 3 — 多会话身份链路：webview 侧

> 依据：porting guide §3。除特别说明外**照搬文件 + 照搬测试**，不要重写。

### 3.1 身份解析中枢（新文件，零依赖纯函数）
- 新建 `webview/src/utils/sessionIdentity.ts`：`resolveSessionId(targetSessionId, kind)`（空 → null + 计数 + 告警）、`droppedCount(kind)` / `resetDroppedCounts()`；
- 测试照搬 `sessionIdentity.test.ts`（6 例）。

### 3.2 回调签名改 `(sessionId, payload)`
- 过渡期兼容双形态：`window.onContentDelta = (arg0, arg1?) => { const delta = arg1 !== undefined ? arg1 : arg0; ... }`，**只在有身份时才写入**；宿主改完后删兼容分支；
- `global.d.ts` 签名更新：`onContentDelta`、`onThinkingDelta`、`onStreamEnd`、`showLoading`、`updateMessages`、`updateMessageTail`、`onBlockReset`、`onStreamingHeartbeat`、`updateRetryStatus`；
- 宿主侧 `SessionCallbackAdapter.ts` 的 `callJavaScript` 调用点同步首参注入 `sessionId`（当前 :66/:71/:124/:245-249/:280 等处全是单参）。

### 3.3 `onTurnEnd` 新回调
- `registerCallbacks/streamingCallbacks.ts` 末尾照搬 idea 实现：无身份 → 丢；后台会话 → 写后台快照 loading/streaming/thinking = false；前台 → 清全局 + `finalizeUnresolvedToolUses()`；按 `${sessionId}#${turnId}` 幂等（集合上限 256 清空）；
- 测试照搬 `turnEnd.test.ts`（7 例）。

### 3.4 禁止"空 id → 当前会话"
- 新增代码一律 `resolveSessionId(...)`，null 时 `return`；review checklist 设为必查项（这是加多会话 UI 时最容易重新引入的模式）。

### 3.5 序号屏障 per-session 化（新建 `sequenceBarrier.ts`）
- **直接按 per-session 写，不要先写全局再改**：`getMinAcceptedSequence` 删除全局基线兜底（未知会话返回 0，取放行方向）；`isForegroundSession(null)` 从 true 改 false；
- 理由：单一全局基线会让"会话 A 的高序号"永久卡死"刚加载的会话 B 的快照"。vscode 现只有 `parseSequence.ts`。

### 3.6 coalescer 无身份不推
- `StreamMessageCoalescer` 推送前检查 sessionId，空 id 的快照直接不推（会被前端丢弃或误归属）。

### 3.7 验收
- **单会话行为与改造前完全一致（底线）**；
- 多会话隔离（同 2.6）；
- 测试：`pnpm test`（webview）+ `pnpm run compile`（宿主）。

---

## 6. Phase 4 — 多会话 UI（依赖 P0-4 决策）

### 选项 A（默认）：保留原生标签，收口身份链路
- 身份链路就位后，原生标签模型（每 webview 单会话）的并发安全由 Phase 2/3 保证；
- 收口工作：`closeSession`/标签关闭 → `clearSession(sessionId)` + `unregisterSessionSink(sessionId)` 对称注销；`create_new_tab` 与新会话创建路径复核身份注入；
- `useSessionManagement.ts` 两仓现有 130 行差异逐段核对，只合并且与身份相关的部分（新建/切换时的清理序列）。

### 选项 B：移植 idea webview 内标签栏（大改，需你先拍板）
- 新增：`hooks/useSessionTabs.ts`（222 行）+ `components/ChatHeader/SessionTabBar.tsx` + `SessionTabContextMenu.tsx` + `types.ts` + `header.less` 标签栏样式（约 420 行）；
- 改造：`ChatHeader.tsx` 左区换标签栏、`App.tsx` 接入、`main.tsx` 预注册 `applyBackendTabState`、`useDialogManagement` 挂 `__dialogStore`（切 tab 保存/恢复弹窗）、`useSessionManagement` tab 化；
- 删除：`utils/skipNewSessionConfirm.ts`（D3，标签即新会话）+ 设置页 `newSessionConfirm` 文案（zh.json 等 10 语言，idea 已删、vscode 还在）；
- 新增宿主：`SessionRegistry`（多会话注册表，取代单 active）、`TabStateStore`（Memento 持久化 `opencode.tabs.<hostKind>`）；
- 测试照搬：`ChatHeader.test.tsx`、`useSessionTabsContextDecoupling.test.ts`、`useSessionTabsMultiSession.test.ts`；
- **注意与 79779fb 的方向冲突**：idea 多标签依赖后台会话持续流式，vscode 现行语义是"新建/切换一律中断并释放旧会话"。选 B 必须同时改 `SessionManager.createNewSession/switchToSession` 的 dispose 语义。

---

## 7. Phase 5 — 测试、文档、发布

### 7.1 测试清单
| 层 | 照搬/新增 | 命令 |
|---|---|---|
| core | 已有（`engine-registry.test.js` / `cli-path.test.js` / `engine-probe.test.js` / `opencode-serve-manager.test.js`）——vscode 跑一遍确认 symlink 生效 | core: `npm test` |
| vscode 宿主 | 新增：信封 session 路由、TURN_END 幂等、权限 sessionId 不借会话、中断释放前端、注册表淘汰；扩展现有 `multiInstanceAndAbortIsolation.test.ts` | `pnpm run compile` + `vscode-test` |
| vscode webview | 照搬：`sessionIdentity.test.ts`(6)、`turnEnd.test.ts`(7)、`useUsageTracking.test.ts` engine 四例；新建 sequenceBarrier 测试 | `pnpm test` |
| 注意 | vscode 仓库 HEAD 上**已有 23 个既有失败**（`ChatInputBox/types.test.ts`、`MarkdownBlock.test.tsx`、`useSubmitHandler.test.ts`），不是本次引入；判据是 worktree 检出 HEAD 复跑失败数一致 | — |

### 7.2 文档同步（复制到 vscode `docs/`）
- 从 idea 复制：`cli-path-and-engine-mode-architecture.md`、`engine-resolution-architecture.md`、`multi-session-identity-refactoring.md`、`multi-session-refactoring-porting-guide.md`（porting guide 中"待建"项随实施更新为实际落点）；
- 更新 vscode 既有：`multi-tab-and-opencode-v2-sync-plan.md`（标注 Phase 3 未实施/被原生标签路线取代，或按 P0-4 决策改写）、`multi-tab-and-session-isolation.md`、`opencode-v2-adaptation-guide.md`；
- 行尾：vscode 工作区多为 CRLF，复制时按目标行尾还原（直接 cp 会让 git diff 整文件翻红）。

### 7.3 发布链
- core 提交 + 新 tag（含 `55eec03` 之后的引擎/身份全部改动）→ vscode `package.json:113` pin 新 tag + `tools/prepare-core.mjs` 同步 → `pnpm run unlink:core` 后 `pnpm run package` 验证。

---

## 8. 风险与回滚

| 风险 | 缓解 |
|---|---|
| core 旧缓存："改了没生效" | vscode 确认 `node_modules/opencode-buddy-core` 仍是指向 sibling 的 symlink；发布包走新 tag |
| 回调双签名过渡期宿主/ webview 改到一半 | 严格按 3.2 顺序：先兼容双形态 → 宿主改完 → 删兼容分支 |
| L0 阻断式失败误伤（用户设置路径在 WSL/容器另一命名空间） | vscode 无 WSL 场景，设置保存时校验存在性；core 侧 L0 语义保持不变 |
| Windows `.cmd` shell 注入面 | core 已处理（`shell:true` + 引号）；vscode 注入路径时不做 shell 拼接 |
| `unknown` 期间 UX 空洞 | 告警条必须给可操作出口（重新检测/打开设置/查看日志），参考 StatusNoticeBar |
| 每阶段独立可回滚 | Phase 1 独立；Phase 2/3 需一起回滚（宿主与 webview 签名耦合）；Phase 4 独立 |

---

## 9. 附录 A：文件落点对照表（idea → vscode）

| 概念 | idea-gui | opencode-vscode-plugin 落点 | 状态 |
|---|---|---|---|
| CLI 规格注入 | `bridge/CliSpecResolver.java` + `EnvironmentConfigurator.java`（未提交） | `extension.ts:56-66` additionalEnv | 待升级契约 |
| 引擎事件解析 | `provider/common/DaemonBridge.java:1049-1057`（未提交） | `provider/OpenCodeDaemonBridge.ts` handleDaemonEvent(:846) | 待建 |
| ready 头折叠 | `DaemonBridge.java:1196-1221` | `OpenCodeDaemonBridge.ts` ready 分支 | 待建 |
| 状态 payload.engine | `handler/DaemonStatusHandler.java:224-230` | `provider/DaemonStatus.ts` + `handlers/WindowEventHandler.ts` 六处广播点 | 待建 |
| webview 引擎状态 | `hooks/providers/useUsageTracking.ts`（未提交） | 同名文件 | 待合段 |
| 信封 session 解析 | `DaemonBridge.java` handleDaemonOutput/bufferOrphanLine | `OpenCodeDaemonBridge.ts:759-850` | 待建 |
| 按 session 路由 | `session/SessionStreamRouter.java`（未跟踪） | 待建（host/session/ 下） | 待建 |
| `[TURN_END]` | `OpenCodeMarkerParser.java:136` → MessageHandler → Adapter → JS | `session/MarkerParser.ts`（参考 :107） | 待建 |
| 回调身份注入 | `session/SessionCallbackAdapter.java`（hasIdentity/getTargetSessionId） | `session/SessionCallbackAdapter.ts`（:56-374 单参） | 待改 |
| 消息处理 | `session/OpenCodeMessageHandler.java` | `session/MessageHandler.ts`（:895 借用法待删） | 待改 |
| 权限宿主 | `handler/PermissionHandler.java`（删除借会话兜底） | `handlers/PermissionHandler.ts:78`（**同款缺陷**） | 待改 |
| 权限注册表 | `session/OpencodePermissionRegistry.java`（逐条淘汰 + clearSession） | 权限注册表等价物 | 待建 |
| 会话管理 | `SessionLifecycleManager.java` + `OpencodeBuddyChatWindow.java` | `session/SessionManager.ts` + `tabs/TabManager.ts` | 架构分歧（P0-4） |
| webview 身份中枢 | `utils/sessionIdentity.ts`（未跟踪） | 待建（同名） | 照搬 |
| 序号屏障 | `hooks/windowCallbacks/sequenceBarrier.ts`（未提交改动） | 待建（现只有 `parseSequence.ts`） | 照搬 |
| 流式回调 | `registerCallbacks/streamingCallbacks.ts`（onTurnEnd） | 同名文件 | 照搬 |
| 多会话 tab | `useSessionTabs.ts` / `SessionTabBar.tsx` / `SessionTabContextMenu.tsx` | 无 | P0-4 决策 |

## 10. 附录 B：明确不移植清单（平台差异）

- `NodeDetector`（idea 的 node 路径扫描）——vscode 用 `process.execPath`；
- `WebviewEventQueue` 的 JCEF 批量/背压——vscode 用 postMessage；但"不要每帧全量序列化"仍成立；
- IntelliJ `PropertiesComponent`/`PersistentStateComponent`——用 VS Code Memento（选项 B 的 TabStateStore 才需要）；
- `OSProcessHandler`/`GeneralCommandLine`——vscode 用 `child_process`（core 已统一 daemon 启停）；
- idea 的 `global.d.ts`/i18n/Chrome 相关既有差异——不同步。

---

## 11. 执行顺序 checklist

1. [ ] P0-1 idea 未提交改动 commit（或确认工作区快照口径）
2. [ ] P0-2 vscode 未提交改动 commit/stash
3. [ ] P0-3 core commit + 新 tag（开发期可先 link）
4. [ ] P0-4 架构决策（选项 A / B）
5. [ ] Phase 1 引擎兼容（1.1→1.5）→ 验收 1.6
6. [ ] Phase 2 宿主身份链路（2.1 先只解析计数 → gate → 2.2-2.5）→ 验收 2.6
7. [ ] Phase 3 webview 身份链路（3.1-3.6）→ 验收 3.7
8. [ ] Phase 4（按 P0-4 结论）
9. [ ] Phase 5 测试/文档/core pin/发布验证
