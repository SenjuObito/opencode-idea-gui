<a id="readme-top"></a>

<div align="center">

<img src="media/icon.png" alt="OpenCode Buddy Logo" width="100" height="100" onerror="this.style.display='none'">

# OpenCode Buddy for JetBrains IDEs

<p align="center">
  <strong>Native, Full-Featured Chat GUI & Workflow Assistant for OpenCode in IntelliJ IDEA / JetBrains</strong>
  <br />
  <span>专为 IntelliJ IDEA 及 JetBrains 全家桶量身定制的 OpenCode 原生可视化交互插件</span>
</p>

[![JetBrains Plugin Version](https://img.shields.io/jetbrains/plugin/v/opencode-buddy?style=flat-square&color=blue&label=JetBrains%20Marketplace)](https://plugins.jetbrains.com/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg?style=flat-square)](https://opensource.org/licenses/MIT)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg?style=flat-square)](https://github.com/SenjuObito/opencode-idea-gui/pulls)
[![Bilingual](https://img.shields.io/badge/Language-English%20%7C%20%E7%AE%80%E4%BD%93%E4%B8%AD%E6%96%87-green?style=flat-square)](./README.zh-CN.md)

<p align="center">
  <a href="#-about-the-project">About The Project</a> •
  <a href="#-key-features">Key Features</a> •
  <a href="#-quick-start">Quick Start</a> •
  <a href="#-usage-guide">Usage Guide</a> •
  <a href="#-development--building">Development</a> •
  <a href="#-contributing">Contributing</a> •
  <a href="#-license">License</a>
</p>

</div>

---

<details>
  <summary>Table of Contents / 目录导航</summary>
  <ol>
    <li><a href="#-about-the-project">About The Project</a></li>
    <li><a href="#-key-features">Key Features</a></li>
    <li>
      <a href="#-quick-start">Quick Start</a>
      <ul>
        <li><a href="#prerequisites">Prerequisites</a></li>
        <li><a href="#installation">Installation</a></li>
      </ul>
    </li>
    <li>
      <a href="#-usage-guide">Usage Guide</a>
      <ul>
        <li><a href="#1-open-the-chat-window">1. Open the Chat Window</a></li>
        <li><a href="#2-interaction-matrix">2. Interaction Matrix</a></li>
        <li><a href="#3-settings-center">3. Settings Center</a></li>
      </ul>
    </li>
    <li><a href="#-development--building">Development & Building</a></li>
    <li><a href="#-contributing">Contributing</a></li>
    <li><a href="#-license--acknowledgments">License & Acknowledgments</a></li>
  </ol>
</details>

---

## 🌟 About The Project

<div align="center">
  <img src="media/home.png" alt="OpenCode Buddy JetBrains Main View" width="700">
</div>

许多 Java / Kotlin / Go / Python 开发者在 JetBrains IDE 体系下工作，但以往想使用 OpenCode 时，必须切出 IDE 进入独立终端，割裂感强且缺乏针对多文件修改的可视化 Diff 确认。

**OpenCode Buddy (JetBrains)** 将 OpenCode 的完整能力无缝搬进了 JetBrains IDE 工具窗口：
- **常驻后台桥接（Persistent Daemon）**：通过高性能 `ai-bridge` 保持常驻 `opencode serve` 守护进程，具备自动预热、心跳保活与异常自愈机制；
- **原生 JCEF 沉浸体验**：依托现代 React 19 + JCEF 架构，打造与 IntelliJ 极致契合的界面质感；
- **全流程能力打通**：支持多 Tab 会话并行、思考链深度折叠、代码 Diff 逐行审阅、Token 实时消费看板与 MCP 市场扩展。

---

## 🚀 Key Features

* ⚡ **高性能守护进程桥接**：集成 `opencode-buddy-core` 运行时，彻底规避按消息频繁起停 CLI 的卡顿，支持连接池复用与多会话隔离。
* 🗂️ **多标签会话并行（Multi-Tab & Multi-Session）**：IDE 工具窗口内支持开启多个独立会话 Tab，可重命名、支持状态标签、并可随时脱离为主窗口独立浮动。
* 💬 **流式会话与思考链可视化**：SSE 极速流式打字效果、可折叠的高级推理（Thinking Delta）过程，结构化 Tool Call 卡片及实时语法高亮。
* 📎 **富工程上下文感知与快捷输入**：
  * `@` 键智能模糊检索当前工程文件并自动附加；
  * 图片拖拽或剪贴板直接粘贴进入多模态输入；
  * 原生支持 `/init`、`/review` 等斜杠指令及 `!shell` 快捷控制台调用。
* 🛡️ **交互式审批与决策流**：工具执行与文件改动权限拦截卡片（一次允许 / 始终允许 / 拒绝），用户问卷交互及计划确认。
* 📊 **TokenTracker 实时用量看板**：实时显示当前轮次 Token 消耗百分比及费用估算，内置多维度使用统计与消耗图表。
* 🧩 **MCP 市场与 Skills 生态**：一键安装、更新与卸载 MCP 服务器，无缝扫描并管理工作区与全局 Skills。
* 🎨 **深度 IDE 外观融合**：自动跟随 JetBrains 浅色/深色主题，支持 IDE 编辑器字体同步与自定义字号调整。

---

## ⚡ Quick Start

### Prerequisites
1. **JetBrains IDE**：版本 **2023.3+**（IntelliJ IDEA, PyCharm, WebStorm, GoLand, Rider 等带有 JCEF 支持的版本）。
2. **OpenCode CLI**：已在系统 PATH 中安装并正常运行。
3. **Node.js**：版本 >= 18.0.0（作为后端 Bridge 运行环境）。

### Installation
* **途径 1（官方插件市场）**：在 IDE 的 `Settings -> Plugins -> Marketplace` 中搜索 `OpenCode Buddy` 并点击 Install。
* **途径 2（本地离线安装）**：前往 [Releases](https://github.com/SenjuObito/opencode-idea-gui/releases) 下载最新版本的 ZIP 安装包，在 IDE 插件管理页面选择 `Install Plugin from Disk...`。

---

## 💡 Usage Guide

### 1. Open the Chat Window
安装完成后，点击右侧工具栏的 **OpenCode** 图标即可展开工具窗口，或通过菜单栏 `View → Tool Windows → OpenCode` 唤出。

### 2. Interaction Matrix

| 功能模块 | 操作说明 |
|---|---|
| **多 Tab 并发** | 点击顶栏 `+` 开启全新独立会话；右键标签可脱离为浮动窗口 |
| **文件智能引用** | 输入框中键入 `@` 触发文件模糊搜索，方向键选择回车确认 |
| **斜杠指令与终端** | 输入 `/` 调用预设命令，前缀输入 `!` 或 `$` 直接在当前项目上下文执行 Shell |
| **多模态图片** | 截图后直接在输入框按 `Ctrl+V` / `⌘+V` 或直接拖入图片文件 |
| **会话归档与导出** | 顶栏一键生成 Markdown 会话记录，或生成 OpenCode Web 分享链接 |

### 3. Settings Center
点击右上角 ⚙️ 齿轮图标进入设置：

<div align="center">
  <img src="media/settings.png" alt="JetBrains Settings Page" width="550">
</div>

- **外观与行为**：自定义字体、主题色彩、发送快捷键（`Enter` 或 `⌘+Enter`）及通知弹窗规则；
- **运行环境**：自定义 Node.js 及 `opencode` 可执行文件的探测路径；
- **MCP 与生态**：浏览 MCP 工具市场并治理工作区技能。

---

## 🛠️ Development & Building

本项目基于 Gradle 与 IntelliJ Platform Plugin SDK：

```bash
# 1. 克隆代码仓库
git clone https://github.com/SenjuObito/opencode-idea-gui.git
cd opencode-idea-gui

# 2. 编译 Webview 前端
cd webview && pnpm install && pnpm run build && cd ..

# 3. 运行本地开发沙箱调试
./gradlew runIde

# 4. 构建打包分发插件包
./gradlew buildPlugin
# 产物输出在 build/distributions/
```

---

## 🤝 Contributing

Contributions are what make the open source community such an amazing place to learn, inspire, and create.
1. Fork the Project
2. Create your Feature Branch (`git checkout -b feature/AmazingFeature`)
3. Commit your Changes (`git commit -m 'feat: Add some AmazingFeature'`)
4. Push to the Branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

---

## 📄 License & Acknowledgments

- **License**：基于 **MIT License** 开源。详情参见 [`LICENSE`](./LICENSE)。
- **Special Thanks**：感谢 upstream 项目 [`jetbrains-cc-gui`](https://github.com/zhukunpenglinyutong/jetbrains-cc-gui) 的启发与卓越实践。

<p align="right">(<a href="#readme-top">back to top ↑</a>)</p>
