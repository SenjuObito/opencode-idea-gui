# 多会话全量重构进度

分支：`refactor/full-cutover`

> 本文件是逐轮流水记录。**当前状态总表**（16 个条目、剩余估算）
> 见 `docs/refactor-status.md`。

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

## 第三轮收口：运行态归属与事件身份

### 前端运行态按 tab 归属（§6.3 / 决策基线 4）

- `useStreamingMessages` 的 per-tab runtime map 是运行态的唯一 owner；refs
  只是"活动 tab 的视图"（proxy），不再是存储。
- 删除 `utils/streamingRuntime.ts`（capture/restore/clear）与
  `store/tabRuntimeStore.ts`（快照归档）：切 tab 不再搬运运行态，tab 的运行态
  留在原处，关 tab 时随 tab 释放。
- 会话切换守卫从窗口级 flag 改为 per-tab（`store/tabTransitionStore.ts` +
  `hooks/windowCallbacks/tabTransition.ts`）：之前任何 tab 切换都会静音所有
  tab 的事件，现在只有切换中的那个 tab 被静音。

### 会话级事件携带 tabId（§7.2 / INV-1）

- `SessionCallbackAdapter` 的 tabId 可写，绑定时写入；所有会话级事件在末尾
  附带 tabId（delta / stream start+end / loading / status / title / thinking /
  summary / turn end / block reset / usage / uuid patch / task / todo / revert /
  messages removed）。
- webview 侧按 tabId 直写目标 tab，不再用 sessionId 谓词反查归属。
- `BridgeScheduler` 的 lane 解析优先使用事件自带的 tabId：未绑定会话的事件
  也能进正确的 lane，而不是落到全局 lane。

### 静态护栏升级

- `single-runtime-swap` / `global-transition-lock` 进入硬门禁；
  `-PstrictLegacy=true` 现在 0 forbidden / 0 debt 通过。

### 集成测试

- 新增 `MultiTabEventProtocolIntegrationTest`：两个 tab 同时流式，断言每个
  事件都带自己的 tab 与 session，且互不出现对方的身份。

### 权限收口到 `com.opencodebuddy.prompt`（§5.9 / §10 / §15.1）

- 新增 `com.opencodebuddy.prompt`：`PromptKind`、`PendingPrompt`、
  `PromptDecision`、`PromptBroker`（从 `handler` 包迁出并成为 public）。
- prompt 记录归属它的 tab（`permissionTabs` / `askTabs` / `planTabs`），
  `PromptBroker.rejectForTab(tabId)` 只拒绝该 tab 的 prompt，其他 tab 不受影响。
- 关闭 tab 时除了按 session 清理，还会按 tab 拒绝该 tab 的 prompt
  （`PermissionHandler.clearPendingRequestsForTab`）。
- `PermissionManager` 保留：它是**会话级**状态（权限模式 + 工具记忆），不是
  prompt 登记表，合并进全局 broker 反而会让模式变成全局的。

## 第四轮收口：语义协议、消息对账、fork、附件

### 语义事件目录（§7.3）

- 新增 `com.opencodebuddy.bridge.EventCatalog`：语义名 ↔ 旧函数名的唯一映射表。
- 事件信封改为 `{v, type(语义名), fn(旧函数名), tabId, sessionId, epoch, payload}`。
- webview 新增 `utils/semanticEvents.ts`，dispatcher 先按语义名解析，再回落旧的
  `window.<fn>` 查找（可增量迁移）。
- `EventCatalogTest` 交叉校验：Java 目录与 webview 目录必须完全一致，且
  `SessionCallbackAdapter` 推送的每个事件都必须有语义名。

### 消息按 id 对账（§6.5）

- 新增 `webview/src/store/tabMessageStore.ts`：`order` + `byId` + 流式 assistant
  标记；快照按 id 对账、tail 只替换尾部、按 id 删除。
- tab 数据带上 `messageStore`（按引用共享，tab 对象克隆时转交不丢）。
- 后台 tab 的快照经由 store 对账后写入。

### fork 落到自己的 tab（§9.8）

- 新增命令 `session.fork {sourceTabId, targetTabId, messageId?}`：宿主按源 tab 解析
  会话、调用 daemon fork、把新会话绑定到目标 tab，再回 `onForkBound`；源 tab
  完全不动。
- webview fork 前先预留目标 tab id，收到 `onForkBound` 后用该 id 开 tab。

### 附件归属 tab（§9.9）

- 新增 `com.opencodebuddy.tab.TabAttachmentStore`：附件写进 tab 自己的目录，
  跨 tab 不可见，tab 关闭即释放（窗口 dispose 时全部释放）。
- 发送路径把附件交给 tab 的 store 暂存并读回；发送结束即释放暂存字节。
- webview 发送时带上附件 id，宿主据此归属。
- 说明：daemon 协议仍然收 base64（core 侧协议不变），本轮落地的是归属与生命周期。

