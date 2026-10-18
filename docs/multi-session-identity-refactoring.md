# 多会话身份 —— 重构方案

> **本文取代** `docs/multi-session-isolation-remediation-plan.md`（那版是"渐进迁移"视角，含兼容开关与灰度 gate，
> 已按"这是一次重构、不需要向下兼容"的要求废弃）。
> 诊断与证据链见 `docs/multi-session-isolation-architecture.md`。
> **本文只描述目标态与落地动作，不含过渡态。** 未开始编码。

---

## 0. 重构目标与验收判据

按五条要求逐条给出**可检验**的判据（不是形容词）：

| # | 要求 | 判据 | 验证方式 |
|---|---|---|---|
| 1 | **架构清晰** | 会话身份只有一个生产点（传输层信封），零推断；每层职责可用一句话描述且无重叠 | §2.1 职责表；§2.4 禁止清单的静态检查可全绿 |
| 2 | **职责分明** | 每个状态有唯一 owner（§2.3）；不存在"多个单槽竞争裁决权"；不存在全局单槽 | 静态: 全仓 `grep` 不得出现"消费侧读当前会话"的模式 |
| 3 | **流程时序正确** | 四条主流程（发送/流式/终态、权限/提问、会话切换、中断）满足 §3 时序图；终态幂等且按会话 | 时序相关的单测 + 手动脚本 |
| 4 | **功能符合预期可用** | 三症状消失：多会话不串、弹窗必现、停止后转圈即灭 | §3 的端到端用例 |
| 5 | **无性能问题** | 逐条热路径复杂度和内存生命周期达标（§6） | 复杂度表 + 长会话基准 + 会话关闭后内存回落 |

**不变式（目标态必须成立，可测）**

| ID | 内容 |
|---|---|
| **INV-A** | 任何会话级跨层消息**必须**携带 `sessionId`；缺失即**丢弃并计数**，**不得**回落 |
| **INV-B** | 会话内事件由服务端 `durable.seq` **单调定序**；回合由 `turnId` 标识，`sessionId + turnId` 唯一定位一条流 |
| **INV-C** | 回合终态是**显式、幂等、按会话**的事件；到达即置该会话终态，**不论该会话是否激活** |
| **INV-D** | 会话的 UI 运行时状态只由该会话自己的状态机写；无全局单槽，无"全局 ↔ 快照"搬运 |

---

## 1. 缺陷的架构定位：会话身份**没有所有者**

现状不是"少了一个字段"，而是**没有任何一层拥有会话身份**。于是 5 个"当前会话型"单槽 + 2 个全局单槽
各自独立地行使归属裁决权，每一层推断失败都回落成"当作当前会话"，而**没有任何一层选择拒绝**：

| 层 | 单槽 | 位置 |
|---|---|---|
| core 传输 | `backgroundStreamRequestId` | `core/daemon.js:64`（被任何 turn 覆盖，`:436-439`） |
| core 事件 | `_activeTurns` 未命中 → 无 ALS | `core/services/opencode/opencode-daemon-service.js:416-421` |
| 宿主窗口 | `session` / `sessionId` / `activeSessionId` / `streamCoalescer` / `sessionCallbackAdapter` | `OpencodeBuddyChatWindow.java:77,88,92,94,211` |
| 宿主回调 | `SessionCallbackAdapter.sessionId`（5 参构造为 `null`） | `SessionCallbackAdapter.java:42,55,102-104` |
| webview 回调 | `targetSessionId \|\| currentSessionIdRef.current` | `streamingCallbacks.ts:459-460`、`messageCallbacks.ts:174,433,560` |
| **全局** | `loading/streamingActive/isThinking/status` | `MessagesContext.tsx:47-57` |
| **全局** | 单模态弹窗 + 全局队列 | `useDialogManagement.ts:65-82` |

**架构缺陷的本质**：把"**信令**（谁属于谁）"和"**内容**（字节流）"放在同一条通道里，
用同一个字符串（`requestId`）同时承担两件事，再用"当前活跃 turn"去猜。这是
`daemon.js:208-223` 那个 `{id, line}` 信封的直接后果。

---

## 2. 目标架构

### 2.1 分层与职责（一句话职责，边界不重叠）

| 层 | **唯一职责** | 拥有的状态 | 明确禁止 |
|---|---|---|---|
| **core: SSE 适配** | 把服务端 SSE 事件**翻译**成带身份的 marker | `_sessions` / `_activeTurns`（按 sessionId） | 禁止任何"当前会话"概念 |
| **core: 传输** | 给**每一行**输出贴 `(id, session, turn, seq)` 信封 | 无（纯函数式包装） | 禁止单槽兜底；禁止借用他人 id |
| **宿主: demux** | 按 `session` 把行**路由**到该会话的 adapter | `sessionAdapters` 注册表 | 禁止按"当前会话"兜底；禁止丢弃无 session 行而不计数 |
| **宿主: session 状态** | 维护每个会话的消息/元数据/在途请求 | `SessionState`（已存在，per-session）+ future 注册表 | 禁止跨会话共享可变状态 |
| **宿主: 桥接** | 把 Java 事件映射为 webview 调用（**唯一**前端出口） | `WebviewEventQueue`（按会话分区） | 禁止在桥接层做归属判定 |
| **webview: store** | 按 sessionId 存运行时状态（`Map<sessionId, Runtime>`） | 全部会话运行时状态 | 禁止全局单槽；禁止"当前会话"回落 |
| **webview: 视图** | 只渲染"当前活动 tab 指向的 sessionId"的状态 | 无（派生） | 禁止写状态 |

