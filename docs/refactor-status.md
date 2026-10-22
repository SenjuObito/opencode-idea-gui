# 重构进度总表（折中项落地）

- 分支：`refactor/full-cutover`
- 方案原文：`docs/full-refactor-architecture-plan.md`（验收口径）
- 逐轮流水：`docs/refactor-progress.md`
- 配套仓库：`../opencode-buddy-core`
- 统计时间：以 `dc1b76a`（附件分块上传）为基线

## 一、总览

| 状态 | 条目数 | 占比 |
|---|---:|---:|
| 已完成 | 6 | 38% |
| 部分完成 | 5 | 31% |
| 未开始 | 5 | 31% |
| 合计 | 16 | 100% |

四项验证（每一轮都必须全绿才提交）：

```bash
./gradlew test -x buildWebview -PskipWebview=true
cd webview && npx tsc --noEmit
cd webview && npx vitest run
./gradlew checkLegacyPatterns -PstrictLegacy=true
```

当前基线：gradle `BUILD SUCCESSFUL`、tsc 通过、vitest `173 文件 / 1516 测试`
全通过、`checkLegacyPatterns` 0 forbidden / 0 debt。

## 二、条目明细

| 条目 | 内容 | 状态 | 证据 / 提交 |
|---|---|---|---|
| A1 | 事件具名化：`TabEvent` / `TabEventSink` / `TabEventBus`，信封 `{v, type, tabId, sessionId, turnId, seq, epoch, payload}` | 已完成 | 宿主会话级事件与 webview 处理器全部具名 payload |
| A2 | 命令目录：全部命令走单一 `host_command` 信封，类型统一 `域.动作` | 部分完成 | 主路径已统一；webview 仍有约 70 处 `sendBridgeEvent`（历史/设置/窗口命令） |
| A3 | 路由表已权威（31 个事件）；约 96 个 `window.*` 全局仍在 | 部分完成 | 路由层已落地，全局清理待做 |
| B4 | 权限 / 提问 / 计划回复三重校验（tabId + sessionId + requestId） | 已完成 | `b78cbc9` 及 `PermissionHandler` 校验链 |
| B5 | 计划审批 per-tab 队列（`pendingPlans`） | 已完成 | 对话式审批不再串 tab |
| B6 | daemon 重启：给所有 STREAMING tab 发 `turn.ended reason=daemon_restart` | 已完成 | `9e191cc` |
| C7 | `TabSnapshot` / `PendingSend` / `TabStreamRouter` | 部分完成 | `99bd04c`：`TabSnapshot`、`PendingSend`、core 先播报 `[SESSION_ID]`；`TabStreamRouter` 仍名 `SessionStreamRouter`，adapter/coalescer 未替换 |
| C8 | `TabRecord` 字段：附件归属已完成；缺 router / eventSink / prompts / TabDraft | 部分完成 | `17ada18`（附件归属）、`dc1b76a`（分块上传） |
| C9 | 删除清单：`useSessionTabs.ts`（1641 行）、`sessionTransition.ts`、`PermissionManager` | 未开始 | 风险最高，需分两步：先搬家后删旧 |
| D10 | 附件传输：分块上传，发送只带引用 | 已完成 | `dc1b76a` |
| D12 | opencode 命令按 tab 寻址 | 部分完成 | `99bd04c`：`commands.refresh` 带 tabId 并按 tab cwd 刷新；`commands.list` 仍是窗口级设置 |
| E13 | 每帧参数预算 32KB（单事件超预算单独成帧） | 已完成 | `17ada18` |
| E14 | 渲染：消息分页 / 窗口化、序列化缓存、Markdown 缓存、引用稳定 | 未开始 | — |
| E15 | 内存：每 tab 消息单副本（宿主 + webview 镜像目前两份） | 未开始 | — |
| E16 | 指标上报：queue depth / 丢弃数 / turn 时长 | 未开始 | — |
| §13 | JCEF 真实基准与功能矩阵 | 待环境 | 步骤见 `docs/jcef-benchmark.md`、`docs/functional-matrix.md`，需 IDE 运行时 |

## 三、剩余工作与估算

| 剩余项 | 性质 | 估算 |
|---|---|---|
| A2 剩余：约 70 处 `sendBridgeEvent` 迁入命令信封并删除旧入口 | 机械、量大 | 1–2 天 |
| A3 剩余：摘除约 96 个 `window.*` 全局，只保留路由表分发 | 机械、量大 | 0.5–1 天 |
| C8 剩余：`TabRecord` 补 router / eventSink / prompts / TabDraft | 结构性 | 1 天 |
| C7 剩余：`SessionStreamRouter` 迁为 `TabStreamRouter`，替换 adapter/coalescer | 结构性 | 1–2 天 |
| C9：拆 `useSessionTabs.ts`、删 `sessionTransition.ts`、迁 `PermissionManager` | 结构性、高风险 | 2–3 天 |
| D12 剩余：`commands.list` 按 tab cwd 返回并缓存 | 中等 | 0.5 天 |
| E14 / E15 / E16：渲染窗口化与缓存、消息单副本、指标上报 | 结构性 | 2–4 天 |
| §13：JCEF 基准 + 功能矩阵 | 需 IDE 运行时 | 0.5 天（人工执行） |

合计约 **8–12 个工作日**。按当前节奏（每次一个可提交的块 + 四项验证全绿再提交），
约 **10–14 个推进轮次**。

## 四、结论

- 命名事件主路径（A1）与会话命令主路径已经收口；A2 仍有约 70 处窗口/设置/历史
  命令走旧 `sendBridgeEvent`，不能再说"只有一种走法"。
- 附件链路（C8 附件部分 / D10）已经完整，且不依赖 core 改动。
- 新会话引导（C7 的 `PendingSend` / `TabSnapshot`）已落地；core 现在先播报
  `[SESSION_ID]` 再绑定身份，v1/v2 共用。
- 剩余工作集中在**状态归属**（C7 / C8 / C9 / E15）与**渲染性能**（E14），
  这两块必须分步落地，不能一次重写。
