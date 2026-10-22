# 多会话全量重构进度

分支：`refactor/full-cutover`

## 已完成

### 宿主身份与状态

- 新增 `com.opencodebuddy.tab`：
  - `TabId`
  - `SessionId`
  - `TabState`
  - `TabSettings`
  - `TabRecord`
  - `TabRegistry`
- 删除宿主单槽：
  - `session`
  - `activeSessionId`
  - `sessionId`
- 删除旧索引：
  - `sessions`
  - `sessionAdapters`
  - `sessionCoalescers`
- active session、adapter、coalescer 全部从 `TabRegistry` 派生。
- 初始会话绑定到 bootstrap `TabRecord`。
- 关闭 tab、dispose、历史加载、session 更新均按 tab/session 路由。
- 历史加载支持 `tabId` 贯穿 `load_session` 链路。

### 事件与权限

- 删除文件 IPC 权限体系及相关类。
- `PermissionHandler` 改为 session 级 prompt 清理。
- permission/question/plan prompt close 会结束对应 future。
- 新增统一入站分发 `window.__opencodeDispatch`。
- Java `WebviewEventQueue` 优先通过 dispatcher 调用 webview。
- `tab_activate` 支持稳定 `tabId`。

### Webview 多 tab 状态

- 新增 `TabStore`。
- 新增 `TabRuntimeStore`。
- 新增 `TabDraftStore`。
- streaming refs 改为 per-tab runtime。
- rAF 回调增加 tab 身份校验，避免跨 tab 渲染。
- 状态面板任务/子代理/编辑按 tab 隔离。
- `claimUnboundTab`、`switch_session`、`create_new_session` 前端路径删除。
- tab id 改为 UUID/随机稳定 id，避免时间戳碰撞。

### 会话执行与命令

- 新增 `TabSessionActor`：per-tab 串行执行器；关闭后拒绝排队中/新任务，
  由 `TabRecord` 拥有并随 tab 一起关闭。
- 新增 `TabSessionFactory`：统一 `prepare`（新 tab）与 `attach`（历史会话），
  不再在窗口里手写 session/adapter/coalescer 组装。
- 新增 `TabCommandRouter` + `TabCommand`：`tab.create` 在路由线程内联执行，
  其余命令投递到目标 tab 的 actor；`requestId` 经 2048 项 LRU 去重，
  重复的 reload/重放命令不会执行第二次。
- `handleJavaScriptMessageLocked` 识别 `tab_command`，未知 tab 不再回落到
  当前活动 tab。

### 调度与 typed envelope

- 新增 `BridgeScheduler`：按 tab 分 lane 轮询，活动 lane 权重 3、后台 1，
  每批后轮转起点避免超大事件饿死后续 lane；每 lane 上限 256 个事件，
  每批最多 64 个事件 / 256k 字符。
- delta 在 lane 尾部合并，`updateStatus` 等 latest-only 事件只保留最新；
  `updateMessages` 作为快照边界，同一 lane 的旧快照被替换。
- 队列满时优先丢 delta 并标记 `snapshotRequired`，终态事件不丢；
  监听器重放 `session.getMessages()` 后清除标记。
- `WebviewEventQueue` 改为 `BridgeScheduler` 的薄子类，保留原有 API 与测试。
- 新协议 `{v:2, type, tabId?, epoch?, payload:{args}}`；无 raw script 的批次
  一次调用 `window.__opencodeDispatchBatch`，raw script 仍按调用顺序逐条执行。
- webview `hostDispatcher` 接受 envelope 批次，并按 `tabId` 拒绝低于已见
  epoch 的事件；旧 `restoreSessionTabs` 路径删除。

### Prompt 与 tab 状态

- 新增 `PromptBroker`，拥有 permission / question / plan 的 future 与
  session 关联表；`PermissionHandler` 保留 dialog 显示、回复解析和跨表清理编排。
- 新增 `TabStatusStore`、`TabPromptStore`：切 tab、close、reload resync 时
  按稳定 `tabId` 保存/恢复/清理状态，后台 tab 的更新不会覆盖前台快照。
- 新增 `onTabResync`：payload 携带稳定 `tabId`、`activeTabId`、每 tab 的
  `epoch`，替换旧的按 sessionId 重建 tab 列表。