## 第五轮收口：视图订阅与验收工具

### 视图直接读 tab store（§6.4 / 决策基线 4）

- `TabStore` 成为 tab 集合与 transcript 的唯一 owner（模块级单例，生命周期
  等于 webview，而不是一次 React 挂载）。
- `MessagesContext` 的 `messages` 不再是独立 state：用 `useSyncExternalStore`
  订阅 store，读的是"活动 tab 的 transcript"；`setMessages` 变成写入该 tab。
- `useSessionTabs` 不再把 transcript 从活动态拷进 tab，也不再在切 tab 时把
  目标 tab 的 transcript 拷回活动态——切 tab 只换 `activeTabId`。
- 没有 tab 时（strip 还在建）transcript 暂存在 store 里，owner 仍然唯一。

### 验收工具

- `scripts/bridge-benchmark.sh`：宿主侧性能基准（6 tab / 3 流 / 10k 消息），
  打印脚本数、字节数与耗时，并附 §12.1 的预算对照。
- `docs/functional-matrix.md`：方案 §13.3 的功能矩阵，改写成"操作 / 看什么 /
  通过标准"的可执行清单（22 项）。

### 事件路由表（§6.1 的终态）

- 新增 `webview/src/utils/hostEventRegistry.ts`：语义事件 → 处理器的路由表。
- dispatcher 先查路由表，再查语义目录，最后才回落到旧的 `window.<fn>` 查找；
  路由表里登记过的处理器，window 全局只是兼容面。
- 各回调模块在注册时把处理器登记进路由表（`registerHostEvents`），
  共 29 个语义事件完成登记。

## 第四轮收口：事件具名化（§7.2 的最后一半）

- 新增 `com.opencodebuddy.tab.TabEvent` / `TabEventSink` / `TabEventBus`：
  事件的身份（tab / session / turn / seq）与内容（具名 payload）分开携带。
- 宿主全部会话级事件改为具名事件：delta、turn、loading、status、title、usage、
  todo、task、revert、message removed、prompt 请求/关闭、history 加载/清空。
- `BridgeScheduler` 的信封变成
  `{v, type(语义名), tabId, sessionId, turnId, seq, epoch, payload(具名)}`。
- webview 的处理器改为读具名 payload + 信封身份；`webview/src/utils/hostEventTypes.ts`
  提供类型与 `readJson` 兼容读取。
- 新增静态门禁 `positional-event-calls`：目录里的事件不得再以位置参数调用。

### 提示回复三重校验与计划审批队列（§10.2.6 / §10.3）

- 权限 / 提问 / 计划的回复带 tabId + sessionId + requestId，宿主校验三者与
  待答提示的归属一致；不一致的回复被拒绝并记录。
- 计划审批进入 per-tab 队列（`pendingPlans`）：后台 tab 的计划审批留在自己的
  tab 里，切回去再显示。

### daemon 重启主动收尾（§11.3）

- daemon 死亡时，宿主给每个 STREAMING tab 发
  `turn.ended {reason: daemon_restart}` 与 `session.loading {loading:false}`，
  并把 tab 的状态落回 BOUND——不再等到用户重载窗口。

### 提示回复与 daemon 收尾（§10.2.6 / §10.3 / §11.3）

- 权限 / 提问 / 计划的回复走 v2 命令（`permission.reply` / `question.reply` /
  `plan.reply`），信封带 tabId + sessionId + requestId，宿主校验三者一致。
- 计划审批进入 per-tab 队列（`pendingPlans`），后台 tab 的审批不再抢前台。
- daemon 死亡时，宿主给每个 STREAMING tab 发 `turn.ended {reason: daemon_restart}`
  与 `session.loading {loading:false}`。

### 附件按 tab 归属 + 预算回调（§5.1 / §9.9 / §12.2）

- `TabRecord` 自己拥有 `TabAttachmentStore`：另一个 tab 看不到，tab 关闭即释放。
- 每帧参数预算从 256KB 回到方案要求的 32KB。

## 未完成

- 真实 JCEF 6 tab / 3 流 / 10k 消息渲染基准，以及 §13.4 完整矩阵
  （1000 次切 tab、50 次 reload、100 次权限请求）。需要 IDE 运行时，
  当前环境无法启动 JCEF；步骤、记录表和预算见 `docs/jcef-benchmark.md`。
- 前端运行态所有权仍在 store 与"单份 ref 交换"之间：
  `useSessionTabs.ts` 切 tab 时用 `capture/restoreStreamingRuntime` 搬运运行态，
  `__sessionTransitioning` 全局锁仍在（`checkLegacyPatterns` 债务清单）。
- 其余会话级事件（delta / usage / status / title）尚未携带 tabId。
- 权限体系尚未收口到 `com.opencodebuddy.prompt`（`PromptBroker` 仍在
  `handler` 包内，`PermissionManager` 仍与它双轨）；计划审批弹窗没有
  per-tab 队列。
