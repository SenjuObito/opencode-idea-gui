<div align="center">

# OpenCode Buddy (JetBrains)

> opencode 的 JetBrains IDE 可视化插件 · opencode-native GUI for IntelliJ IDEA

[English](./README.md) · **简体中文**

</div>

一个把 **opencode** agent 带入完整聊天 GUI 的 JetBrains IDE 插件。

本插件维护一个**持久化的 `opencode serve` 守护进程**（通过 `@opencode-ai/sdk` v2），跨所有标签页和对话复用，由 `ai-bridge` 守护进程管理，支持预热、心跳、崩溃
重启和会话复用。

## 项目理念

[`jetbrains-cc-gui`](https://github.com/zhukunpenglinyutong/jetbrains-cc-gui) 的开发方向是兼容各个 AI 的差异，
在插件中提供统一的体验。这与本项目的思路不同——本项目旨在**复用 opencode 的能力，不做二次开发**，专注于为
opencode 提供原生的 GUI 体验。

## 开发工具

本项目主要使用 AI 辅助开发：

- **AI 工具**：AntiGravity（主要）、Claude Code
- **AI 模型**：Gemini3.7（主要）、Deepseek-v4-flash

Gemini购买了Pro套餐，DeepSeek花费约￥82.15。
希望大家觉得好用可以赞赏，能够回收我的成本。

## 功能特性

- **多标签与多会话管理** —— 支持在 IDE 工具窗口内打开多个独立的对话标签页，标签页支持重命名、一键新建、关闭、脱离为独立浮动窗口，并具备实时的“思考/回答中”状态指示。
- **持久化 opencode 守护进程** —— 无需为每条消息重新生成进程。后台常驻 `opencode serve`（默认端口 4096，可用 `OPENCODE_PORT` 覆盖），通过 `@opencode-ai/sdk` v2 维持连接，支持心跳保活、进程预热与崩溃自愈重启。
- **沉浸式完整聊天交互** —— SSE 流式文本回答、深度思考增量（Thinking 折叠/展开）、带代码 Diff 对比的工具调用卡片，支持随时中断生成、历史消息重新编辑与撤销重试。
- **原生输入与上下文感知** —— 支持 `@文件名` 智能补全与引用、图片等多模态附件拖拽/粘贴、opencode 原生斜杠命令（`/init`、`/review` 等）自动补全、`!shell` / `$` 命令行快捷执行。
- **Agent / 模型 / 推理力度** —— `Build` / `Plan` 双模式一键切换，实时同步本地 opencode CLI 配置的全部 Provider + Model 组合，支持自定义模型配置，以及推理力度（Reasoning Effort / Variant）与 Always Thinking 开关。
- **交互式审批与提问流** —— 工具执行与文件修改权限审批（Once 单次 / Always 始终 / Reject 拒绝）、Agent 主动提问交互（Ask User Question 表单交互）和计划审批（Plan Approval）面板。
- **会话管理、搜索与 Markdown 导出** —— 本地会话历史索引与检索、会话全文搜索（`Ctrl+F` / `⌘F`）、历史回退（Revert）、会话分支（Fork）、上下文压缩（Compact）、会话一键导出为标准 Markdown 文档，以及 OpenCode 会话分享链接（Share）生成与管理。
- **Token 用量统计与可视化看板** —— 实时 Token 消耗指示器与模型上下文窗口占用百分比，点击可展开上下文用量分解详情，内置多维度 TokenTracker 统计看板（包含调用次数、消耗图表与费用预估）。
- **MCP 市场与技能生态** —— MCP 服务器状态监控、工具列表查看，支持通过内置 MCP 市场（Marketplace）一键搜索、安装、卸载与配置 MCP 服务器，支持本地与托管 Skills 技能扫描管理。
- **深度 IDE 融合与个性化** —— 界面深色/浅色主题自适应跟随 IDE，自动同步 IDE 编辑器与界面字体（支持自定义字体与字号），底部状态栏小组件实时提示任务状态，智能感知终端与控制台输出，界面支持中英双语无缝切换。

## 使用教程

### 1. 打开聊天窗口

安装插件后，通过 **View → Tool Windows → OpenCode** 打开（默认停靠在右侧边栏）。若本机已有 `opencode serve` 在运行则直接复用，否则插件会自动启动常驻服务。

<img src="media/home.png" width="600" alt="聊天主界面截图">

聊天窗口包含：

- **顶部标签栏** —— 支持多标签页并发对话，每个标签页维护独立会话，可双击/点击重命名，支持脱离为独立窗口或保存为模板。
- **顶部操作栏** —— 包含返回、新建对话、新建标签页、会话内搜索、会话分享链接、Fork 分支、导出 Markdown、历史会话列表与设置按钮。
- **消息区** —— 流式显示模型回答、思考过程折叠、工具调用详情与内联代码 Diff。
- **输入框** —— `@文件名` 模糊搜索并引用文件，`/命令` 执行 opencode 原生斜杠命令，`!命令` 快速运行 shell 指令，支持拖拽或粘贴图片附件。回车发送，`Shift+回车` 换行（可在设置中切换快捷键模式）。
- **底部工具栏** —— `Build` / `Plan` 模式切换、Provider 与模型选择、推理力度（Reasoning Effort）、Token 用量指示器（点击查看上下文分解）与设置入口。

### 2. 常用操作与交互指南

| 功能 / 操作 | 说明 |
|---|---|
| **多标签会话** | 点击顶部 `+` 或工具栏新增标签页，多会话互不干扰；标签页右上角可将当前会话脱离为独立浮动窗口 |
| **文件引用与补全** | 在输入框中输入 `@` 触发项目文件模糊搜索列表，支持键盘上下键选择并回车插入引用 |
| **斜杠命令补全** | 在输入框中输入 `/` 弹出可用命令列表（如 `/init`、`/review`、`/compact` 等） |
| **终端命令快捷执行** | 在输入框中以 `!` 或 `$` 开头直接执行终端命令 |
| **附件与图片** | 支持将图片直接拖入输入框或从系统剪贴板粘贴（`Ctrl+V` / `⌘V`） |
| **会话内搜索** | 点击顶部搜索图标或在聊天面板内按 `Ctrl+F` / `⌘F` 搜索历史消息内容 |
| **导出 Markdown** | 点击顶部文档导出图标，可将完整会话（含对话、思考过程、工具调用与代码 Diff）一键导出为 `.md` 文件 |
| **会话分享 (Share)** | 点击顶部分享图标，直接生成 OpenCode Web 分享链接并一键复制 |
| **消息回退与分支** | 支持在历史消息上进行 Revert（回退到该步骤）或 Fork（从该步骤分叉出新会话） |
| **审批与提问交互** | 当 Agent 触发工具执行或提问时，界面会弹出原生审批卡片（允许/拒绝）或选项表单，确认后继续执行 |
| **Token 消耗详情** | 点击底部 Token 百分比指示器，可弹出当前会话上下文窗口占用与输入/输出 Token 分布详情 |

### 3. 个性化设置

点击聊天窗口右上角的**齿轮图标**进入设置界面。持久化配置保存在 `~/.opencodebuddy` 目录下：

- **基础配置 (Basic Configuration)**：
  - **外观**：明暗主题切换（跟随 IDE / Light / Dark）、字体同步（自动同步 IDE 字体 / 自定义字体文件与字号）、聊天背景色、气泡颜色与 Diff 高亮主题。
  - **行为**：发送快捷键（`Enter` 或 `⌘+Enter`）、文件自动打开、Diff 默认展开、新建/压缩会话确认弹窗、任务完成气泡通知、提问弹窗提示、审批超时倒计时与提示音效配置。
  - **环境**：Node.js 路径检测与自定义配置、`opencode` CLI 路径指定与检测、自定义工作目录。
- **供应商管理 (Providers)**：查看和管理已配置的 Provider 与模型列表，支持添加自定义模型参数。
- **使用统计 (Usage Statistics)**：内置 TokenTracker 数据看板，直观展示 Token 用量走势、调用频率与成本估算。
- **MCP 服务器 (MCP Servers)**：查看已连接的 MCP 服务器与工具列表，并通过 **MCP 市场 (Marketplace)** 一键安装与配置各类 MCP 插件。
- **Skills 技能管理**：扫描并管理项目工作区或全局的 Skills 扩展。
- **其他设置**：历史记录数量上限、提示词管理与缓存清理。
- **社区与赞赏**：版本更新日志（Changelog）、问题反馈与赞助支持。

<img src="media/settings.png" width="600" alt="设置页截图">

## 环境要求

- JetBrains IDE **2023.3+**，且支持 JCEF（IntelliJ IDEA、PyCharm、Rider 等）。
- **Node.js 18+** —— `ai-bridge` 守护进程运行在本机检测到的 Node 运行时上。
- 已安装并配置好 **opencode CLI**（`opencode auth login`）。凭证与模型配置都在 opencode CLI 里，插件不会经
  自己的云端服务转发。

## 开发指南

仓库包含三个部分，各有独立依赖：

| 部分 | 作用 | 安装 | 构建 |
|---|---|---|---|
| `src/main/java/` | JetBrains 插件宿主（JCEF 桥、handlers、actions、工具窗口） | — | `./gradlew compileJava` |
| `webview/` | React 19 + Vite + Tailwind + antd 聊天 UI（cc-gui 移植） | `cd webview && npm ci` | `cd webview && npm run build` |
| `ai-bridge/` | 持久化守护进程：`opencode serve` + `@opencode-ai/sdk` | `cd ai-bridge && npm ci` | ESM 直接运行，无需打包 |

两处 npm 安装都会在 `node_modules` 缺失时由 Gradle 构建自动执行，所以从干净检出直接 `./gradlew buildPlugin`
即可。

### 命令（Gradle）

| 任务 | 命令 |
|---|---|
| 在沙箱 IDE 中运行插件 | `./gradlew runIde` |
| 打包发布 zip | `./gradlew buildPlugin`（产物在 `build/distributions/`） |
| Java 单元测试 | `./gradlew test` |
| 对真实 IDE 构建做兼容性校验 | `./gradlew verifyPlugin` |
| 重新生成 `plugin.xml`（兼容区间 + 更新说明） | `./gradlew patchPluginXml` |
| 跳过 webview 构建 | 追加 `-PskipWebview=true` |
| 构建到其它 IDE（PyCharm / Rider） | 追加 `-PtargetIde=PC`、`-PtargetIde=PY` 或 `-PtargetIde=RD` |

### 命令（webview）

| 任务 | 命令 |
|---|---|
| 构建 | `cd webview && npm run build`（tsc → vite build，单文件 bundle） |
| 单元测试 | `cd webview && npm test`（vitest） |
| E2E 测试 | `cd webview && npm run test:e2e`（Playwright） |
| 仅类型检查 | `cd webview && npx tsc --noEmit` |

## 架构设计

```
webview/ (React SPA, JCEF)  ⇄  Java 插件宿主 (JBCefJSQuery + MessageDispatcher)
                            ⇄  ai-bridge/daemon.js (NDJSON over stdio, 常驻 Node 进程)
                            ⇄  opencode serve --port 4096 (@opencode-ai/sdk v2) + event.subscribe SSE
```

- webview 通过 `sendToJava("type:content")` 与宿主通信；宿主在 JCEF 内回调 `window[fn](...args)` 回复。
  所有聊天面板共享同一条消息分发链路。
- `ai-bridge/daemon.js` 通过 stdio 传输 NDJSON：`{id, method, params}` 请求、`{id, line}` 流式输出、
  `{type:'daemon', event}` 生命周期事件。请求非阻塞，并发请求用 `AsyncLocalStorage` 隔离上下文。
- 会话、消息历史与设置缓存存放在 `~/.opencodebuddy`；消息以 `opencode serve` 为准，插件侧维护本地会话索引。

Java 模块布局（`src/main/java/com/opencodebuddy/`）：

```
action/        编辑器、项目视图、控制台、VCS（提交信息）与聊天相关动作
bridge/        Node/opencode 检测、ai-bridge 守护进程生命周期、NDJSON 客户端
handler/       每个 webview 消息类型一个 handler（设置、provider、权限…）
permission/    权限 / question 审批服务与工具拦截
session/       会话与消息转换、revert / fork / 压缩
provider/      opencode provider 接线与模型目录
mcp/ skill/    MCP 服务器、技能
ui/            工具窗口、标签页、脱离窗口、JCEF 初始化
notifications/ 状态栏小组件与气泡通知
settings/      插件设置持久化（~/.opencodebuddy）
util/ utils/   字体、i18n、token 用量、平台辅助
```

## 文档

设计与实现说明在 [`docs/`](./docs)（简体中文）：

| 文档 | 内容 |
|---|---|
| [input-commands-and-mentions.md](./docs/input-commands-and-mentions.md) | `@文件` 引用、`!shell`、`/opencode` 命令 |
| [file-attachment-and-message-flow.md](./docs/file-attachment-and-message-flow.md) | 附件与消息链路 |
| [permission-and-question-flow.md](./docs/permission-and-question-flow.md) | 权限 / question 审批流程 |
| [session-compaction.md](./docs/session-compaction.md) · [undo-redo-and-fork-flow.md](./docs/undo-redo-and-fork-flow.md) | 压缩、撤销与 fork |
| [version-and-changelog-strategy.md](./docs/version-and-changelog-strategy.md) | 版本记录获取、缓存与回退 |
| [token-consumption-indicator.md](./docs/token-consumption-indicator.md) · [font-system-and-ui-alignment.md](./docs/font-system-and-ui-alignment.md) | 用量指示器、字体与主题同步 |

## 赞赏

如果使用体验不错，欢迎赞赏支持：

| 微信 | 支付宝 | PayPal |
|:---:|:---:|:---:|
| <img src="webview/src/assets/images/wallet.png" width="200" alt="微信赞赏码"> | <img src="webview/src/assets/images/wallet-alipay.png" width="200" alt="支付宝赞赏码"> | <img src="webview/src/assets/images/wallet-paypal.png" width="200" alt="PayPal"> |

## 致谢

感谢源项目 [`jetbrains-cc-gui`](https://github.com/zhukunpenglinyutong/jetbrains-cc-gui)，欢迎大家前往源项目
点 Star 和赞赏支持；以及同源的 VS Code 版本
[`opencode-vscode-plugin`](https://github.com/SenjuObito/opencode-vscode-plugin)。

## 赞助支持

如果这个项目对你有帮助，欢迎赞助支持~

[查看赞助者列表 →](SPONSORS.md)

## 许可证

[MIT](./LICENSE)
