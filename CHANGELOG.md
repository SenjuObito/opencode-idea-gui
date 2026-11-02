# Changelog

Each released version has one section holding a `中文：` half and an `English：`
half, so both languages ship in the bundled changelog dialog and in the GitHub
Release body. Consumers of this file:

- `webview/scripts/extract-changelog.mjs` → bundled `webview/src/version/changelog.ts`
- `tools/extract-release-notes.mjs` → GitHub Release body (emits the English half
  under an `### English` heading, which the changelog dialog splits on)

## 2.0.3 (2026-11-02)

中文：

✨ 新增功能
- 支持自动压缩 Token，消息队列调度更稳定

🐛 问题修复
- 修复出现错误提示后无法继续发送消息
- 修复 Markdown 导出追加多余扩展名、成功提示时机不对
- 修复弹窗未按标签页归属、权限弹窗边框
- 修复相关 UI 显示错误

English：

✨ Features
- Automatic token compaction and more stable message queue scheduling

🐛 Fixes
- Fixed messages being blocked after an error notice appeared
- Fixed Markdown export appending an extra extension and the success toast firing too early
- Fixed dialogs not being scoped to their tab, and the permission dialog border
- Fixed related UI display issues

## 2.0.2 (2026-10-28)

中文：

🔧 其他
- 插件描述同步 README 的核心功能与架构说明

English：

🔧 Other
- Synced the plugin description with the README feature and architecture overview

## 2.0.1 (2026-10-28)

中文：

✨ 界面优化
- 会话消息状态背景动态分级：空状态展示极光背景，有消息时切回纯净底色

English：

✨ UI Polish
- Grade the message-state background dynamically: an aurora backdrop for empty states and a clean surface once messages arrive

## 2.0.0 (2026-10-28)

中文：

OpenCode Buddy 迎来重大架构重构版本：落地全双工语义契约、Tab Actor 多会话物理隔离、OpenCode v1 与 v2 双世代深度兼容，并大幅提升渲染性能与界面质感。

- **架构全面重构（全双工语义事件与强类型信封）**：
  - 彻底淘汰旧式的 `window[fn](...args)` 位置参数调用机制，宿主与前端通信全面转向语义事件系统（Semantic Event Catalog），权威派发覆盖 138 个语义键，非私有全局 `window` 回调清零。
  - 前端发往宿主的指令统一收归为单一 `host_command` 强类型信封（携带 `requestId`、`tabId`、语义动作及泛型 payload），彻底杜绝参数多重转义与状态栏误报。
  - 建立双端协议交叉校验测试与静态护栏，保证全链路类型安全。

- **多对话彻底物理隔离（Tab Actor 架构）**：
  - 重构多标签会话模型为 Tab Actor 架构，每个 Tab 独立持有专属的 `TabRecord`、`TabStreamRouter`、`TabMessageStore`、Turn 状态及独立事件通道。
  - 彻底删除全局会话切换锁、跨 Tab 状态浅拷贝及 ref 交换，根治以往多会话流式输出串流、会话标题被新会话覆盖、Token 消耗统计混淆及首轮响应卡死等问题。
  - 消息视图直接绑定 tab store 并通过消息 ID 严格对账；会话 `/fork` 独立落入新 Tab 且不影响源会话；`/revert` 严格遵从 OpenCode 快照工作流。
  - 权限回复、交互式提问（Questions）与计划审批（Plans）严格归属对应 Tab，具备 `tabId + sessionId + requestId` 三重校验与独立队列。

- **OpenCode v1 与 v2 双世代深度自适应与兼容**：
  - 升级服务端探测逻辑（`GET /api/info` 探测响应，状态透明展示为 `pending | v1 | v2 | unknown`），无缝适配两代协议差异。
  - 兼容 OpenCode v1 消息撤销（revert）写操作还原与文件快照回滚能力。
  - 完善会话压缩总结（Session Compaction Fold）体验，支持智能折叠与历史上下文保留。
  - 子代理（Subagent）规范化历史转发，将子代理规范 transcript 与 todo 状态作为真实权威源；打通 OpenCode MCP 服务器读取与技能回复通道。

- **性能调优、内存治理与渲染优化**：
  - 附件传输重构为分块上传（Chunked Upload），消息发送仅携带引用 ID，单帧参数预算限制在 32KB，消除大附件导致的 IPC 阻塞。
  - 对话渲染优化：支持按回合分页加载与稳定引用更新，引入 300 条 LRU HTML 缓存（`MarkdownBlock`），大幅削减长对话滚动重绘耗时。
  - 内存治理：按 Tab 分区治理消息缓存与合并缓存（`messageMergeCache`），关闭标签即时清理；接入桥接队列深度、丢弃数与回合耗时指标上报。