- fork 仍是旧路径：没有预建目标 tab、没有 `session.fork` 命令；
  附件仍是内联 base64，没有 `TabAttachmentStore` / `attachment.prepare`。
- 没有 fake daemon 集成测试，只有针对单个组件的隔离测试。

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

### 弹窗按 tab 归属（§10.1 / §10.3）

- 宿主新增 `sessionId → tabId` 反查（`OpencodeBuddyChatWindow.findTabIdForSession`，
  经 `HandlerContext.getTabIdForSession` 暴露）。
- 权限 / 提问 / 计划弹窗的 payload 现在带 `tabId`。
- webview 收到带 tabId 的弹窗时直接写那一个 tab 的待办队列，不再用
  sessionId 谓词反查。
- 计划审批弹窗目前仍然直接弹出（没有 per-tab 队列），见"未完成"。

### 其他

- `HandlerContext.getSession()` 拆成 `getActiveTabSession()`（窗口级）
  与 `getSession(tabId|sessionId)`（路由级）；窗口级调用点全部显式改名，
  路由级调用点全部补身份。
- fork 不再回落到"当前会话"：只 fork 调用方点名的会话，支持用 tabId 寻址。
- 新增测试：`HostCommandTest`、`RequestIdLedgerTest`、
  `SessionHandlerHostCommandTest`、`TabRecordTest`。

## 命令目录统一（A2 收口）

一条命令，一种走法：webview 的每个命令现在都发在同一个传输上
`host_command:{v, type, requestId, tabId, sessionId, payload}`，宿主按 `type`
路由，不再为每种命令各开一个桥接事件。

- 前端新增三个发送面：`sendHostCommand(tabId, type, payload, sessionId)`
  （tab 级）、`sendSessionCommand(type, sessionId, payload)`（会话级，自动解析
  会话所属 tab）、`sendWindowCommand(type, payload)`（窗口级，不属任何 tab）。
- 旧的 `sendTabCommand` 也改为发同一个信封（`tab.*` 命令）。
- 命令类型统一为 `域.动作`：`session.send|interrupt|restart|load|fork|revert|
  unrevert|compact`、`settings.mode|model|reasoning`、`context.refresh`、
  `commands.list|read|save|delete`、`tab.create|activate|close`。
  旧的 `send_message`、`interrupt_session`、`set_mode`、`commands_list` 等
  在宿主侧直接删除，不再保留双份入口。
- `HostCommand.parse(content, expectedType, tabIdRequired)`：命令必须点名 tab 或
  会话；窗口级命令显式声明不点名，宿主不会替它猜。
- 身份只有一个来源：payload 不再重复 sessionId，宿主把信封身份合并进 payload
  后再交给 handler，因此"信封说的"和"payload 说的"不可能打架。
- `session.send` 的两种形态（纯文本 / 带附件）合并为一条命令，
  `hasAttachments()` 精确判断，不再靠字符串包含猜测。
- `session.fork` 只保留 v2 语义（`sourceTabId` + `targetTabId`）：fork 永不改动
  源 tab，落点 tab 由 `bindForkedSession` 绑定。
- 新增 `HandlerContext.getSessionForTab(tabId)` / `setTabSessionLookup`：按 tab
  解析走 tab registry，避免命中"某个 tab 已经切走"的旧会话。
- 宿主对未知类型回 `command.rejected`（`UNKNOWN_TYPE`），并忘记该 requestId，
  允许前端修正后重试。

### tab.* 命令进路由表

- `host_command` 里的 `tab.*` 现在直接交给 `TabCommandRouter`：解析 tab、
  按 requestId 幂等、在该 tab 的 actor 上串行执行，失败的会回
  `command.rejected`（`UNKNOWN_TAB` / `CLOSED` / `UNKNOWN_COMMAND`）。
- 删除旧的 `tab_command` 传输与其解析器：一个传输，一条路径。
- `tab.title.set` 写的是**用户标题**（`TabRecord.setUserTitle`），之后不再被
  自动标题覆盖；webview 重命名 tab 时同步下发。
- `update_title` 改名为 `session.rename`，走会话级命令
  （`{customTitle}` 在 payload，sessionId 在信封）。
- 删除 `webview/src/types/protocol.ts` 与 `sendTypedBridgeEvent`：那份清单还在
  描述已删除的 `send_message` / `set_model` / `update_title`。

### 附件分块上传（D10）

- 大附件不再塞进 `session.send` 的一帧（32KB 预算下单帧会被整块穿过桥）。
  改为 `attachment.prepare → attachment.chunk × N → attachment.complete`，
  每块 ≤24KB base64（4 字符对齐，可独立解码）。
- `TabAttachmentStore` 从"一次性写盘"扩展成"可续传"：
  `prepare / append(offset) / complete / abort`；未完成的文件**读不出来**
  （`readData` 只交付 complete 的），声明大小与实际大小不符则不可完成。
