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

- **持久化 opencode 守护进程** —— 无需为每条消息生成进程。`opencode serve`（默认端口 4096，可用
  `OPENCODE_PORT` 覆盖）按需启动或复用，跨请求保持预热，崩溃后自动重启。
- **完整聊天界面** —— 流式文本、思考增量、带 diff 的工具调用卡片；工具窗口内多标签会话，支持脱离为独立窗口。
- **原生输入** —— `@文件名` 引用、图片附件、编辑器选区/文件路径一键发送、opencode 原生斜杠命令
  （`/init`、`/review` 等）、`!shell` 命令，以及上下文压缩流程。
- **Agent / 模型 / 推理力度** —— build / plan 模式切换，读取 opencode 配置里的任意 provider + model 组合，
  以及推理力度（variant）选择。
- **审批流** —— 权限审批（once / always / reject）、question 提问和 plan 计划审批，全部在聊天区内以原生面板呈现。
- **会话管理** —— 本地会话索引 + 收藏 + 搜索，revert / fork / compact，历史导出。
- **MCP** —— 服务器状态查看与市场（安装 / 移除 MCP 服务器）。
- **IDE 集成** —— 编辑器、项目视图、编辑器标签页、控制台右键菜单动作，以及 VCS 提交工具栏的
  *Generate Commit Message*（通过 Git4Idea 跑真实 `git diff`）。
- **跟随 IDE** —— 明暗主题跟随 IDE，可同步 IDE 字体，界面支持中英双语。

## 使用教程

### 1. 打开聊天窗口

安装插件后，通过 **View → Tool Windows → OpenCode** 打开（默认在右侧边栏）。若本机已有 `opencode serve`
在运行则直接复用，否则插件会自行启动一个。

<img src="media/home.png" width="400" alt="聊天主界面截图">

聊天窗口包含：

- **标签栏** —— 每个会话一个标签页，可重命名、脱离为独立窗口、存为模板。
- **消息区** —— 流式回答、思考块、工具调用与内联 diff。
- **输入框** —— `@文件名` 引用文件，`!命令` 执行 shell，`/命令` 执行 opencode 命令。回车发送，
  `Shift+回车` 换行。
- **底部工具栏** —— 模式（`Build` / `Plan`）、模型选择、推理力度、token 用量、附件，以及打开设置页的齿轮按钮。

### 2. 快捷键与右键菜单

| 操作 | 快捷键 / 位置 |
|---|---|
| 把选中的代码发送到聊天输入框 | `Ctrl+Alt+K`（Win/Linux）· `⌘⌥K`（macOS），或编辑器右键菜单 *Send Selected Code to OpenCode Buddy Plugin* |
| 让 opencode 分析 / 改进当前代码 | `Ctrl+Shift+Q` · `⌘⇧Q`，或编辑器右键菜单 *Ask OpenCode…* |
| 复制选区的 AI 引用 | 编辑器右键菜单 *复制 AI 引用* |
| 把文件路径发送到聊天输入框 | 项目视图 / 编辑器标签页右键菜单 *Send File Path to OpenCode Buddy* |
| 把控制台输出发送到聊天输入框 | 控制台右键菜单 *Send to OpenCode Buddy* |
| 生成提交信息 | VCS 提交工具栏 *Generate Commit Message* |
| 在聊天框内隐藏工具窗口 | `Shift+Esc` |
| 在 IDE 侧隐藏 / 显示工具窗口 | *Hide CC GUI Panel* 动作 —— 可在 **Settings → Keymap** 里绑定快捷键 |

### 3. 个性化设置

所有设置都在 webview 内：点击聊天窗口右上角的**齿轮图标**。左侧栏包含**基础配置**（主题、语言、字体、
diff 主题、聊天背景色）、**供应商管理**、**命令（提示词）**、**使用统计**、**MCP 服务器**、**Skills**、
**其他设置** 和 **赞赏支持**。持久化数据存放在 `~/.opencodebuddy`。

<img src="media/settings.png" width="400" alt="设置页截图">

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