**关键结构变化：会话身份的生产点收敛为 1 个 —— 传输层信封。** 其余各层只做**透传**和**按身份分派**。

### 2.2 核心概念（数据契约）

```jsonc
// 统一事件信封（core → 宿主，NDJSON，一行一个）
{
  "id": "7",            // 请求级应答关联键；仅控制面回执需要，否则 null
  "session": "ses_x",   // 会话身份。任何会话级事件必填；缺失 = 非法
  "turn": 1042,         // turnId：本回合首个事件的 durable.seq；无回合时为 null
  "seq": 1047,          // 事件序号：服务端 durable.seq（会话级单调）
  "line": "[CONTENT_DELTA] \"hi\""   // 原样 marker，不做二次解析
}
```

**为什么 `id` 和 `session` 必须并存**：二者回答不同问题——
`id` = "这条是对哪个请求的回执"（请求-响应配对，HTTP 语义）；
`session` = "这条内容属于哪个会话"（数据归属）。
现状把二者压成一个字符串，一旦并发即分离且不可恢复。

**`turnId` 的定义（精确）**：`durable.seq` 是**会话级**单调（`aggregateID = sessionID`），
不是回合级。因此 `turnId` 取**本回合并第一个 SSE 事件的 `seq`**——会话级单调保证回合之间天然递增且唯一。
> 不采用 `session.execution.started` 作为回合边界：其 payload 未实测，不作为依赖。
> 也**不**自造计数器：进程重启会导致自造序号错乱，而服务端 `seq` 由服务端保证。

**控制面 / 数据面分离**（"职责分明"的关键）：

| 面 | 事件 | 处理策略 |
|---|---|---|
| **控制面** | `[MESSAGE_START]`/`[SESSION_ID]`/`[STREAM_END]`/`[TURN_END]`/`[PERMISSION_*]`/`[MESSAGE_REMOVED]` | 严格有序、不可合并、不可丢 |
| **数据面** | `[CONTENT_DELTA]`/`[THINKING_DELTA]` | 可合并、可丢、按 `seq` 保序 |

分类依据就是信封的 `turn`/`seq` + marker TAG，**不再靠"当前 turn"推断**。

### 2.3 状态所有权表（消除 5+2 单槽竞争）

| 状态 | 唯一 owner | 生命周期 | 现状问题 |
|---|---|---|---|
| 会话身份 `sessionId` | core 传输信封 | 随事件 | 现由 5 层各自推断 |
| `_activeTurns[sessionId]` | core: SSE 适配 | 回合开始 → `_settleTurn` | 已有（per-session，正确） |
| 每会话消息 | 宿主 `SessionState` | 会话创建 → 关闭 | 已有 |
| 每会话 adapter | 宿主 `sessionAdapters` | `registerSession` → **需补 `unregisterSession`** | 只 put 不 remove（泄漏） |
| 每会话在途请求 | 宿主 future 注册表（**按 sessionId 索引**） | 请求发起 → 结算 | 现为全局静态 Map（`OpencodePermissionRegistry.java:36`） |
| **每会话运行时状态**（loading/streamingActive/isThinking/status/todos/pendingPrompts） | webview `Map<sessionId, Runtime>` | **会话 tab 关闭 → delete** | 现为全局单槽（`MessagesContext.tsx:47-57`） |
| 弹窗 | 每会话 `pendingPrompts[sessionId]` 队列 | 请求 → 答复 | 现为单模态 + 全局队列 |
| 活动 tab（纯 UI） | webview `useSessionTabs` | — | 保留 |

### 2.4 依赖规则与禁止清单

**依赖方向**（单向，不允许反向）：

```
core SSE 适配 ──▶ core 传输 ──▶ 宿主 demux ──▶ 宿主 session 状态 ──▶ 宿主桥接 ──▶ webview store ──▶ 视图
```

**禁止清单**（每条都可用 grep 静态检查）