- 偏移量严格校验：乱序/丢帧会被拒绝并 abort 整个上传，不产生静默损坏的文件。
- 发送路径只带 `{attachmentId, fileName, mediaType}`；宿主把该 tab 目录里的
  字节读回来交给 core，语义与之前完全一致（core 无需改动，v1/v2 都走这条）。
- 缺失的附件不再被静默丢弃：整条 `session.send` 以 `ATTACHMENT_MISSING` 拒绝，
  并回 `command.rejected`。
- 失败只回一个事件 `attachment.failed`（`onAttachmentFailed` → toast），
  成功路径不需要事件：命令本就是有序的。

## 第四轮：新会话引导与命名事件收口

对应提交：宿主 `99bd04c`，core `bb91c57`。

### 新会话引导（C7 / §8.2.1）
- core 在 `bindSession` 之前播报 `[SESSION_ID]`：该行只有请求 id、没有 session；
  后续行才由 session 归属。send/shell 共用 v1/v2 的同一 daemon 路径。
- 新增宿主 `tab/PendingSend`：按 `requestId` 保存新会话绑定前的内容行，
  `[SESSION_ID]` 到达后交给发起请求以绑定 tab，sink 注册后按线序回放；
  30s 未拿到 id 则失败该请求，不再让首轮永远挂起。
- `DaemonBridge` 增加 `registerPendingSend` / `unregisterPendingSend`；
  `OpenCodeSDKBridge.sendMessage` / `shell` 全部注册 PendingSend；
  删除 `[SESSION_ID]` 的临时 id 分支，历史/查询等 request-scoped 回包仍按 id 路由。
- 新增 `TabSnapshot` + `TabRegistry.snapshots()`，reload 的 `tab.resync`
  由不可变快照生成，不再遍历可变 `TabRecord`。
- 回归：`PendingSendTest`、`OpenCodeListMessagesRoutingTest` 新增无 session 戳
  引导行 + 绑定前内容回放场景。

### 命名事件遗留（A1）
- `UsagePushService` 不再调用 `window.onUsageUpdate`：改发 `usage.updated`，
  带目标 session 的 tabId；模型切换按目标 session 更新，不再使用 active tab 的用量。
- `PermissionHandler` 的问答完成改发 `question.answered`，webview 同时接受对象与旧 JSON 字符串。
- `TabEventBus` 接入窗口 `publishTabEvent`，不再是有类无调用。

### 命令按 tab 寻址（D12）
- 新增 `commands.refresh`（保留 `refresh_slash_commands` 兼容入口）；
  webview 优先以 active tabId 发送，宿主按该 tab 的 session cwd 刷新命令列表。
- `WindowEventHandler.Callback.onRefreshSlashCommands` 增加 tabId 参数；
  `SessionLifecycleManager.fetchSlashCommandsOnStartup(tabId)` 按 tab 解析 session，
  无绑定回退 active/project cwd。

### 仍未收口
- A2：仍有约 70 处 `sendBridgeEvent`（含窗口/设置/历史命令），双传输未全删。
- A3：约 96 个 `window.*` 全局仍由 dispatcher 回退调用。
- C7：`SessionStreamRouter` 仍沿用 session 包名；`TabStreamRouter` 未改名/迁包
  （已在「第四轮补 4」收口）。
- C8 / C9 / E14 / E15 / E16 未开始或仅部分；§13.3/§13.4 仍需真机执行。

## 第四轮补充：把会话/窗口命令继续搬进信封

- `SessionStreamRouter` 改名为 `TabStreamRouter` 并迁入 `com.opencodebuddy.tab`，
  与 `TabSnapshot` / `PendingSend` 同包；`OpencodeSession` 持有它，行为不变。
- 新增多 tab 关闭场景回归：一个 tab 还在流式时关闭它，另一个 tab 的事件仍继续，
  关闭 tab 不再发布任何事件（`MultiTabEventProtocolIntegrationTest`）。
- 历史列表刷新改 `history.refresh`；分享/取消分享改 `session.share` /
  `session.unshare`；撤销的 latest 回退改 `session.revert` 信封，修掉原先
  发送 `revert_session` 但宿主未注册该类型的断链。
- 设置/代理/标题继续语义化：`settings.pinnedModels[.get]`、`agents.list`、
  `tab.title.set`；webview 的 `get_agents`、`get/set_pinned_models`、
  `update_tab_title` 调用点已迁移，旧宿主类型保留兼容入口。
- 数字更新：webview 非测试/非传输的 `sendBridgeEvent` 从约 70 处降到 46 处。

## 第四轮补充 2：设置/模型/主题命令继续收口

- 新增 models.list、settings.provider、settings.uiPreferences[.get]、settings.theme.get、settings.mode.get；对应 webview 调用点已迁移，宿主保留旧类型兼容。
- get_cli_models、set_provider、get/set_ui_preferences、get_ide_theme、get_mode 的调用不再走裸 sendBridgeEvent。
- 数字更新：webview 非测试/非传输的 sendBridgeEvent 从 46 处降到 36 处。

