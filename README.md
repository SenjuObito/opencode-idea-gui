<a id="readme-top"></a>

<div align="center">

<img src="media/icon.png" alt="OpenCode Buddy Logo" width="100" height="100" onerror="this.style.display='none'">

# OpenCode Buddy for JetBrains IDEs

<p align="center">
  <strong>Native, Full-Featured Chat GUI &amp; Workflow Assistant for OpenCode in IntelliJ IDEA / JetBrains</strong>
  <br />
  <span>Bring the OpenCode coding agent into a first-class JetBrains tool window.</span>
</p>

[![JetBrains Plugin Version](https://img.shields.io/jetbrains/plugin/v/34304-opencode-buddy?style=flat-square&color=blue&label=JetBrains%20Marketplace)](https://plugins.jetbrains.com/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg?style=flat-square)](https://opensource.org/licenses/MIT)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg?style=flat-square)](https://github.com/SenjuObito/opencode-idea-gui/pulls)
[![opencode v1 & v2](https://img.shields.io/badge/opencode-v1%20%7C%20v2-blueviolet?style=flat-square)](#-opencode-compatibility)
[![Bilingual](https://img.shields.io/badge/Language-English%20%7C%20%E7%AE%80%E4%BD%93%E4%B8%AD%E6%96%87-green?style=flat-square)](./README.zh-CN.md)

<p align="center">
  <a href="#-about-the-project">About</a> •
  <a href="#-key-features">Features</a> •
  <a href="#-opencode-compatibility">Compatibility</a> •
  <a href="#-quick-start">Quick Start</a> •
  <a href="#-usage-guide">Usage</a> •
  <a href="#-architecture">Architecture</a> •
  <a href="#-development--building">Development</a> •
  <a href="#-license">License</a>
</p>

</div>

---

<details>
  <summary>Table of Contents</summary>
  <ol>
    <li><a href="#-about-the-project">About The Project</a></li>
    <li><a href="#-key-features">Key Features</a></li>
    <li><a href="#-opencode-compatibility">OpenCode Compatibility (v1 &amp; v2)</a></li>
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
    <li><a href="#-architecture">Architecture</a></li>
    <li><a href="#-development--building">Development &amp; Building</a></li>
    <li><a href="#-contributing">Contributing</a></li>
    <li><a href="#-license--acknowledgments">License &amp; Acknowledgments</a></li>
  </ol>
</details>

---

## 🌟 About The Project

<div align="center">
  <img src="media/home.png" alt="OpenCode Buddy JetBrains Main View" width="700">
</div>

Many Java / Kotlin / Go / Python developers live inside JetBrains IDEs. Until now, using OpenCode meant
leaving the IDE for a standalone terminal — a disruptive context switch with no visual diff confirmation
for multi-file edits.

**OpenCode Buddy (JetBrains)** brings the full OpenCode agent into a native tool window:

- **Persistent daemon bridge** — keeps a resident `opencode serve` process alive through the
  `opencode-buddy-core` runtime, with pre-warming, heartbeat keep-alive, and self-healing restarts.
- **Native JCEF experience** — a modern React 19 + JCEF interface that feels at home in IntelliJ.
- **End-to-end workflow** — parallel multi-tab sessions, collapsible thinking chains, line-by-line diff
  review, a live token-usage dashboard, and the MCP marketplace.

---

## 🚀 Key Features

* ⚡ **High-performance daemon bridge** — integrates the `opencode-buddy-core` runtime to avoid the latency of spawning the CLI per message, with connection reuse and multi-session isolation.
* 🗂️ **Multi-tab & multi-session** — open multiple independent session tabs inside the tool window; rename them, show live status, or detach them into standalone floating windows.
* 💬 **Streaming chat & visible reasoning** — SSE-powered streaming output, collapsible Thinking deltas, structured tool-call cards, and real-time syntax highlighting.
* 📎 **Rich project context & fast input**:
  * `@` triggers fuzzy search over project files and attaches them automatically;
  * drag-and-drop images or paste directly from the clipboard for multimodal input;
  * native slash commands such as `/init` and `/review`, plus `!shell` quick console execution.
* 🛡️ **Interactive approvals & decision flows** — permission cards for tool execution and file edits (Once / Always / Reject), agent question forms, and plan confirmation.
* 📊 **TokenTracker usage dashboard** — live per-turn token percentage and cost estimate, with multi-dimensional usage statistics and charts.
* 🧩 **MCP marketplace & Skills ecosystem** — one-click install / update / uninstall of MCP servers, plus scanning and management of workspace and global Skills.
* 🎨 **Deep IDE integration** — automatically follows the JetBrains light/dark theme with IDE editor font sync and adjustable font size.

---

## 🔌 OpenCode Compatibility

OpenCode Buddy supports **both OpenCode v1 and OpenCode v2** and is designed to be resilient across the
protocol change between the two generations.

| Capability | OpenCode v1 | OpenCode v2 |
| :--- | :--- | :--- |
| **Transport** | `@opencode-ai/sdk` RPC client | HTTP REST (OpenAPI 3.1) + SSE |
| **Version detection** | Fallback when `/api/info` is absent | `GET /api/info` returns HTTP 200 |
| **Sessions** | `{ title, directory }` | `{ title, location: { directory } }` |
| **Messages** | `role` field | `type` field, ascending time sort |
| **Prompts & attachments** | `parts` array | `files: [{ uri, name }]` |
| **Questions** | `question.asked` events | Form schema (`fields` / `field.key`) |
| **Permissions** | `permission.updated` | `permission.asked` with `decision` reply |
| **Tool lifecycle** | single tool event | `tool.input.started` → `tool.called` → `tool.success` |

How the plugin handles the difference:

- On startup the daemon probes the server with `GET /api/info`. An HTTP 200 response locks the runtime
  into **v2** mode; a 404 or no response falls back to **v1**.
- Requests, event normalization, history loading, permission/form replies, and tool-lifecycle handling
  are all routed through a version-aware layer, so the same UI works on either server generation.
- Version state is reported as `pending | v1 | v2 | unknown`, so an undetermined server is never silently
  treated as v1.

> **Tip:** run `opencode --version` to check which CLI generation you have installed. Both are supported.

---

## ⚡ Quick Start

### Prerequisites

1. **JetBrains IDE** — version **2023.3+** with JCEF support (IntelliJ IDEA, PyCharm, WebStorm, GoLand, Rider, etc.).
2. **OpenCode CLI** — installed and available on your system `PATH` (`opencode auth login` to configure credentials). Both v1 and v2 are supported.
3. **Node.js** — version **>= 18.0.0** (runtime for the bundled `opencode-buddy-core` daemon).

### Installation

* **Option 1 (JetBrains Marketplace)** — in `Settings → Plugins → Marketplace`, search for `OpenCode Buddy` and click **Install**.
* **Option 2 (Offline)** — download the latest ZIP from [Releases](https://github.com/SenjuObito/opencode-idea-gui/releases) and use `Install Plugin from Disk...` in the plugin manager.

---

## 💡 Usage Guide

### 1. Open the Chat Window

After installation, click the **OpenCode** icon in the right toolbar to open the tool window, or use
`View → Tool Windows → OpenCode`. If a local `opencode serve` is already running it is reused;
otherwise the plugin starts the resident service automatically.

### 2. Interaction Matrix

| Module | How to use |
|---|---|
| **Multi-tab sessions** | Click `+` in the top bar to start an independent session; right-click a tab to detach it into a floating window |
| **File references** | Type `@` in the input box to fuzzy-search project files, use arrow keys and press Enter to insert |
| **Slash commands & terminal** | Type `/` for preset commands, or prefix `!` / `$` to run shell commands in the current project context |
| **Multimodal images** | Paste a screenshot with `Ctrl+V` / `⌘+V`, or drag an image file directly into the input box |
| **Archive & export** | Export the conversation as Markdown, or generate an OpenCode web share link from the top bar |

### 3. Settings Center

Click the ⚙️ gear icon in the top-right corner to open settings:

<div align="center">
  <img src="media/settings.png" alt="JetBrains Settings Page" width="550">
</div>

- **Appearance & behavior** — custom fonts, theme colors, send key (`Enter` or `⌘+Enter`), and notification rules;
- **Runtime** — custom paths for Node.js and the `opencode` executable;
- **MCP & ecosystem** — browse the MCP marketplace and manage workspace Skills.

Settings are persisted under `~/.opencodebuddy`.

---

## 🏗️ Architecture

```
webview/ (React SPA, JCEF)  ⇄  Java plugin host (JBCefJSQuery + MessageDispatcher)
                            ⇄  opencode-buddy-core daemon (NDJSON over stdio, resident Node process)
                            ⇄  opencode serve (HTTP + SSE, v1 / v2)
```

- The webview talks to the host via `sendToJava("type:content")`; the host replies by calling back
  `window[fn](...args)` inside JCEF. All chat panels share the same message pipeline.
- `opencode-buddy-core` transports NDJSON over stdio: `{id, method, params}` requests, `{id, line}`
  streaming output, and `{type:'daemon', event}` lifecycle events. Requests are non-blocking and
  concurrent requests are isolated with `AsyncLocalStorage`.
- Session, message-history, and settings caches live under `~/.opencodebuddy`; message content is
  sourced from `opencode serve` while the plugin maintains a local session index.

Java module layout (`src/main/java/com/opencodebuddy/`):

```
action/         editor, project view, console, VCS (commit message) and chat actions
bridge/         Node/opencode detection, daemon lifecycle, NDJSON client
handler/        one handler per webview message type (settings, provider, permission, history…)
permission/     permission / question approval services and tool interception
session/        session and message conversion, revert / fork / compact
provider/       opencode provider wiring and model catalog
mcp/ skill/     MCP servers and Skills
ui/             tool window, tabs, detached windows, JCEF initialization
notifications/  status-bar widget and bubble notifications
settings/       plugin settings persistence (~/.opencodebuddy)
util/ utils/    fonts, i18n, token usage, platform helpers
```

---

## 🛠️ Development & Building

The repository has three parts, each with its own dependencies:

| Part | Role | Install | Build |
|---|---|---|---|
| `src/main/java/` | JetBrains plugin host (JCEF bridge, handlers, actions, tool window) | — | `./gradlew compileJava` |
| `webview/` | React 19 + Vite + Tailwind CSS + antd chat UI | `cd webview && npm ci` | `cd webview && npm run build` |
| `opencode-buddy-core/` | Resident daemon: `opencode serve` + HTTP/SSE (packaged as `ai-bridge.zip`) | fetched/built by Gradle | see [opencode-buddy-core](https://github.com/SenjuObito/opencode-buddy-core) |

The Gradle build automatically runs the npm install when `node_modules` is missing, so a clean checkout
can build directly with `./gradlew buildPlugin`.

```bash
# 1. Clone the repository
git clone https://github.com/SenjuObito/opencode-idea-gui.git
cd opencode-idea-gui

# 2. Build the webview frontend
cd webview && npm ci && npm run build && cd ..

# 3. Run the plugin in a sandbox IDE
./gradlew runIde

# 4. Build the distributable plugin ZIP
./gradlew buildPlugin
# output: build/distributions/
```

### Gradle commands

| Task | Command |
|---|---|
| Run the plugin in a sandbox IDE | `./gradlew runIde` |
| Package a release ZIP | `./gradlew buildPlugin` (artifacts in `build/distributions/`) |
| Java unit tests | `./gradlew test` |
| Compatibility verification against a real IDE | `./gradlew verifyPlugin` |
| Regenerate `plugin.xml` | `./gradlew patchPluginXml` |
| Skip the webview build | append `-PskipWebview=true` |
| Build against another IDE (PyCharm / Rider) | append `-PtargetIde=PC`, `-PtargetIde=PY`, or `-PtargetIde=RD` |

### webview commands

| Task | Command |
|---|---|
| Build | `cd webview && npm run build` (tsc → vite build, single-file bundle) |
| Unit tests | `cd webview && npm test` (vitest) |
| E2E tests | `cd webview && npm run test:e2e` (Playwright) |
| Type check only | `cd webview && npx tsc --noEmit` |

> Requires a JDK 21 toolchain to build the plugin (`sourceCompatibility` / `targetCompatibility` = 17).

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

- **License** — released under the **MIT License**. See [`LICENSE`](./LICENSE).
- **Special Thanks** — inspired by the upstream project [`jetbrains-cc-gui`](https://github.com/zhukunpenglinyutong/jetbrains-cc-gui) and its sibling VS Code build [`opencode-vscode-plugin`](https://github.com/SenjuObito/opencode-vscode-plugin).

<p align="right">(<a href="#readme-top">back to top ↑</a>)</p>