| # | 禁止 | 检查方式 |
|---|---|---|
| N1 | 传输层做"当前会话"兜底 | `grep -n backgroundStreamRequestId core/` → 0 |
| N2 | 消费侧用"当前会话"弥补缺失身份 | `grep -rn "currentSessionIdRef.current" webview/src/hooks/windowCallbacks/` → 仅允许出现在**新建会话**处（≤2 处） |
| N3 | 宿主从 adapter 字段"借" sessionId | `grep -n "getTargetSessionId()" SessionCallbackAdapter.java` → 仅允许出现在自身校验处 |
| N4 | 按 `id` 做会话归属判定 | `grep -n "getRequestHandler(id)" DaemonBridge.java` → 仅用于控制面回执 |
| N5 | 全局单槽承载会话状态 | `grep -n "useState<boolean>(false)" MessagesContext.tsx` → 仅 UI 级 |
| N6 | 静默丢弃（无计数、无日志） | 所有 `return` 早退路径必须伴随 `orphanCount++` 或 WARN |
| N7 | 跨会话合并 delta | `WebviewEventQueue.mergeWithTailDelta` 必须校验 `session`（已有会话校验，改为读信封字段而非 `args[0]`） |

---

## 3. 正确时序（重构后应如此）

### 3.1 发送 → 流式 → 终态

```
用户发送(P)                                                      webview
  │ send(prompt)  ────────────────────────────────────────────────▶│
  │   { sessionId: "ses_x" }  ← 前端必须带（活动 tab 的 sessionId） │
  ▼                                                                │
宿主 SessionHandler.sendMessage                                    │
  │ 校验 sessionId 非空（空 → 拒绝 + 报错，不猜）                    │
  │ 转发 daemon: { id:"7", method:"opencode.send", params:{sessionId} }
  ▼
core daemon.processRequest
  │ turn 命令 → backgroundStream = { requestId:"7", sessionId:"ses_x" }
  ▼
core sendMessagePersistent
  │ beginStream(ses_x) / emitSessionId(ses_x)   ── 信封 {id:7, session:ses_x, turn:null, seq:null}
  │ _createTurn("7", "ses_x") → _activeTurns.set("ses_x", turn)
  ▼
服务端 SSE: session.execution.started
  │ _handleEvent: sessionID = ses_x ; turn.turnId = durable.seq  ← 回合起点登记
  ▼
SSE: message.part.delta ×N
  │ _handleEvent → runWithContext({id:7, sessionId:ses_x, turnId, seq})
  │ → [CONTENT_DELTA] → 信封 {id:7, session:ses_x, turn:1042, seq:1047}
  ▼
宿主 DaemonBridge
  │ session 非空 → sessionAdapters.get("ses_x") → adapter.onLine(line)
  ▼
宿主 adapter → StreamMessageCoalescer（150ms 节流 + tail transport）
  │ 信封 session 已确证，推送参数 sessionId 直接来自信封，不再 getTargetSessionId()
  ▼
webview store
  │ runtime = runtimes.get("ses_x")   ← O(1)
  │ runtime.streamingContent += delta ; rAF 批处理
  │ 若 ses_x 非激活 → 只写 runtime，不触发活动视图 re-render（细粒度订阅）
  ▼
SSE: session.idle / abort / error
  │ _settleTurn(turn) → [TURN_END]{sessionId:"ses_x", turnId:1042, reason:"idle"}
  │ （幂等：turn.settled 已存在，重复调用直接 return）
  ▼
宿主 → webview onTurnEnd(sessionId, payload)
  │ runtime = runtimes.get(sessionId)    ← 按身份，不按"是否激活"
  │ runtime.state = settled(turnId) ; loading = false ; streamingActive = false
  ▼
视图：当前活动 tab 的 runtime 派生显示（单一真源）
```

**关键差异**：
- 每一跳都带身份，无一处回落；
- `turnId` 使终态**幂等**，因此可以安全接收重复/迟到信号；
- 终态**不做激活态分流**（INV-C），激活显示由视图派生。

### 3.2 权限 / 提问

```
服务端 SSE: permission.asked ─▶ core _handleEvent
  │ normalizePermissionRequest(props) 已含 sessionId（event-normalize.js:43）
  │ 信封 {session: ses_x, turn, seq} + [PERMISSION_REQUEST]{sessionId: ses_x, ...}
  ▼
宿主 OpenCodeMessageHandler.handlePermissionRequest
  │ 校验 payload.sessionId 非空（空 → 拒绝 + WARN，不回落到当前会话）
  │ PermissionRequest 构造时**必填** sessionId（新增字段）
  │ 注册到 pendingPrompts[sessionId] + future + safety-net（超时自动 DENY）
  ▼
webview showPermissionDialog(request)
  │ sessionId = request.sessionId（不再依赖参数）
  │ 该会话激活 → 开弹窗
  │ 非激活      → 入 runtimes.get(sessionId).pendingPrompts 队列 + tab 角标（**不丢弃**）
  │ 该会话尚无 tab → 入 orphan 队列（按 sessionId 归集，有上限+TTL），tab 出现后自动认领
  ▼
用户答复 → 宿主按 sessionId 找到 future → 结算 → 移除 pendingPrompts 与 safety-net
```