## 第四轮补充 3：历史、剪贴板、诊断与生命周期事件

- 历史命令语义化：`history.delete`、`history.deleteMany`、`history.export`、
  `history.favorite`、`history.deepSearch`；宿主新增 JSON 字段提取，旧裸
  id/数组仍可解析。
- 剪贴板与上下文：`clipboard.write`、`context.usage`；`get_context_usage`
  的 /context 调用改为信封。
- 设置：`settings.sendShortcut`、`settings.autoOpenFileEnabled`、
  `settings.font.editor.get` / `.ui.get` / `.code.get`、`filesystem.linkify.get`。
- 诊断：`diagnostics.nodeProcesses.list` / `.kill` / `.killOrphans` / `.restart`。
- 生命周期事件也走 `host_command`：`app.frontendReady`、`app.heartbeat`、
  `tab.loading.changed`、`tab.status.changed`、`history.domCommitted`、
  `surface.damage.applied`；`parseHistoryCommitEpoch` 同时接受裸数字与
  `{epoch}`。
- 删除无宿主的 `set_thinking_enabled` 发送；`sendBridgeEvent` 只剩 8 处，
  全部是宿主未实现的旧功能（子会话历史、CLI 转换、自定义模型定价、早期游标）。
- 新增 `HistoryHandlerTest` / `ClipboardHandlerTest.extractText` 覆盖信封与旧值
  两条路径。

## 第四轮补 4：TabStreamRouter 成为每 tab 输出唯一持有者

- `TabStreamRouter` 新增 `attachOutputs` / `getAdapter` / `getCoalescer` /
  `clearOutputs`：adapter 与 coalescer 成对挂载、成对释放，不再由
  `TabRecord` 和 session 各存一份引用。
- `TabRecord` 删除 adapter / coalescer 字段，`getAdapter` / `getCoalescer`
  委托给当前 session 的 router；新增 `getRouter()` 作为数据面入口。
- 换 session 时先释放旧 session 的 router 输出，避免历史切换后旧 adapter
  继续持有该 tab 的事件出口。
- 关闭 tab 仍在 `record.close()` 前捕获 adapter/coalescer 并 dispose，
  释放语义不变。
- 验证：gradle 852 测试、tsc、vitest 173 文件 / 1516 测试、
  `checkLegacyPatterns` 0 forbidden / 0 debt 全绿。

## 第四轮补 5：命令管理按 tab cwd 读取并缓存

- `commands.list/read/save/delete` 的项目作用域不再固定用 project base。
  宿主从信封的 `tabId` 解析该 tab 当前 session 的 cwd，子目录 tab 看到的是
  自己的 `.opencode/command`，没有 tab 时才回退 project root。
- 列表按 `scope + cwd` 缓存 2 秒；保存/删除后立即失效，避免每次打开设置
  都重读并解析全部 Markdown。
- webview 的 PromptSection 有活动 tab 时发 `sendHostCommand(tabId, ...)`，
  无 tab 才退回窗口级命令。
- 新增 `CommandsHandlerTest` 覆盖「从 tab cwd 读取项目命令」。

## 第四轮补 6：删除本地 PermissionManager 回退

- `PermissionManager` 只服务旧的 Claude 风格本地权限请求：`createRequest`
  已无调用方，前端模式也不再由它执行；opencode 权限一律由服务端持有，
  经 `OpencodePermissionRegistry` 回复。
- 删除 `PermissionManager` 与 `OpencodeSession` 中的字段、回调和
  `createPermissionRequest`；`setPermissionMode` 只更新会话状态。
- 对未知权限 id 的迟到决策改为记录并丢弃，不再回退到本地 future。
- `PermissionRequest` 本身保留，它是权限弹窗与回调的传输对象。

## 第四轮补 7：桥接指标可观测

- `BridgeScheduler` 增加只读计数：入队、实际下发、delta 合并、丢弃、成帧数、
  最后一帧/最大帧字符数、reload 次数；`metrics()` 同时给出当前队列深度和
  活跃 lane 数。计数只用于观测，不参与路由。
- 丢弃点统一计数：lane 满时丢弃当前事件，或为腾位置淘汰可丢弃/可合并事件。
- `TabRecord` 在离开 `STREAMING` 时记录上一回合时长，关闭 tab 也结算。
- 关闭 tab 和销毁窗口时写一条 `[metrics]` 日志，带 `tabId` / `sessionId` /
  `turnMs` 和队列快照；日志不再只指向“当前会话”。
- 新增 `BridgeSchedulerMetricsTest` 覆盖队列深度、下发成帧、delta 合并和丢弃计数。

## 第四轮补 8：事件出口归 TabRecord 所有

- `TabRecord` 新增 `TabEventSink` 字段与 `getEventSink()`，默认 sink 为 no-op，
  测试可直接注入收集器。