- **视觉与交互质感全方位升级**：
  - 升级全新 OpenCode 正统全息多维棱晶图腾（覆盖 16px-512px 全套矢量与光栅资源）。
  - 启动加载重构为 Java2D 原生 `AeroLoadingPanel` 秒开，配合 HTML 首字节内联深色背景，彻底根除启动白屏与闪烁。
  - 状态面板升级为微通透悬浮毛玻璃胶囊（Glassmorphism），作用域穿透至整体窗口，消除展开后的横向色差断层；支持自定义模式配色与宿主推送。

English：

OpenCode Buddy major release featuring full architectural refactoring, full-duplex semantic contracts, Tab Actor multi-session isolation, deep compatibility with OpenCode v1 & v2, plus major rendering performance and UI polish.

- **Full Architectural Refactoring (Typed Envelopes & Semantic Events)**:
  - Retired the legacy `window[fn](...args)` positional parameter dispatching. Communication between the host and webview now strictly uses a Semantic Event Catalog (138 registered semantic actions) via authoritative routing, completely eliminating non-private window-level callbacks.
  - Consolidated frontend-to-host commands into a unified `host_command` typed envelope (carrying `requestId`, `tabId`, semantic action, and typed payload), preventing argument double-escaping and status misreports.
  - Established cross-boundary catalog integration tests and static architectural guardrails to ensure end-to-end type safety.

- **Strict Multi-Session Isolation (Tab Actor Model)**:
  - Transitioned the multi-tab session architecture into a Tab Actor model: each tab independently owns its own `TabRecord`, `TabStreamRouter`, `TabMessageStore`, turn lifecycle state, and dedicated event sink.
  - Completely eliminated global session transition locks, cross-tab snapshot copying, and ref swapping, resolving cross-session streaming leaks, title overrides, token metric cross-talk, and first-turn pending freezes.
  - Chat views bind directly to the tab store with per-message ID reconciliation. Session `/fork` cleanly creates a new tab without affecting the parent, and `/revert` strictly adheres to OpenCode snapshots.
  - Interactive permissions, question forms, and plan approvals are strictly scoped per tab with triple-check validation (`tabId + sessionId + requestId`) and per-tab queues.

- **Deep OpenCode v1 & v2 Compatibility**:
  - Enhanced engine probing (`GET /api/info` probe with explicit states: `pending | v1 | v2 | unknown`) with seamless fallback across protocol differences.
  - Added support for OpenCode v1 message undo/revert write-operation restoration and snapshot rollbacks.
  - Polished session compaction fold behaviors, retaining tab fold states and compaction notices across restarts.
  - Forwarded canonical subagent history from core, treating child transcripts and canonical todo states as single sources of truth; unified OpenCode MCP server discovery and skills responses.

- **Performance, Memory Governance & Rendering Optimizations**:
  - Re-architected attachments into chunked uploads with reference-only prompt submissions and a strict 32KB per-frame parameter budget, preventing IPC bottlenecks.
  - Optimized conversation rendering: introduced turn-based pagination and reference-stable message lists, backed by a 300-entry LRU HTML cache (`MarkdownBlock`) to eliminate re-rendering freezes during long sessions.
  - Memory governance: partitioned message caches and merge caches (`messageMergeCache`) by tabId with automatic cleanup on tab closure; added live telemetry for bridge queue depth, dropped events, and turn durations.

- **Visual & UI Experience Overhaul**:
  - Introduced the new OpenCode holographic multidimensional prism emblem across all vector and raster assets (16px to 512px).
  - Rebuilt the startup screen with native Java2D `AeroLoadingPanel` instant opening paired with inline dark styles in index.html to permanently eliminate white startup flicker.
  - Redesigned the status panel into a floating translucent glassmorphism capsule spanning the entire chat screen shell, eliminating horizontal seam contrast, and added support for host-pushed modes and custom color schemes.

## 0.2.2 (2026-10-19)

中文：

🐛 问题修复
- 修复多个会话被更新为同一个标题
- 修复构建脚本与设置环境变量不生效

🔧 其他
- 更新 README

English：

🐛 Fixes
- Fixed multiple sessions being renamed to the same title
- Fixed the build script and environment variables not taking effect

🔧 Other
- Updated the README

## 0.2.1 (2026-10-19)

中文：

✨ 新增功能
- 插件内实现多标签页会话，新增多会话支持并调整架构
- 问题弹窗、权限弹窗、bash/opencode 命令、会话目录兼容 OpenCode v2
- 撤销、Fork 等操作兼容 OpenCode v2
- 额度用完时展示错误信息

🐛 问题修复
- 修复提示音音量过小、多会话切换消息丢失
- 修复状态面板 Todo 列表不更新
- 修复未通过的单例测试

English：

✨ Features
- Multi-tab sessions inside the plugin, with multi-session support and an architecture overhaul
- Question dialogs, permission dialogs, bash/opencode commands, and session directories now work with OpenCode v2
- Undo, fork, and related actions now work with OpenCode v2
- Show an error when the quota is exhausted

🐛 Fixes
- Fixed low notification volume and messages lost when switching multi-session tabs
- Fixed the status panel todo list not refreshing
- Fixed failing singleton tests