**与现状的差异**：现状 `PermissionRequest` 无 `sessionId`（`PermissionRequest.java:12-18`），
前端会话身份来自 `getTargetSessionId()`（可空，空返回 `""`），且 `updateBackgroundTab` 谓词不命中即
**静默 no-op**（`useSessionTabs.ts:337`）→ 请求彻底消失 → 服务端结算 rejected → 卡片显示"已取消"。

### 3.3 会话切换（无搬运）

```
用户点 tab B
  ▼
webview setActiveTab("tab-b")
  │ activeTabId = tab-b ; 只改"读哪个 key"
  │ **不**清空全局 messages，**不**搬运快照
  ▼
视图读取：runtimes.get(tabB.sessionId)  ← 直接命中
```

**删除**：`saveCurrentTabSnapshot` / `selectTab` 的 `setMessages(targetTab.messages)` 等"全局 ↔ 快照"双向搬运
（`useSessionTabs.ts:411-474`）、`beginSessionTransition` 的整屏清空（`useSessionManagement.ts:116-142`）
与 15s 超时（`:186-195`）。

> 现状的 `__sessionTransitioning` 全局锁会**吞掉**切换窗口内的终态事件（`streamingCallbacks.ts:592`），
> 是"停止后 loading 不灭"的三条路径之一。目标态下切换是纯 UI 操作，不存在需要"锁"的共享状态。

### 3.4 中断

```
用户点停止
  ▼
webview interruptSession(sessionId)          ← 明确传 sessionId（不再取 currentSessionIdRef）
  │ runtimes.get(sessionId).state → settled(turnId, 'aborted')  立即、本地、幂等
  │ 记录 settledTurnId，后续该 turnId 的 delta 一律丢弃
  ▼
宿主 SessionHandler.handleInterruptSession
  │ getSession(sessionId) 查不到 → **WARN + 回 echo onTurnEnd**（不静默跳过）
  ▼
core abortCurrentTurn(sessionId)
  │ turn.aborted = true
  │ 本地立即 emit [TURN_END]{reason:'aborted'}（幂等）
  │ 同时 HTTP abort 照发
```

**与现状的差异**：现状 `abortCurrentTurn` 自身不发终态（`opencode-daemon-service.js:1369`），
靠服务端 SSE 补发；服务端不补发时不再有本地 180s 兜底（已移除 `TURN_TIMEOUT_MS`）。
且 `ensureStreamingActive()`（`streamingCallbacks.ts:452-457`）会被一个 trailing delta 重新点亮。

---

## 4. 落地清单

> 行号为 2026-10-16 工作区状态。`core` = `D:/source/repos/opencode-buddy-core`。

### 4.1 core

| 文件:行 | 改动 | 理由 |
|---|---|---|
| `request-context.js`（全文） | 约定 store 形状为 `{ id, sessionId, turnId, seq }` | 唯一作用域通道 |
| `daemon.js:64-70` | 删 `backgroundStreamRequestId`；改 `backgroundStream = {requestId, sessionId}` + `currentContext()` | 消除单槽（N1） |
| `daemon.js:208-223` | 信封改 `{id, session, turn, seq, line}`；**单行快速路径**（无 `\n` 时跳过 `split`，见 §6.2） | 身份生产点（唯一） |
| `daemon.js:279-285,297,301,436-439,526-538,563,580` | 全部改用 `currentContext()` | 一致性 |
| `opencode-daemon-service.js:416-421` | `runWithContext` **无条件** `requestContext.run({id:turn?.requestId ?? null, sessionId, turnId: turn?.turnId ?? null, seq: props?.durable?.seq ?? null}, fn)` | 修身份丢失单点 |
| `opencode-daemon-service.js:902-930` | `_createTurn(requestId, sessionId)` 增加 `sessionId` / `turnId` 字段 | 终态需要身份 |
| `opencode-daemon-service.js:404-410` 后 | `turn` 存在且 `turn.turnId == null` 时，用 `props.durable.seq` 登记回合起点 | turnId 定义（§2.2） |
| `opencode-daemon-service.js:508,614,616,808,810` | `[CONTENT_DELTA]`/`[THINKING_DELTA]` **不再需要**单独补 session（信封已带） | 杠杆点：不必改发射点 |
| `opencode-daemon-service.js:749,759` | `[QUESTION_CLOSED]`/`[PERMISSION_CLOSED]` 补 `sessionID`（用于带内冗余校验，见宿主） | 下降沿身份 |
| `opencode-daemon-service.js:881-900` | `_settleTurn` 发 `[TURN_END]{sessionId, turnId, reason}`；保留 `turn.settled` 幂等 | INV-C |
| `opencode-daemon-service.js:1369-1393` | `abortCurrentTurn` 本地**立即**发射 `[TURN_END]{reason:'aborted'}` | 移除"180s 兜底" |
| `marker-protocol.js` | 新增 `emitTurnEnd(sessionId, turnId, reason)` | — |

