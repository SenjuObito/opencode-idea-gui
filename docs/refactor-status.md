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
| 已完成 | 9 | 56% |
| 部分完成 | 6 | 38% |
| 待环境 | 1 | 6% |
| 合计 | 16 | 100% |

四项验证（每一轮都必须全绿才提交）：

```bash
./gradlew test -x buildWebview -PskipWebview=true
cd webview && npx tsc --noEmit
cd webview && npx vitest run
./gradlew checkLegacyPatterns -PstrictLegacy=true
```

当前基线（`ffaf345` + 本轮工作区改动）：gradle 118 个测试类 / 866 用例
0 失败、tsc 0 错误、vitest `173 文件 / 1512 测试` 全通过、
`checkLegacyPatterns` 0 forbidden / 0 debt。

## 二、条目明细

| 条目 | 内容 | 状态 | 证据 / 差距 |
|---|---|---|---|
| A1 | 事件具名化：`TabEvent` / `TabEventSink` / `TabEventBus`，信封 `{v, type, tabId, sessionId, turnId, seq, epoch, payload}` | 已完成 | 宿主会话级事件与 webview 处理器全部具名 payload，双端目录有交叉校验测试 |
| A2 | 命令目录：全部命令走单一 `host_command` 信封，类型统一 `域.动作` | 部分完成 | 会话/标签/附件/提示类已迁入；仍有 **50 个**前端命令直发旧事件名（`get_prompts`、`add_agent`、`set_ui_font_config`、`browse_ui_font_file` 等配置 CRUD），无 requestId 幂等 |
| A3 | 路由表收口：所有业务事件走 `hostEventRegistry` 权威派发 | 部分完成 | 已登记 **43 个语义键**，但 webview 仍有 **145 个非 `__` 的 `window.*` 全局**（与语义键零交集，注册值直接取自 window）；宿主 **84 处** `callJavaScript("函数名")`，其中 **79 个**只能落回 window。原先 **13 个宿主调用在 webview 无接收方**，已在本轮清理完毕（见 P0 记录） |
| B4 | 权限 / 提问 / 计划回复三重校验（tabId + sessionId + requestId） | 已完成 | `b78cbc9` 及 `PermissionHandler` 校验链 |
| B5 | 计划审批 per-tab 队列（`pendingPlans`） | 已完成 | 对话式审批不再串 tab |
| B6 | daemon 重启：给所有 STREAMING tab 发 `turn.ended reason=daemon_restart` | 已完成 | `9e191cc` |
| C7 | `TabSnapshot` / `PendingSend` / `TabStreamRouter` | 部分完成 | 三件套均落地且在引用（`d3c0542`）；但 `SessionCallbackAdapter`（8 个文件）与 `StreamMessageCoalescer`（10 个文件）仍承担主输出，`TabStreamRouter` 是包装而非替换 |
| C8 | `TabRecord` 字段：附件、router、eventSink 已归 tab；prompts 由 `PromptBroker` 持有，TabDraft 归 webview | 已完成 | `17ada18`、`dc1b76a`、`614e028` |
| C9 | 巨石 Hook 拆解与清理：`useSessionTabs.ts`、`sessionTransition.ts`、`PermissionManager` | 已完成 | 已拆成 69 行协调器 + `useTabBarState` / `useTabSessionBinder` / `useTabLifecycle`（1767 行）；`PermissionManager`、`sessionTransition.ts`、`usePromptManagement` + `PromptHandler` 均已删除 |
| D10 | 附件传输：分块上传，发送只带引用 | 已完成 | `dc1b76a` |
| D12 | opencode 命令按 tab 寻址 | 已完成 | `commands.refresh/list/read/save/delete` 按 tab cwd 解析与缓存 |
| E13 | 每帧参数预算 32KB（单事件超预算单独成帧） | 已完成 | `17ada18` |
| E14 | 渲染：消息分页 / 窗口化、序列化缓存、Markdown 缓存、引用稳定 | 部分完成 | 按回合分页（每页 5 轮）与 `MessageList` memo / `useMemo` 已有；**未见**专门的序列化缓存与 Markdown 渲染缓存，引用稳定无测试覆盖 |
| E15 | 内存：每 tab 消息单副本与缓存分区治理 | 部分完成 | `messageMergeCache` 按 tabId 分区、切 tab 复用；视图已直读 tab store（`4748421`）；宿主与 webview 各持一份仍是设计现状 |
| E16 | 指标上报：queue depth / 丢弃数 / turn 时长 | 部分完成 | `BridgeScheduler.Metrics` 记录 queueDepth / dropped / frames，`TabRecord` 记录回合时长；**没有消费方与上报出口**（不落日志、不出事件） |
| §13 | JCEF 真实基准与功能矩阵 | 待环境 | 步骤见 `docs/jcef-benchmark.md`、`docs/functional-matrix.md`，需 IDE 运行时 |

## 三、剩余工作与估算

| 优先级 | 剩余项 | 性质 | 估算 |
|---|---|---|---|
| P0 | ~~本轮改动提交；`open_external_url` 断链；13 个宿主死调用与死代码清理~~ | 已完成 | — |
| P1 | A2：50 个配置类命令迁入 `host_command` | 机械、量大 | 1 天 |
| P1 | A3：98 处 `callJavaScript` 改发语义事件，删除 145 个 window 全局 | 结构性 | 2 天 |
| P1 | C7：`SessionCallbackAdapter` / `StreamMessageCoalescer` 退场，`TabStreamRouter` 接管 | 结构性 | 1 天 |
| P2 | E14 序列化 / Markdown 缓存；E15 单副本收口；E16 指标出口 | 性能与可观测 | 2 天 |
| P3 | §13 JCEF 基准 + 功能矩阵 | 需 IDE 运行时 | 0.5 天（人工） |

合计约 **7–8 个工作日**。

## 四、结论

- 协议新通道（具名事件 + 语义目录）与命令信封的主体已经落地，测试与门禁全绿，
  功能路径可用。
- **但"单一权威"尚未达成**：旧函数名通道与 145 个 window 全局仍在运行，
  A2 / A3 是"双轨并行"而非"只剩一条路"；C7 是包装而非替换。
- 上一版本文档写的"100% 落地、无折中残留"与代码事实不符，已按实测数据更正。