- reload/daemon 重启后的重同步按稳定 tab 恢复；迟到事件由 epoch 丢弃。

### 旧路径清理

- 删除 `TabStateService.tabSessions` 与 per-tab session 落盘/恢复。
- 删除 `TabSessionRestorePolicy`、未使用的 UUID
  `SessionLifecycleManager.createNewSession`、`__pendingSessionId`。
- `DaemonBridge.deliverDataLine` 只在数据行没有 session 时才回落到 request
  handler；命名了 session 的迟到行按该 session 缓冲，避免跨会话串流。

## 验证

在 `D:\source\repos\opencode-idea-gui` 执行：

- `.\gradlew.bat test -x buildWebview`：793 个测试通过，7 个跳过。
- `npm test -- --run`：170 个文件、1502 个测试通过。
- `npx tsc --noEmit`：通过。
- `git diff --check`：通过。
- 宿主调度基准（6 tab / 3 流 / 10k 消息）：10k delta 合并为 1 条脚本，
  10k 离散消息产生 314 条脚本、无丢弃，20k 次入队耗时 123ms。

## 未完成

- 真实 JCEF 6 tab / 3 流 / 10k 消息渲染基准，以及 §13.4 完整矩阵
  （1000 次切 tab、50 次 reload、100 次权限请求）。需要 IDE 运行时，
  当前环境无法启动 JCEF；步骤、记录表和预算见 `docs/jcef-benchmark.md`。

## 第二轮收口：协议 v2 与身份收口

对照 `docs/full-refactor-architecture-plan.md` 逐条复核后补齐的部分。

### 静态护栏（§16）

- 新增 gradle 任务 `checkLegacyPatterns`，接入 `check` 生命周期。
- 硬门禁（命中即失败）：`claimUnboundTab` / `switch_session` /
  `permissionServiceKey` / `__pendingSessionId` / `getHostedSessionIds`，
  以及 `context.getSession()` 这类"当前会话"兜底访问。
- 债务清单（当前只报告，`-PstrictLegacy=true` 时才失败）：
  `currentSessionIdRef.current`、`capture/restoreStreamingRuntime`、
  `__sessionTransitioning`。
- 例外必须写在命中行并带 `legacy-allow:` 注释。

### W→H 命令信封（§7.1）

- 新增 `handler/core/HostCommand`：统一解析
  `{v, type, requestId, tabId, sessionId?, payload}`，校验版本、类型、
  tabId、requestId，并把信封身份合并进 payload（payload 自带的身份作废）。
- `session.send` 走新信封；校验失败回 `commandRejected`
  （`tabId/requestId/code/message`），webview 侧 toast + 日志。
- 新增 `handler/core/RequestIdLedger`：命令按 requestId 幂等，重放只执行一次。
- 发送路径不再回落"当前会话"：没有 tabId 直接拒绝
  （`MISSING_TAB_ID`），sessionId 与 tab 绑定不一致时拒绝
  （`SESSION_MISMATCH`）。
- `interrupt_session` / `restart_session` 必须带 tabId；前端统一走
  `sendInterrupt()`，关闭后台 tab 时停止的是那个 tab 自己的会话。

### 回合终态幂等（INV-5）

- 新增 `tab/TurnId`（强类型，正数）。
- `TabRecord` 记录已结算回合（上限 256，LRU 淘汰），`[TURN_END]` 只结算一次，
  迟到的旧回合终态不会清掉新回合的 spinner。
- 关闭 tab 时一并释放该表。

### 快照按 tab 路由（INV-1）

- `StreamMessageCoalescer` 现在同时持有 tabId 与 sessionId，
  `updateMessages` 推送时附带 tabId。
- webview 收到带 tabId 的快照时直接写那一个 tab，不再用 sessionId 反查 tab。

### 其他

- `HandlerContext.getSession()` 拆成 `getActiveTabSession()`（窗口级）
  与 `getSession(tabId|sessionId)`（路由级）；窗口级调用点全部显式改名，
  路由级调用点全部补身份。
- fork 不再回落到"当前会话"：只 fork 调用方点名的会话，支持用 tabId 寻址。
- 新增测试：`HostCommandTest`、`RequestIdLedgerTest`、
  `SessionHandlerHostCommandTest`、`TabRecordTest`。