### 4.2 宿主

| 文件:行 | 改动 | 理由 |
|---|---|---|
| `DaemonBridge.java:804-831` | 解析 `session`/`turn`/`seq`；**`session != null` → 按 session 路由到 `sessionAdapters`**；`id` 仅用于控制面回执；`session == null` 的会话级行 → `orphanCount++` + WARN | demux 唯一职责 |
| `DaemonBridge.java` 新增 | `SessionAdapterRegistry`（sessionId → adapter），由窗口注入 handlerContext | 消除静态/单槽 |
| `OpencodeBuddyChatWindow.java:89,1604-1618` | `sessionAdapters` 暴露 getter；**新增 `unregisterSession`**（配对的 remove，防泄漏） | §2.3 生命周期 |
| `OpencodeBuddyChatWindow.java:2523-2541` | 5 参构造删除；主 adapter **构造时**带 sessionId | N3 |
| `SessionCallbackAdapter.java:41-66,95-104` | `sessionId` 必填（构造抛错而非 null）；`getTargetSessionId()` 删除（调用方改读信封/请求对象） | N3 |
| `SessionCallbackAdapter.java:140-145` | **删除** `loading=false` 抑制守卫 | INV-C（有了 turnId 幂等） |
| `OpenCodeMarkerParser.java:131-134,200-203` | `[STREAM_END]`/`[MESSAGE_START]` 保留 `<sid>` 作为**带内冗余**；与信封不一致时以信封为准 + WARN | 双源校验 |
| `OpenCodeMarkerParser.java` | 新增 `[TURN_END]` → `onMessage("turn_end", json)` | INV-C |
| `OpenCodeMessageHandler.java:342-347` | `sessionId` 改**必填校验**（空 → 拒绝 + WARN） | N6 |
| `OpenCodeMessageHandler.java:563-586,387-401` | `handleContentDelta`/`handleThinkingDelta`/`handleSessionTitle` 增加 session 过滤（对齐 `:455-460` 的既有做法） | 防线一致化 |
| `PermissionRequest.java:12-18` | **新增 `sessionId` 字段**，构造必填 | 根修弹窗归属 |
| `PermissionHandler.java:267-272` | 删除回落到 `context.getSession()` | N6 |
| `PermissionHandler.java:451-457` | 删除三级回落；`payload.sessionId` 必填 | N6 |
| `PermissionHandler.java:258-307` | 权限路径纳入统一 `pendingPrompts` 注册表 + `scheduleSafetyNet`（对齐 `:207-249` 的做法） | 弹窗必现 |
| `PermissionHandler.java:379-424` | `clearPendingRequests` 改**按 sessionId 清**，禁止整表 `clear()` | 多会话可用 |
| `OpencodePermissionRegistry.java:36,41-53` | 去进程级 `static`；改实例级 + LRU（**先结算再淘汰**）；删 `per_` 前缀硬编码 | 成批丢请求 |
| `SessionHandler.java:542-559` | `targetSession == null` → WARN + 回 echo `onTurnEnd`（不静默） | 症状③ |
| `SessionHandler.java:198-212,435-450` | send 路径的 `context.getSession()` 兜底**删除**，改必填校验 | N6 |
| `HandlerContext.java:105-119` | `sessionPool` 双键注册（`tabId` + `sessionId`），`sessionLookup` 接上 | 查表不再 miss |
| `OpencodeBuddyChatWindow.java:2855-2868` | 删 `permissionServiceKey` 兜底分支 | N2 |
| `WebviewEventQueue.java:172-195` | `mergeWithTailDelta` 的会话键改读**信封 session**（而非 `args[0]`）；队列按会话分区（§6.2） | N7 + 性能 |
| `WebviewEventQueue.java:245-269` | `takeBatch` 去掉循环内 `containsMessageSnapshot(batch)`（O(n²) → O(n)） | §6.2 |
| `StreamMessageCoalescer.java:469-500` | `selectMessageTransport` 的 `hasSamePrefix` 用增量游标替代全量比较 | §6.2 |
| `MessageMerger.java:21-63` | `mergeAssistantMessage` 的 `deepCopy` 改为**只拷贝 content 数组中被改动的 block** | §6.2 |

### 4.3 webview