## 0.0.8 (2026-10-14)

中文：

✨ 新增功能
- 支持 OpenCode v2：问题弹窗、权限弹窗最小化
- 架构重构：移除 ai-bridge，改用 opencode-buddy-core

🐛 问题修复
- 修复 GitHub 测试与打包

English：

✨ Features
- OpenCode v2 support, with a question dialog and a minimizable permission dialog
- Architecture overhaul: replaced ai-bridge with opencode-buddy-core

🐛 Fixes
- Fixed the GitHub tests and packaging

## 0.0.7 (2026-10-12)

中文：

✨ 新增功能
- 所有 JS 注入改为走 bridge

🐛 问题修复
- 修复 Token 显示与详细消息展示
- 修复打包覆盖 VS Code 插件的问题
- 修复历史会话加载

English：

✨ Features
- Every JS injection now goes through the bridge

🐛 Fixes
- Fixed token display and detailed message rendering
- Fixed packaging overwriting the VS Code plugin
- Fixed history session loading

## 0.0.6 (2026-09-23)

中文：

🐛 问题修复
- 修复 UI 冻结
- 修复启动时重复拉起 opencode serve

English：

🐛 Fixes
- Fixed UI freezes
- Fixed opencode serve being launched repeatedly at startup

## 0.0.5 (2026-09-23)

中文：

🐛 问题修复
- 还原误删的 Node 路径与多标签页对话

English：

🐛 Fixes
- Restored the accidentally deleted Node path and multi-tab conversations

## 0.0.4 (2026-09-22)

中文：

🐛 问题修复
- 修复 Token 消耗 loading 与会话切换问题

English：

🐛 Fixes
- Fixed the token-usage loading indicator and session switching

## 0.0.3 (2026-09-22)

中文：

✨ 新增功能
- 重构模型列表，插件描述支持中英双语
- 添加赞助者信息

🐛 问题修复
- 修复 MCP 加载与模型列表默认选中项
- 修复状态栏等待 opencode serve 启动
- 修复单测、启动字体加载、Node 配置与构建问题
- 修复 opencode TUI 路径保存与对话问题
- 修复日志异常，移除超时自动结束对话逻辑
- 修复启动状态栏与 opencode 进程生命周期

English：

✨ Features
- Rebuilt the model list and shipped the plugin description in Chinese and English
- Added sponsor information

🐛 Fixes
- Fixed MCP loading and the model list default selection
- Fixed the status bar waiting for opencode serve to start
- Fixed unit tests, startup font loading, Node configuration, and the build
- Fixed saving the opencode TUI path and conversation issues
- Fixed logging issues and removed the timeout that auto-ended conversations
- Fixed the startup status bar and the opencode process lifecycle

## 0.0.2 (2026-09-17)

中文：

✨ 新增功能
- 支持多标签多会话
- 支持会话导出为 Markdown
- 补齐 Windows 获取 opencode 路径的单元测试

🔧 其他
- 修改开源协议

🐛 问题修复
- 修复 Linux 下图标与文字对齐
- 修复分享功能

English：

✨ Features
- Multiple tabs and sessions
- Session export to Markdown
- Windows opencode path resolution covered by unit tests

🔧 Other
- Changed the open-source license

🐛 Fixes
- Fixed icon and text alignment on Linux
- Fixed sharing

## 0.0.1 (2026-09-15)

中文：

✨ 新增功能
- 更名 OpenCode Buddy：插件 ID com.senjuobito.opencode-buddy，Java 包名 com.opencodebuddy，工具窗口 ID OpenCode
- opencode 链路升级为常驻 ai-bridge daemon + opencode serve (4096) + @opencode-ai/sdk v2 + SSE 事件流，替代每消息 spawn CLI
- 会话历史改为本地索引 + daemon session.messages API
- 新增 /revert、/fork、compact、MCP 状态与 question 审批
- @opencode-ai/sdk 随插件打包，无需按需安装

🔧 其他
- 移除全部多引擎支持（Claude Agent SDK / Codex / Grok / Kimi / Pi / OMP / DSH / cc-switch）
- 设置目录由 ~/.codemoss 迁移到 ~/.opencodebuddy

English：

✨ Features
- Renamed to OpenCode Buddy: plugin id com.senjuobito.opencode-buddy, Java package com.opencodebuddy, tool window id OpenCode
- The opencode pipeline now runs a persistent ai-bridge daemon + opencode serve (4096) + @opencode-ai/sdk v2 + SSE event stream instead of spawning the CLI per message
- Session history now uses a local index + the daemon session.messages API
- Added /revert, /fork, compact, MCP status, and question approval
- @opencode-ai/sdk ships with the plugin, so it no longer has to be installed on demand

🔧 Other
- Removed every non-opencode engine (Claude Agent SDK / Codex / Grok / Kimi / Pi / OMP / DSH / cc-switch)
- Moved settings from ~/.codemoss to ~/.opencodebuddy