- `TabRegistry.setEventSink` 在窗口构造时注入；之后创建的每个 tab 都带自己的
  事件出口，不再由 adapter 直接抓窗口方法。
- `createCallbackAdapter` 优先使用所属 tab 的 sink；只有纯 session 的旧路径
  才回退窗口 sink。
- 新增 `TabRecordTest.eventSinkBelongsToTheRecord`。

## 第四轮补 9：删除宿主从未实现的三个旧入口

- `set_earlier_cursor`：分页裁剪只影响 webview 本地窗口，宿主下一页仍按
  `pageStart` 回源，不需要回写游标；删除无效发送与注释。
- `set_custom_model_pricing`：删除 `usePluginModels` 向 Java 同步自定义模型
  定价的分支；opencode 的定价来自 provider/model 元数据。相关测试改为只验证
  本地保存与校验。
- `convert_to_cli_session`：opencode 会话本身就是 CLI 会话，宿主没有转换
  服务；删除历史列表按钮、确认弹窗、入口点判断、`onConversionResult`
  回调、CSS 与对应 i18n 文案。
- A2 目前只剩 5 处 `load_subagent_session`，需要 core 暴露子会话定位/读取
  接口后才能迁入 `host_command`。

## 第四轮补 10：确认渲染窗口化与缓存已落地

- 复核 E14 的四个点：宿主历史分页与 webview 分页上限、`MessageList` 按轮
  折叠只渲染可见窗口、`MarkdownBlock` / `MessageItem` 的 `useMemo` 解析缓存、
  `reconcileMessageKeys` 的消息 key 复用。
- 未新增代码，状态表从“未开始”改为“已完成”，避免重复实现。

## 第四轮补 11：固化 TabRecord 的 prompts / draft 归属

- 不在 `TabRecord` 上再放一份 prompt 列表：`PromptBroker` 已按 tabId 持有
  所有权限/提问/计划 future，关闭 tab 时按 tab 精确拒绝。重复字段会让两处
  状态分叉。
- 可编辑草稿属于 webview 的 `TabDraftStore`；宿主只负责发送串行，现有
  `PendingSend` 已承担。`TabRecord` javadoc 明确这两点，C8 收口。

## 第四轮补 12：清掉 `__sessionTransitioning` 测试残留

- 运行时守卫早已迁到 `tabTransition` 的 per-tab store；`__sessionTransitioning`
  只在测试里赋值，不参与任何判断。
- 删除四处测试赋值，测试名与注释改为 tab 级 transition，避免继续误导。


## 第四轮补 13：子代理历史迁入信封

- webview 的 5 处 `load_subagent_session` 全部改为 `sendSessionCommand('subagent.history', ...)`；
  命令由宿主按当前 session 解析所属 tab，不再走裸事件。
- 新增 `SubagentHistoryService`：读取父会话 transcript，按 `toolUseId` 找到
  `subagent` / `task` 工具块，从 `state.metadata.sessionID`（旧构建回退解析
  `<subagent sessionID="...">`）拿到子会话 id；仍在启动中的子代理用
  `parentID` 查询子会话列表并按 description/title 匹配。
- 子会话 transcript 转成 `SubagentHistoryResponse.messages`（thinking / text /
  tool_use），状态优先取工具块状态，轮询只读尾部 200 条，展开时读全量。
- 新增 `subagent.history` 语义事件，宿主与 webview 事件表同步；结果按
  `sessionId` 路由到所属 tab，后台 tab 不会污染当前 tab。
- Core 仅补 `listSessions(..., parentID)` 与 `listMessages(..., {limit})` 两个薄封装，
  不承担子代理业务判断。
- 新增 `SubagentHistoryServiceTest`，覆盖元数据定位、文本回退、子会话匹配、
  thinking/tool 转换；vitest 增加命名 payload 路由测试。
- 验证：gradle 全绿、tsc 通过、vitest `173 文件 / 1512 测试`、
  `checkLegacyPatterns` 0 forbidden / 0 debt。
- 提交：宿主 `e4643df`，Core `8d882cc`。

## 第四轮补 14：A3、C9、E15 架构全量无折中收口

### A3 全局事件与路由表收口
- 补齐未纳入语义路由表的事件：`agentCallbacks` 的 `selection.info`、`selection.snippet`、`selection.clear`；`registerCallbacks` 的 `subagent.history`、`task.event`、`todos.updated`；`main.tsx` 的 `font.editor.apply`、`font.ui.apply`、`font.code.apply`、`language.apply`、`notification.sound.play`、`daemon.status`、`turn.retry` 全部登记至 `registerHostEvents`。
- dispatcher 优先从路由表查找 handler，业务事件不再依赖 `window.*` 全局函数查找。