| 文件:行 | 改动 | 理由 |
|---|---|---|
| **新增** `state/sessionRuntimes.ts` | `Map<sessionId, SessionRuntime>` + 细粒度订阅（`useSyncExternalStore`）；含 `createRuntime` / `disposeRuntime` | INV-D 单一真源 |
| `MessagesContext.tsx:47-57` | 全局单槽 `messages/status/loading/loadingStartTime/isThinking/streamingActive/sessionLoading/sseTodos/isCompacting/compactingStartTime` → 全量搬入 `runtimes` | INV-D |
| `streamingCallbacks.ts:459-461,500-501` | 删双参兼容与回落；`targetSessionId` 缺失 → `orphanCount++` + `return` | INV-A |
| `streamingCallbacks.ts:452-457` | `ensureStreamingActive(turnId)`：已 settled 的 turnId 不再点亮 | INV-C |
| `streamingCallbacks.ts:546-590` | `onStreamEnd` 删 `__sessionTransitioning` 早退（`:592`）；删激活态分流，改为**总写目标会话** | INV-C |
| `streamingCallbacks.ts:609` | 幂等键从 `streamingTurnIdRef` 改为 `(sessionId, turnId)` | INV-B |
| **新增** `onTurnEnd(sessionId, payload)` | 会话级终态入口，幂等落 `settled(turnId, reason)` | INV-C |
| `streamingCallbacks.ts:997-1000,1033-1038` | `onBlockReset`/`onStreamingHeartbeat` 删"非激活即丢" | 非激活也需要 |
| `messageCallbacks.ts:174,433,560` | 删 `\|\| currentSessionIdRef.current` 回落 | INV-A |
| `messageCallbacks.ts:793-796` | **删除** loading 抑制守卫 | INV-C |
| `messageCallbacks.ts:506-544,621-655,748-759,831-857,941-956,1061-1085` | 统一改为"按 `sessionId` 定位 runtime 写"；非激活写 runtime（不触发视图） | INV-D |
| `messageCallbacks.ts:1024,1238` | `addHistoryMessage`/`addUserMessage` 增加必填 `sessionId` 参数 | INV-A |
| `permissionCallbacks.ts:59-79,130-154,179-182` | `sessionId` 改读 `request.sessionId`；非激活/无 tab → `pendingPrompts` / orphan 队列（**永不丢弃**）；`showPlanApprovalDialog` 补归属 | 症状② |
| `useSessionTabs.ts:309-324` | 删"活动 tab 未绑定即认领"兜底（`:317-322`） | 该兜底是补丁新增的反向推断 |
| `useSessionTabs.ts:326-340` | `updateBackgroundTab` 改为 **Map 直取 O(1)**；谓词不命中 → orphan + 计数 | 性能 + N6 |
| `useSessionTabs.ts:411-474` | **删除** `saveCurrentTabSnapshot` / `selectTab` 的全局↔快照搬运 | INV-D |
| `useSessionManagement.ts:116-142,186-195` | **删除** `beginSessionTransition` 整屏清空 + 15s 超时锁 | §3.3 |
| `sequenceBarrier.ts:31,37-40` | 删全局兜底；空 `sessionId` → 非前台 | INV-A |
| `useMessageSender.ts:443-476` | `interruptSession(sessionId)`：推进 `settled(turnId,'aborted')`；**不再** `ensureStreamingActive` 反置 | §3.4 |
| `useDialogManagement.ts:65-82` | 单模态 + 全局队列 → per-session 队列；`__dialogStore` 删除 | 多会话可用 |

---

## 5. 删除清单（重构 = 减法，这部分和新增同等重要）

| 删什么 | 位置 | 为什么可以删 |
|---|---|---|
| 传输层单槽 | `core/daemon.js:64,69,438` | 身份由信封携带 |
| `loading=false` 抑制守卫 | `SessionCallbackAdapter.java:140-145`、`messageCallbacks.ts:793-796` | 终态幂等后无需规避 |
| `__sessionTransitioning` 对终态的早退 | `streamingCallbacks.ts:592` | 切换不再是共享状态操作 |
| 空 id 回落（共 20 处） | §4.3 各处 | INV-A |
| 全局↔快照搬运 | `useSessionTabs.ts:411-474` | INV-D |
| 单模态弹窗 + 全局队列 | `useDialogManagement.ts:65-82` | per-session 队列 |
| `getTargetSessionId()` | `SessionCallbackAdapter.java:102-104` | 身份从信封/请求对象来 |
| 5 参构造 | `SessionCallbackAdapter.java:48-56` | 强制作身份出生 |
| `isActiveSession` 的 `permissionServiceKey` 兜底 | `OpencodeBuddyChatWindow.java:2867` | N2 |
| 新建会话"清空全部 pending" | `PermissionHandler.java:379-424`、`SessionLifecycleManager.java:174-201` | 改按会话清 |
| `OpencodePermissionRegistry` 的 `PENDING.clear()` | `OpencodePermissionRegistry.java:50-52` | 改"先结算再淘汰" |
| 激活态分流（终态） | `streamingCallbacks.ts:559`、`messageCallbacks.ts` 各处 | INV-C |

**删除量预估**：这三行守卫 + 20 处回落 + 快照搬运的删除，会**净减少**代码（新增集中在 `sessionRuntimes.ts`
与 orphan 队列，约 300 行；删除约 400+ 行）。

---

## 6. 性能与资源

### 6.1 四条热路径的复杂度预算

