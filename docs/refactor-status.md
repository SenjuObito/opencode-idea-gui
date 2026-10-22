# 重构进度总表（折中项落地）

- 分支：`refactor/full-cutover`
- 方案原文：`docs/full-refactor-architecture-plan.md`（验收口径）
- 逐轮流水：`docs/refactor-progress.md`
- 配套仓库：`../opencode-buddy-core`
- 统计时间：以 `dc1b76a`（附件分块上传）为基线

## 一、总览

| 状态 | 条目数 | 占比 |
|---|---:|---:|
| 已完成 | 9 | 56% |
| 部分完成 | 4 | 25% |
| 未开始 | 3 | 19% |
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
| A2 | 命令目录：全部命令走单一 `host_command` 信封，类型统一 `域.动作` | 部分完成 | 主路径与生命周期事件已统一；仅剩 5 处 `load_subagent_session` 旧调用，需 core 暴露子会话定位/读取后迁入信封 |
| A3 | 路由表已权威（31 个事件）；约 96 个 `window.*` 全局仍在 | 部分完成 | 路由层已落地，全局清理待做 |
| B4 | 权限 / 提问 / 计划回复三重校验（tabId + sessionId + requestId） | 已完成 | `b78cbc9` 及 `PermissionHandler` 校验链 |
| B5 | 计划审批 per-tab 队列（`pendingPlans`） | 已完成 | 对话式审批不再串 tab |
| B6 | daemon 重启：给所有 STREAMING tab 发 `turn.ended reason=daemon_restart` | 已完成 | `9e191cc` |
| C7 | `TabSnapshot` / `PendingSend` / `TabStreamRouter` | 已完成 | `d3c0542` 落地三件套；本轮 `TabStreamRouter` 成为 adapter/coalescer 的唯一持有者，换 session 时旧输出随旧 session 释放，adapter 按 tab 产出命名事件 |
| C8 | `TabRecord` 字段：附件归属已完成；router 与 eventSink 已收口；prompts 由 `PromptBroker` 按 tab 键持有，TabDraft 只在 webview | 部分完成 | `17ada18`（附件）、`dc1b76a`（分块上传）、本轮（router + eventSink） |
| C9 | 删除清单：`useSessionTabs.ts`（1641 行）、`sessionTransition.ts`、`PermissionManager` | 部分完成 | `PermissionManager` 及本地权限回退已删除（opencode 权限只走服务端 `per_*` + 注册表）；`useSessionTabs` 拆分与 `sessionTransition` 清理待做 |
| D10 | 附件传输：分块上传，发送只带引用 | 已完成 | `dc1b76a` |
| D12 | opencode 命令按 tab 寻址 | 已完成 | `99bd04c`（`commands.refresh` 按 tab）；本轮 `commands.list/read/save/delete` 按 tab cwd 解析，列表按 scope+cwd 缓存 2s，写删后失效 |
| E13 | 每帧参数预算 32KB（单事件超预算单独成帧） | 已完成 | `17ada18` |
| E14 | 渲染：消息分页 / 窗口化、序列化缓存、Markdown 缓存、引用稳定 | 未开始 | — |
| E15 | 内存：每 tab 消息单副本（宿主 + webview 镜像目前两份） | 未开始 | — |
| E16 | 指标上报：queue depth / 丢弃数 / turn 时长 | 已完成 | 本轮：`BridgeScheduler.Metrics` 记录队列深度、合并/丢弃数、成帧数/字节、reload 数；`TabRecord` 记录上一回合时长；tab 关闭与窗口销毁时按 tab/session 写日志 |
| §13 | JCEF 真实基准与功能矩阵 | 待环境 | 步骤见 `docs/jcef-benchmark.md`、`docs/functional-matrix.md`，需 IDE 运行时 |

## 三、剩余工作与估算

| 剩余项 | 性质 | 估算 |
|---|---|---|
| A2 剩余：5 处 `load_subagent_session` 需 core 子会话读取接口 | 依赖 core | 0.5–1 天（含 core） |
| A3 剩余：摘除约 96 个 `window.*` 全局，只保留路由表分发 | 机械、量大 | 0.5–1 天 |
| C8 剩余：`TabRecord` 的 prompts / TabDraft | 结构性 | 0.25 天 |
| C7 剩余：无（`TabStreamRouter` 已持有 adapter/coalescer） | 已完成 | 0 |
| C9：拆 `useSessionTabs.ts`、删 `sessionTransition.ts`、迁 `PermissionManager` | 结构性、高风险 | 2–3 天 |
| E14 / E15：渲染窗口化与缓存、消息单副本 | 结构性 | 2–4 天 |
| §13：JCEF 基准 + 功能矩阵 | 需 IDE 运行时 | 0.5 天（人工执行） |

合计约 **8–12 个工作日**。按当前节奏（每次一个可提交的块 + 四项验证全绿再提交），
约 **10–14 个推进轮次**。

## 四、结论

- 命名事件主路径（A1）与会话命令主路径已经收口；A2 只剩 5 处子会话历史
  调用，等 core 提供子会话定位/读取后迁入信封，其余旧 `sendBridgeEvent`
  功能入口已随宿主未实现路径删除。
- 附件链路（C8 附件部分 / D10）已经完整，且不依赖 core 改动。
- 新会话引导（C7 的 `PendingSend` / `TabSnapshot`）已落地；core 现在先播报
  `[SESSION_ID]` 再绑定身份，v1/v2 共用。
- 剩余工作集中在**状态归属**（C7 / C8 / C9 / E15）与**渲染性能**（E14），
  这两块必须分步落地，不能一次重写。
