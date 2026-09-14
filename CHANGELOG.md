# Changelog

Each released version has one section holding a `中文：` half and an `English：`
half, so both languages ship in the bundled changelog dialog and in the GitHub
Release body. Consumers of this file:

- `webview/scripts/extract-changelog.mjs` → bundled `webview/src/version/changelog.ts`
- `tools/extract-release-notes.mjs` → GitHub Release body (emits the English half
  under an `### English` heading, which the changelog dialog splits on)

## 1.0.0-opencode.1 (2026-09-06)

中文：

OpenCode Buddy — opencode 专属改造版本。

- 插件更名 OpenCode Buddy：插件 ID com.senjuobito.opencode-buddy，Java 包名 com.opencodebuddy，工具窗口 ID OpenCode。
- 移除全部多引擎支持（Claude Agent SDK / Codex / Grok / Kimi / Pi / OMP / DSH / cc-switch 供应商管理）。
- opencode 链路升级为常驻 ai-bridge daemon + opencode serve (4096) + @opencode-ai/sdk v2 + SSE 事件流，替代每消息 spawn CLI。
- 会话历史改为本地索引 + daemon session.messages API；新增 /revert、/fork、compact、MCP 状态、question 审批。
- @opencode-ai/sdk 随插件打包，不再需要按需安装 SDK。
- 设置目录 ~/.codemoss → ~/.opencodebuddy。

English：

OpenCode Buddy — an opencode-only build of the plugin.

- Renamed to OpenCode Buddy: plugin id com.senjuobito.opencode-buddy, Java package com.opencodebuddy, tool window id OpenCode.
- Removed every non-opencode engine (Claude Agent SDK / Codex / Grok / Kimi / Pi / OMP / DSH / cc-switch provider management).
- The opencode pipeline now runs a persistent ai-bridge daemon + opencode serve (4096) + @opencode-ai/sdk v2 + SSE event stream instead of spawning the CLI per message.
- Session history now uses a local index + the daemon session.messages API; added /revert, /fork, compact, MCP status and question approval.
- @opencode-ai/sdk ships with the plugin, so the SDK no longer has to be installed on demand.
- Settings directory ~/.codemoss → ~/.opencodebuddy.