| 热路径 | 频率 | 现状 | 目标 | 说明 |
|---|---|---|---|---|
| ① core：每条 SSE delta → 一行 NDJSON | ~100/s 峰值 | O(len) `JSON.stringify` + `text.split('\n')` | O(len)，**去掉无谓 split** | 信封加 `session` 约 +20B/行，可忽略 |
| ② 宿主：per-line demux | 同上 | `sessionAdapters` 已存在 → O(1) | O(1) | 目标态不新增开销 |
| ③ 宿主→webview 推送 | **150ms 节流**（`STREAMING_MIN_INTERVAL_MS`） | 全量 `updateMessages` 或 tail | tail 恒定 + prefix 比较 O(1) | 见 §6.2 |
| ④ webview 非激活会话 delta | 同上 × 会话数 | **O(tabs)** `prev.map` + 新数组 | **O(1)** Map 直取 | 见 §6.2 |

### 6.2 必须修的三个性能点（当前实测于代码，不是猜测）

**(1) `WebviewEventQueue.takeBatch` 是 O(n²)** — `WebviewEventQueue.java:245-269`

```java
while (iterator.hasNext()) {
    ...
    if (!batch.isEmpty() && containsMessageSnapshot(batch) && isSnapshotResetBoundary(call)) break;  // ← 每轮重扫 batch
```

`containsMessageSnapshot` 对已累积 batch 做 `stream().anyMatch`，循环内每加一条就重扫一次，
n ≤ `MAX_PENDING_EVENTS = 256` → 最坏 ~32k 次谓词调用**每次 drain**。
**修法**：循环外维护 `boolean batchHasSnapshot`，遇 snapshot 时置位，O(n²) → O(n)。

**(2) 非激活会话的状态写入是 O(tabs) 且触发全量 re-render** — `useSessionTabs.ts:326-340`

```ts
setTabs((prev) => prev.map((tab) => { if (predicate(tab)) {...} return tab; }));
```

多会话并行流式时，每个后台 delta 都会：建新数组 + 遍历全部 tab + 触发 `tabs` state 变化 →
**所有 tab 组件 re-render**。会话数 × 帧率 的放大。
**修法**：`Map<sessionId, SessionRuntime>` 直接命中 O(1)；视图侧用 `useSyncExternalStore`
按 `sessionId` 订阅，非激活会话的写入**不触发**活动视图渲染。

**(3) 长会话每帧全量序列化** — `StreamMessageCoalescer.java:440,469-500`

`:440` 走全量 `updateMessages`（整个消息数组 JSON）；`:469-500` 的 tail transport 是缓解手段，
但 `hasSamePrefix(previousMessages, messages, baseIndex)` 每次 push 都要比较前缀 → O(n)；
且 `MessageMerger.mergeAssistantMessage`（`:30`）每次 `existingRaw.deepCopy()` **深拷贝整条消息**，
content 数组内每个 block 再 `deepCopy()`（`MessageMerger.java:87`），
`findMatchingToolUseIndex`（`:175`）为 O(content) 线性扫描。
**修法**：
- prefix 比较改为**增量游标**（记录上次已确认相等的长度，只比较新增部分）→ O(新增)；
- `mergeAssistantMessage` 只拷贝被替换的 block，不做整条 deepCopy；
- `findMatchingToolUseIndex` 走已有 `indexByKey`（`:73`）建立的索引，命中即 O(1)。

> 这些不是重构引入的，但**重构必须顺手修掉**——否则"无性能问题"这条不成立，
> 且 per-session 化之后这些开销会乘以会话数。

### 6.3 内存生命周期（防泄漏，这是 per-session 化的**新**风险）

| 资源 | 创建 | **必须销毁于** |
|---|---|---|
| webview `runtimes: Map<sessionId, Runtime>` | 首次收到该 sessionId 的事件 | tab 关闭 / 会话删除 → `disposeRuntime(sessionId)` |
| webview orphan 队列 | 会话未绑定 tab 时 | 被认领即移除；**上限 64 条 + TTL 10min**，超限淘汰最旧并 WARN |
| 宿主 `sessionAdapters` / `sessions` | `registerSession` | **新增 `unregisterSession`**（现只 put 不 remove → 长时使用泄漏） |
| 宿主 future 注册表 | 请求发起 | 结算（含 safety-net 超时）→ 移除；实例级 + LRU |
| core `_activeTurns` | 回合开始 | `_settleTurn` / `:1202,1300` 已 delete ✅ |
| core `_sessions` | `trackSession` | 已有 `MAX_TRACKED_SESSIONS = 1000` LRU ✅ |
| core `_timedOutTurns` | 超时登记 | `TIMED_OUT_TURN_TTL_MS = 30min` ✅ |

**验收**：打开 20 个会话各跑一轮 → 关闭 19 个 → 堆内存应回落（`disposeRuntime` 生效）；
`sessionAdapters.size()` 应等于当前打开的会话数。

---

## 7. 测试策略

### 7.1 core（`opencode-buddy-core`）

