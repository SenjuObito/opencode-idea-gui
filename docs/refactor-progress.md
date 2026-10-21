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

## 验证

在 `/Users/obito/source/repos/opencode-idea-gui-refactor` 执行：

- `./gradlew test`：通过。
- `npm test`：169 个文件、1496 个测试通过。
- `tsc --noEmit`：通过。
- `git diff --check`：通过。

## 未完成

- `TabSessionActor`
- `TabSessionFactory`
- `TabCommandRouter`
- `BridgeScheduler` 的 per-tab 公平调度
- 完整 typed envelope 协议
- 独立 `PromptBroker` 类（当前仍由 `PermissionHandler` 承担）
- 独立 `TabStatusStore` / `TabPromptStore` 的最终收口
- reload/daemon 重启 resync、epoch/requestId 幂等验收
- 真实 JCEF 6 tab / 3 流 / 10k 消息性能基准
