<div align="center">

# OpenCode Buddy (JetBrains)

> 为 opencode 提供的 IntelliJ IDEA 可视化插件

[English](./README.md) · **简体中文**

</div>

OpenCode Buddy 为开源 AI 编程代理 [opencode](https://opencode.ai) 提供原生 JetBrains IDE 体验。
它是对原 [jetbrains-cc-gui](https://github.com/zhukunpenglinyutong/jetbrains-cc-gui)（CC GUI）的 opencode 专属改造，
与 [opencode-vscode-plugin](https://github.com/SenjuObito/opencode-vscode-plugin)（VSCode 版）同源同构。

## 架构

```
webview (React, JCEF)  ⇄  Java 插件宿主 (JBCefJSQuery + MessageDispatcher)
                       ⇄  ai-bridge daemon (NDJSON over stdio, 常驻 Node 进程)
                       ⇄  opencode serve --port 4096 (@opencode-ai/sdk v2)
                       ⇄  event.subscribe SSE (按 directory 作用域)
```

- 插件自动启动本地 `opencode serve`（默认端口 4096，可用 `OPENCODE_PORT` 覆盖）；若已在运行则直接复用。
- 凭证与模型配置完全由本机 opencode CLI 管理，插件不做任何云端转发。
- `@opencode-ai/sdk` 随插件分发，无需按需安装。

## 功能

- 流式输出 / 思考增量 / 工具调用卡片
- @文件引用、图片附件、编辑器选区与文件路径一键发送
- Agent（build/plan）与模型选择、推理力度（variant）
- opencode 原生斜杠命令（`/init`、`/review` 等）与 `!` shell 命令
- 权限审批对话框（once / always / reject）与 question 审批
- 会话历史（本地索引 + 收藏 + 搜索）、revert / fork / compact
- MCP 服务器状态查看与市场
- 多标签聊天、脱离窗口、IDE 主题/字体同步、中英双语

## 要求

- JetBrains IDE 2023.3+（需 JCEF 支持）
- [Node.js](https://nodejs.org) 17+
- [opencode CLI](https://opencode.ai) 已安装（`OPENCODE_BIN` / 插件设置可指定路径）

## 本地开发

```bash
# 1. 构建前端
cd webview && npm install && npm run build

# 2. 安装 ai-bridge 依赖（@opencode-ai/sdk）
cd ../ai-bridge && npm install

# 3. 运行 IDE 沙箱 / 打包
cd .. && ./gradlew runIde        # 本地调试
./gradlew buildPlugin            # 产物在 build/distributions/
```

## License

MIT