### C9 拆解巨石 Hook（useSessionTabs.ts）
- 将原本 1641 行的 `useSessionTabs.ts` 彻底拆解为 `sessionTabs/` 下的独立专职子模块：
  1. `types.ts`：集中定义 Hook 参数与生命周期配置。
  2. `useTabBarState.ts`：负责 Tab 列表派生、激活、重命名、拖拽排序、`window.__sessionTabsStore` 维护。
  3. `useTabLifecycle.ts`：负责 `createTab`、`closeTab`、`closeTabBySessionId`、`forkTab`、IDE 顶栏动作暴露。
  4. `useTabSessionBinder.ts`：负责快照保存、视图切换（`selectTab`）、历史会话加载（`openSessionInTab`）与 `onTabResync` 重载同步。
- `useSessionTabs.ts` 收敛为精简顶层入口，对外接口 100% 保持向前兼容。
- `sessionTransition.ts` 已经完全基于 per-tab transition，无全局锁。

### E15 内存单副本与缓存分区治理
- `messageMergeCache.ts` 升级为按 tab 分区的单例缓存容器，支持切 tab 复用已计算的 merge 结果，且在 `closeTab` 时精准释放该 tab 的缓存，彻底杜绝内存随会话切换累积泄漏。
- `useMessageProcessing.ts` 接入按 tab 分区的 merge cache。

### 门禁全绿验证
- `./gradlew test -x buildWebview -PskipWebview=true`：BUILD SUCCESSFUL（852 测试通过）。
- `cd webview && npx tsc --noEmit`：0 错误。
- `cd webview && npx vitest run`：173 文件 / 1512 测试全部通过。
- `./gradlew checkLegacyPatterns -PstrictLegacy=true`：0 forbidden hits, 0 remaining debt lines。



## P0：断链修复与死调用清理

排查方式：把宿主 `callJavaScript("名字")` 的集合与 webview 实际接收方求差，
逐个确认"有没有替代通道"。

### 修复的断链

- `open_external_url`（CLI 安装对话框的"打开外部链接"）宿主没有处理器，
  改发既有的 `open_browser`。
- `insertFileReferencesAtCursor`（从 IDE 向输入框添加文件引用）webview 从未实现，
  宿主改调既有的 `handleFilePathFromJava`，两条通道语义一致。

### 删除的死调用（webview 已无接收方）

- `mcpServerAdded` / `mcpServerUpdated` / `mcpServerDeleted` / `mcpServerValidated`
  ——列表由随后的 `updateMcpServers` 兜底刷新。
- `checkNodeEnvironment`（前端用 `updateNodePath`）。
- `onUserLanguage`（权威通道是 `applyIdeaLanguageConfig`）。
- `onSelectedAgentChanged` / `onSelectedAgentReceived`（前端不使用宿主选中态）。
- `onRewindResult` —— 连同 `RewindHandler` 整个删除：前端从不发 `rewind_files`。
- `onInputHistoryLoaded` / `Recorded` / `Cleared` / `Deleted` —— 连同
  `InputHistoryHandler` 整个删除：输入历史早已改为 webview 本地存储。

### 发现但未处理（P1）

- `usePromptManagement` 没有被任何组件使用，它发出的 `get_prompts` /
  `add_prompt` / `import_prompts_file` 等命令因此无人触发；`PromptHandler`
  整体进入死代码候选。旧的 prompt 导入/导出/预览 UI 已被 `PromptSection`
  的 `commands.*` 通道取代。

## P1：C9 收尾与 prompt 死链清理

- `sessionTransition.ts` 删除：它只剩两件事——`releaseSessionTransition()`
  （`endTransition(activeTabId())` 的薄封装，已内联到两个调用点，并按调用处的
  语义改成结束**所属 tab** 的过渡）和 `buildResetTransientUiState`
  （文件改名为 `resetTransientState.ts`，只负责清瞬时 UI 状态）。
  过渡守卫本身早在 `tabTransition.ts` 里按 tab 持有。
- `usePromptManagement.ts` 删除：没有任何组件使用它，它发的 `get_prompts` /
  `add_prompt` / `import_prompts_file` 等命令因此永远无人触发。
- `PromptHandler.java` 删除：上一条的直接结果。旧的 prompt 导入/导出/预览
  通道（含 `promptImportPreviewResult` / `promptOperationResult`）整体退场，
  命令管理只保留 `PromptSection` 的 `commands.*`。


## P1：A2 收口（配置类命令进信封）

- `HostCommand.parse` 允许 payload 是任意 JSON：字符串类命令（`open_file`、
  `open_class`、`delete_session`…）取裸值交给 handler，与旧通道字节一致；
  对象类命令照旧合并信封身份；`sendWindowCommand` 的 payload 类型放宽到
  `unknown`。
- 49 处 `sendToJava("name:...")` / `sendBridgeEvent("name", ...)` 全部改为
  `sendWindowCommand("name", payload)`：字体、语言、通知、声音、权限超时、
  agent CRUD、CLI 状态、IDE 主题、文件打开、JSON 导出。
