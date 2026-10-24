# 重构进度总表（折中项落地）

- 分支：`refactor/full-cutover`
- 方案原文：`docs/full-refactor-architecture-plan.md`（验收口径）
- 逐轮流水：`docs/refactor-progress.md`
- 配套仓库：`../opencode-buddy-core`
- 口径：**只按代码事实判定**。"已完成"= 方案要求的入口唯一、旧路径已删除，
  而不是"新路径已存在"。

## 一、总览

| 状态 | 条目数 | 占比 |
|---|---:|---:|
| 已完成 | 14 | 88% |
| 部分完成 | 1 | 6% |
| 待环境 | 1 | 6% |
| 合计 | 16 | 100% |

四项验证（每一轮都必须全绿才提交）：

```bash
./gradlew test -x buildWebview -PskipWebview=true
cd webview && npx tsc --noEmit
cd webview && npx vitest run
./gradlew checkLegacyPatterns -PstrictLegacy=true
```

当前基线（`b0c4133` + 本轮工作区改动）：gradle 117 个测试类 / 864 用例
0 失败、tsc 0 错误、vitest `173 文件 / 1513 测试` 全通过、
`checkLegacyPatterns` 0 forbidden / 0 debt。

## 二、条目明细

| 条目 | 内容 | 状态 | 证据 / 差距 |
|---|---|---|---|
| A1 | 事件具名化：`TabEvent` / `TabEventSink` / `TabEventBus`，信封 `{v, type, tabId, sessionId, turnId, seq, epoch, payload}` | 已完成 | 宿主会话级事件与 webview 处理器全部具名 payload，双端目录有交叉校验测试 |
| A2 | 命令目录：全部命令走单一 `host_command` 信封 | 已完成 | 前端已无任何直发旧事件名的命令（`cardDebug` 日志除外）；`HostCommand` 支持任意 JSON payload，字符串类命令取裸值 `b416424` |
| A3 | 路由表收口：所有业务事件走 `hostEventRegistry` 权威派发 | 部分完成 | 双端语义目录已到 **117 键**；宿主 **71 处** `callJavaScript`（**21 个不同名称**）**全部在目录中**，新增 `EventCatalogTest.everyHostCallJavaScriptHasASemanticName` 防止再落回函数名；启动、设置挂载时用 `registerSemanticWindowHandlers()` 把已挂载 handler 绑定进 registry。webview 仍有 **141 个非 `__` 的 `window.*` 全局**（本轮已删 selection 三个、`addErrorMessage`、`createSessionTab`），dispatcher 仍保留 window 回退 |
| B4 | 权限 / 提问 / 计划回复三重校验（tabId + sessionId + requestId） | 已完成 | `b78cbc9` 及 `PermissionHandler` 校验链 |
| B5 | 计划审批 per-tab 队列（`pendingPlans`） | 已完成 | 对话式审批不再串 tab |
| B6 | daemon 重启：给所有 STREAMING tab 发 `turn.ended reason=daemon_restart` | 已完成 | `9e191cc` |
| C7 | `TabSnapshot` / `PendingSend` / `TabStreamRouter` | 已完成 | `TabStreamRouter` 是数据面 owner（daemon 行按 session 直达）；渲染面由 `SessionCallbackAdapter` 经 `TabEventSink` 发布，已无 `callJavaScript`；`JsCallbackTarget` 的 JS 方法从未被调用，已删 |
| C8 | `TabRecord` 字段：附件、router、eventSink 已归 tab；prompts 由 `PromptBroker` 持有，TabDraft 归 webview | 已完成 | `17ada18`、`dc1b76a`、`614e028` |
| C9 | 巨石 Hook 拆解与清理：`useSessionTabs.ts`、`sessionTransition.ts`、`PermissionManager` | 已完成 | 已拆成 69 行协调器 + `useTabBarState` / `useTabSessionBinder` / `useTabLifecycle`（1767 行）；`PermissionManager`、`sessionTransition.ts`、`usePromptManagement` + `PromptHandler` 均已删除 |
| D10 | 附件传输：分块上传，发送只带引用 | 已完成 | `dc1b76a` |
| D12 | opencode 命令按 tab 寻址 | 已完成 | `commands.refresh/list/read/save/delete` 按 tab cwd 解析与缓存 |
| E13 | 每帧参数预算 32KB（单事件超预算单独成帧） | 已完成 | `17ada18` |
| E14 | 渲染：消息分页 / 窗口化、序列化缓存、Markdown 缓存、引用稳定 | 已完成 | 按回合分页（每页 5 轮）+ `MessageList` memo/useMemo；`MarkdownBlock` 增加按输出因素分键的 HTML LRU（300 条，流式 tail 不入缓存）`cf67523` |
| E15 | 内存：每 tab 消息单副本与缓存分区治理 | 已完成 | webview 消息唯一副本在 `tabStore`；`messageMergeCache` 按 tabId 分区、切 tab 复用、关 tab 清理；宿主侧 `OpencodeSession.messages` 是服务端权威源，不属于镜像 |
| E16 | 指标上报：queue depth / 丢弃数 / turn 时长 | 已完成 | `TabRecord` 记录 lastSeq / turnMs / attachments / epoch；桥接队列指标与每 tab 指标每 30s 写入 `PluginFileLogger` 的 METRICS 通道 `cf67523` |
| §13 | JCEF 真实基准与功能矩阵 | 待环境 | 步骤见 `docs/jcef-benchmark.md`、`docs/functional-matrix.md`，需 IDE 运行时 |

## 三、剩余工作与估算

| 优先级 | 剩余项 | 性质 | 估算 |
|---|---|---|---|
| P0 | ~~本轮改动提交；`open_external_url` 断链；13 个宿主死调用与死代码清理~~ | 已完成 | — |
| P1 | A3 剩余：删除 141 个 window 全局并移除 dispatcher 的 window 回退（宿主 `callJavaScript` 已全部有语义名） | 结构性 | 1.5 天 |
| P3 | §13 JCEF 基准 + 功能矩阵 | 需 IDE 运行时 | 0.5 天（人工） |

合计约 **7–8 个工作日**。

## 四、结论

- 协议新通道（具名事件 + 语义目录）与命令信封的主体已经落地，测试与门禁全绿，
  功能路径可用。
- **但“单一权威”尚未达成**：宿主已经不再有目录外的函数名事件，但 webview 的
  141 个 window 全局与 dispatcher 回退仍在运行；A3 是“宿主侧收口”，不是“只剩一条路”。
- 上一版本文档写的“100% 落地、无折中残留”与代码事实不符，已按实测数据更正。
