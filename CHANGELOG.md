# Changelog

## 1.0.0-opencode.1 (2026-09-06)

OpenCode Buddy — opencode 专属改造版本。

### Changed
- 插件更名 OpenCode Buddy：插件 ID com.senjuobito.opencode-buddy，Java 包名 com.opencodebuddy，工具窗口 ID OpenCode。
- 移除全部多引擎支持（Claude Agent SDK / Codex / Grok / Kimi / Pi / OMP / DSH / cc-switch 供应商管理）。
- opencode 链路升级为常驻 ai-bridge daemon + opencode serve (4096) + @opencode-ai/sdk v2 + SSE 事件流，替代每消息 spawn CLI。
- 会话历史改为本地索引 + daemon session.messages API；新增 /revert、/fork、compact、MCP 状态、question 审批。
- @opencode-ai/sdk 随插件打包，不再需要按需安装 SDK。
- 设置目录 ~/.codemoss → ~/.opencodebuddy。