- 至此前端不再有直发旧事件名的命令（`cardDebug` 是日志，保留原样），
  每条命令都带 `requestId`、走同一传输、由宿主统一幂等。

## 第四轮补 15：A3 宿主侧收口

- 把宿主最后 18 个只以函数名存在的事件补进双端语义目录：
  `selection.*`、`message.error`、`toast.*`、`context.usage.dialog`、`cli.models`、
  `history.data`、`chat.focus`、`files.*`、`tab.create.requested`。
- 新增 `EventCatalogTest.everyHostCallJavaScriptHasASemanticName`：扫描
  `src/main/java` 的 `callJavaScript("...")`，任何不在目录里的函数名直接失败。
  当前宿主 71 处调用、21 个不同名称，全部命中语义目录。
- `registerSemanticWindowHandlers()`：在 `main.tsx`、`registerWindowCallbacks`、
  设置窗口挂载后，把已挂载的 handler 绑定到 `hostEventRegistry`，避免业务事件
  依赖 dispatcher 的 window 回退。
- 修掉 `OpencodeBuddyToolWindow.createNewTab` 的真实断链：原先把一整段三元表达式
  当函数名传给 `callJavaScript`，被函数名守卫拒绝，新标签实际不会创建；现在发
  `tab.create.requested` 事件。
- 删除 `window.addSelectionInfo` / `addCodeSnippet` / `clearSelectionInfo` /
  `addErrorMessage` / `createSessionTab` 五个全局，对应 handler 直接注册。
- 验证：gradle 117 个测试类 / 864 用例 0 失败、tsc 0 错误、
  vitest `173 文件 / 1513 测试`、`checkLegacyPatterns` 0 forbidden / 0 debt。

## 第四轮补 16：A3 单一权威收口

- `hostDispatcher` 删除 `window.<fn>` 回退：只查 `hostEventRegistry`，未注册事件
  排队后按超时丢弃。`registerSemanticWindowHandlers()` 及其扫描逻辑一并删除。
- 宿主目录补齐字体/语言/设置类事件：`font.ui.*`、`font.code.*`、`language.apply`、
  `settings.auto.open.file`、`settings.permission.timeout`、`settings.send.shortcut`、
  `settings.streaming`、`settings.commit.*`、`settings.ai.title.generation`、
  `settings.statusbar.widget`、`settings.prompt.enhancer`。
- `EventCatalogTest` 现在同时扫描 `callJavaScript("name")` 和 `"window.name"`
  两种宿主写法，目录外的函数名直接失败。
- 修复权限失败路径：`invalidateQuestionCard` 之前被 `forceCloseFrontendDialog`
  误映射成 `prompt.closed`，现在发 `question.invalidated`，不再关闭错误弹窗。
- webview 非 `__` 的 window 全局从 141 降到 12：消息、流式、权限、会话、用量、
  MCP、技能、命令、设置、文件、剪贴板、节点进程、主题、CLI 模型、标签页重同步
  等宿主回调全部改为 `registerHostEvents` 局部 handler；内部调用改为
  `hostEventHandler(type)`。
- 删除重复的 `onStreamingHeartbeat` 旧实现，保留带 tab 归属判断的版本。
- `ClipboardHandlerTest` 改用内存 clipboard，避免 CI/锁屏机器上系统剪贴板不可用
  导致与业务无关的失败。
- 验证：gradle 117 个测试类 / 864 用例 0 失败、tsc 0 错误、
  vitest `173 文件 / 1512 测试`、`checkLegacyPatterns` 0 forbidden / 0 debt。

剩余 12 个 window 全局全部是组件内部动作（输入框引用/片段、Codex 分页、
IDE 动作、`$` 命令、子代理分块），不在语义目录，也不参与宿主派发。

## 第四轮补 17：内部 window 全局清零

- 输入框引用/片段：`insertCodeSnippetAtCursor` / `addQuotedSnippet` 改为
  `internal.chat.insertSnippet` / `internal.chat.addQuote`，`agentCallbacks` 与
  `quoteUtils` 改查 registry。
- `$` 命令：`updateDollarCommands` 改为 `internal.dollar.commands`，重置状态时
  撤销注册。
- IDEA 快捷动作：`execContextAction` 改为 `internal.chat.contextAction`。
- 子代理分块：`onSubagentHistoryChunk` 改为 `internal.subagent.historyChunk`。
- Codex 历史分页四个回调改为 `internal.history.codex*`；不再暴露 window。
- 旧的 `onHistoryWindowInfo` 没有发送方也没有监听方，直接删除。
- `onModelChanged` / `onSessionStateRestored` 改为 `internal.settings.modelChanged`
  与 `internal.session.stateRestored`。
- 至此 webview 非 `__` 的 window 回调全局为 **0**；只剩 `window.sendToJava`
  传输桥与 `__` 私有状态。
- 验证：tsc 0 错误、相关 vitest 124 用例通过；完整四项在提交前重跑。