```js
// daemon-envelope.test.js
// 1) 有 turn：          {id:'7', session:'ses_a', turn:1042, seq:1047}
// 2) 无 turn 但有 SSE session：{id:null, session:'ses_a', turn:null, seq:1047}
// 3) 完全无归属：        {id:null, session:null, ...}  ← 不得贴到任何 requestId
// 4) _handleEvent 注入无 turn 的 message.part.delta：
//    产出的行 session 必须等于 SSE 的 sessionID，且【不得】等于任何 requestId
// 5) abortCurrentTurn：同步产出一次 [TURN_END]{reason:'aborted'}；重复调用仅一次
// 6) 单行快速路径：无 '\n' 的 chunk 不分配 split 数组（性能回归）
```

### 7.2 宿主

```java
// DaemonBridgeTest：{session:'ses_x'} → 投给 ses_x 的 adapter，不投任何 RequestHandler
// DaemonBridgeTest：{session:null} 的会话级行 → orphanCount++，不投任何 adapter
// SessionCallbackAdapterTest：构造无 sessionId → 抛错
// PermissionRequestTest：无 sessionId 构造 → 抛错
// OpencodePermissionRegistryTest：超过上限时【未结算】的请求不被淘汰
// WebviewEventQueueTest：takeBatch 在 256 条含 snapshot 时谓词调用次数为 O(n)（回归 O(n²)）
```

### 7.3 webview

```ts
// onContentDelta 缺 sessionId → 不写入任何 runtime，orphanCount === 1
// running(ses_x, turn=1042) + delta(turn=1042) 接受 → interrupt → settled(1042)
//   → 后续 delta(turn=1042) 丢弃，且 runtime.streamingActive === false
// onTurnEnd 到达时该会话非激活 → **仍然**清零该 runtime（INV-C）
// disposeRuntime 后 runtimes 不含该 sessionId
// updateBackgroundTab 谓词不命中 → orphan 队列，非 no-op
```

### 7.4 端到端（三症状）

| 症状 | 步骤 | 期望 |
|---|---|---|
| 串会话 | 3 个会话同时发送 | 每会话只收到自己的消息；`orphanCount === 0` |
| 弹窗丢失 | 非激活会话触发权限 | 切回该 tab 必见弹窗；不出现无弹窗的"已取消" |
| loading 不清 | 点停止 / 停止后立即切 tab | 1s 内 tab 转圈消失 |

---

## 8. 提交切分（按层，不按兼容阶段）

| # | 提交 | 可独立验收 |
|---|---|---|
| 1 | **core：信封带身份** | `daemon-envelope.test.js` 全绿；单槽被删 |
| 2 | **宿主：按 session demux + `PermissionRequest.sessionId`** | 宿主单测全绿 |
| 3 | **webview：`sessionRuntimes` + 禁回落 + 删快照搬运** | `tsc` + vitest 全绿 |
| 4 | **core+宿主+webview：`[TURN_END]` + 会话状态机** | 停止后 1s 灭 |
| 5 | **弹窗：per-session 队列 + orphan + 注册表** | 弹窗必现 |
| 6 | **性能三修** | 基准不退化；`WebviewEventQueueTest` O(n) 断言 |

> 提交 1–3 必须**同批**发布（信封/路由/消费三侧同时切）；4–6 可任意顺序。

---

## 9. 风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 信封/路由/消费不同步 | 三侧是同一协议的两端 | 提交 1–3 强制同批（§8）；协议测试先行 |
| core 改动波及两个插件 | vscode 侧 `node_modules/opencode-buddy-core` 是 symlink 直生效；idea 侧需 gradle repack | 协议测试放 core 仓；两插件各自跑一次端到端 |
| per-session 化引入泄漏 | §6.3 是新风险 | 必须有 `disposeRuntime` / `unregisterSession` 的对称测试 |
| 删除守卫后暴露旧 bug | 删守卫是"负向改动"，风险最高 | 每个删除点都必须有替代机制（turnId 幂等 / orphan 队列 / dispose）——§5 已逐条对应 |
| `durable.seq` 服务端行为变化 | v1/v2 差异 | `seq` 缺失时降级为 `null`，幂等键退化为 `(sessionId, 0)`，不影响正确性 |

---

## 10. 与既有文档的关系

- `docs/multi-session-isolation-architecture.md` —— 诊断与证据链（本文的论证来源）。
- `docs/multi-session-isolation-remediation-plan.md` —— **已废弃**（迁移视角，含兼容开关）。
- `docs/message-conversation-and-streaming-lifecycle.md` —— 流式生命周期（本文 §6.2 修正其性能假设）。
- `docs/permission-and-question-flow.md` —— 权限/提问链路（本文 §3.2 替换其归属模型）。
- `docs/undo-redo-and-fork-flow.md` —— revert/fork（依赖按 id 投递，需同步回归）。
- `docs/opencode-v2-adaptation-guide.md` —— v1/v2 差异（`durable.seq` 相关）。
