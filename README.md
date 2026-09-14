<div align="center">

# OpenCode Buddy (JetBrains)

> opencode-native GUI for IntelliJ IDEA · opencode 的 JetBrains IDE 可视化插件

**English** · [简体中文](./README.zh-CN.md)

</div>

A JetBrains IDE plugin that brings the **opencode** agent into a full chat GUI.

The plugin keeps a **persistent `opencode serve` daemon** (via `@opencode-ai/sdk` v2) alive across all tabs
and conversations, managed by the `ai-bridge` daemon with prewarm, heartbeat, crash-restart and session reuse.

## Philosophy

[`jetbrains-cc-gui`](https://github.com/zhukunpenglinyutong/jetbrains-cc-gui) aims to provide a unified
experience across different AI providers. This project takes a different approach — it **reuses opencode's
capabilities without secondary development**, focusing on providing a native GUI experience for opencode.

## Development Tools

This project is primarily developed with AI assistance:

- **AI Tools**: AntiGravity (primary), Claude Code
- **AI Models**: Gemini 3.7 (primary), Deepseek-v4-flash

A Gemini Pro subscription was purchased and DeepSeek cost about ¥82.15. If you find this plugin useful,
donations are welcome — they help cover those costs.

## Features

- **Persistent opencode daemon** — no per-message process spawn. `opencode serve` (default port 4096,
  overridable with `OPENCODE_PORT`) is started or reused on demand, kept warm across requests, and restarted
  on crash.
- **Full chat surface** — streaming text, thinking deltas and tool-call cards with diffs; multi-tab
  conversations inside the tool window and a detachable window.
- **Native input** — `@file` mentions, image attachments, one-click send of the editor selection or file path,
  opencode slash commands (`/init`, `/review`, …), `!shell` commands and a context-compaction flow.
- **Agent, model & effort** — build / plan mode switching, any provider+model pair from your opencode
  configuration, and reasoning-effort variants.
- **Approvals** — permission dialogs (once / always / reject), question prompts and plan approval, all rendered
  as native panels inside the chat.
- **Sessions** — local session index with favourites and search, revert / fork / compact, history export.
- **MCP** — server status and a marketplace for installing/removing MCP servers.
- **IDE integration** — editor, project-view, editor-tab and console context-menu actions, plus *Generate Commit
  Message* in the VCS commit toolbar (runs a real `git diff` through Git4Idea).
- **Fits your IDE** — follows the IDE light/dark theme, can sync the IDE font, and ships a bilingual UI
  (English / 简体中文).

## Usage

### 1. Open the chat window

Install the plugin, then open **View → Tool Windows → OpenCode** (it lives in the right sidebar stripe). The
plugin reuses an `opencode serve` instance when one is already running and otherwise starts its own.

![Chat window](media/home.png)
*The screenshot shows the UI in Simplified Chinese.*

The chat window contains:

- **Tab bar** — one tab per conversation; tabs can be renamed, detached into their own window, or templated.
- **Message area** — streamed answer, thinking blocks and tool calls, with inline diffs.
- **Input box** — `@filename` attaches files, `!command` runs a shell command, `/command` runs an opencode
  command. `Enter` sends, `Shift+Enter` inserts a newline.
- **Bottom toolbar** — mode (`Build` / `Plan`), model selector, reasoning depth, token usage, attachments, and
  the gear icon that opens settings.

### 2. Keyboard shortcuts and context menus

| Action | Shortcut / where |
|---|---|
| Send the selected code to the chat input | `Ctrl+Alt+K` (Windows/Linux) · `⌘⌥K` (macOS), or *Send Selected Code to OpenCode Buddy Plugin* in the editor context menu |
| Ask opencode to analyse / improve the current code | `Ctrl+Shift+Q` · `⌘⇧Q`, or *Ask OpenCode…* in the editor context menu |
| Copy an AI reference for the selection | *Copy AI Reference* in the editor context menu |
| Send a file path to the chat input | *Send File Path to OpenCode Buddy* in the Project view / editor tab context menu |
| Send console output to the chat input | *Send to OpenCode Buddy* in the console context menu |
| Generate a commit message | *Generate Commit Message* action in the VCS commit toolbar |
| Hide the tool window while typing in the chat | `Shift+Esc` |
| Hide / show the tool window from the IDE | *Hide CC GUI Panel* action — bind a shortcut in **Settings → Keymap** |

### 3. Settings

All plugin settings live inside the webview: click the **gear icon** in the top-right of the chat window. The
sidebar holds **Basic Configuration** (theme, language, fonts, diff theme, chat background), **Provider
Management**, **Commands (Prompts)**, **Usage Statistics**, **MCP Servers**, **Skills**, **Other Settings** and
**Sponsor**. Persistent state is stored under `~/.opencodebuddy`.

![Settings page](media/settings.png)
*Settings → Basic Configuration → Appearance; UI labels are in Simplified Chinese.*

## Requirements

- A JetBrains IDE **2023.3+** with JCEF support (IntelliJ IDEA, PyCharm, Rider …).
- **Node.js 18+** — the `ai-bridge` daemon runs on the Node runtime detected on your machine.
- The **opencode CLI** installed and configured (`opencode auth login`). Credentials and model configuration stay
  in the opencode CLI — the plugin never proxies them to a cloud service of its own.

## Development

The repository has three parts, each with its own dependencies:

| Part | Role | Install | Build |
|---|---|---|---|
| `src/main/java/` | JetBrains plugin host (JCEF bridge, handlers, actions, tool window) | — | `./gradlew compileJava` |
| `webview/` | React 19 + Vite + Tailwind + antd chat UI (ported from cc-gui) | `cd webview && npm ci` | `cd webview && npm run build` |
| `ai-bridge/` | Persistent daemon: `opencode serve` + `@opencode-ai/sdk` | `cd ai-bridge && npm ci` | ESM, run directly — no bundle step |

Both npm installs run automatically as part of the Gradle build when `node_modules` is missing, so
`./gradlew buildPlugin` works from a fresh checkout on its own.

### Commands (Gradle)

| Task | Command |
|---|---|
| Run a sandbox IDE with the plugin | `./gradlew runIde` |
| Build the distributable zip | `./gradlew buildPlugin` (→ `build/distributions/`) |
| Java unit tests | `./gradlew test` |
| Verify against a real IDE build | `./gradlew verifyPlugin` |
| Regenerate `plugin.xml` (compat range + change notes) | `./gradlew patchPluginXml` |
| Skip the webview build | add `-PskipWebview=true` |
| Build for another IDE (PyCharm / Rider) | add `-PtargetIde=PC`, `-PtargetIde=PY` or `-PtargetIde=RD` |

### Commands (webview)

| Task | Command |
|---|---|
| Build | `cd webview && npm run build` (tsc → vite build, single-file bundle) |
| Unit tests | `cd webview && npm test` (vitest) |
| E2E tests | `cd webview && npm run test:e2e` (Playwright) |
| Type-check only | `cd webview && npx tsc --noEmit` |

## Architecture

```
webview/ (React SPA, JCEF)  ⇄  Java plugin host (JBCefJSQuery + MessageDispatcher)
                            ⇄  ai-bridge/daemon.js (NDJSON over stdio, persistent Node process)
                            ⇄  opencode serve --port 4096 (@opencode-ai/sdk v2) + event.subscribe SSE
```

- The webview talks to the host with `sendToJava("type:content")`; the host replies by calling
  `window[fn](...args)` inside JCEF. All chat panels share one message dispatch path.
- `ai-bridge/daemon.js` speaks NDJSON over stdio: `{id, method, params}` requests, `{id, line}` streaming
  output, `{type:'daemon', event}` lifecycle events. Requests are non-blocking and concurrent requests are
  isolated with `AsyncLocalStorage`.
- Sessions, message history and the settings cache live under `~/.opencodebuddy`; the plugin keeps a local
  session index because `opencode serve` is the source of truth for messages.

Java module layout (`src/main/java/com/opencodebuddy/`):

```
action/        editor, project view, console, VCS (commit message) and chat actions
bridge/        Node/opencode detection, ai-bridge daemon lifecycle, NDJSON client
handler/       one handler per webview message type (settings, provider, permissions, …)
permission/    permission / question approval services and tool interception
session/       session + message conversion, revert / fork / compaction
provider/      opencode provider wiring and model catalogue
mcp/ skill/    MCP servers, skills
ui/            tool window, tabs, detached window, JCEF initialisation
notifications/ status-bar widget and balloon notifications
settings/      persisted plugin settings (~/.opencodebuddy)
util/ utils/   fonts, i18n, token usage, platform helpers
```

## Documentation

Design notes live in [`docs/`](./docs) (Simplified Chinese):

| Document | Covers |
|---|---|
| [input-commands-and-mentions.md](./docs/input-commands-and-mentions.md) | `@file` mentions, `!shell`, `/opencode` commands |
| [file-attachment-and-message-flow.md](./docs/file-attachment-and-message-flow.md) | Attachments and the message pipeline |
| [permission-and-question-flow.md](./docs/permission-and-question-flow.md) | Permission / question approval flows |
| [session-compaction.md](./docs/session-compaction.md) · [undo-redo-and-fork-flow.md](./docs/undo-redo-and-fork-flow.md) | Compact, revert, fork |
| [version-and-changelog-strategy.md](./docs/version-and-changelog-strategy.md) | Changelog fetching, cache and fallback |
| [token-consumption-indicator.md](./docs/token-consumption-indicator.md) · [font-system-and-ui-alignment.md](./docs/font-system-and-ui-alignment.md) | Usage indicator, fonts and theme sync |

## Support

If you find this useful, consider supporting:

| WeChat | Alipay | PayPal |
|:---:|:---:|:---:|
| ![WeChat](webview/src/assets/images/wallet.png) | ![Alipay](webview/src/assets/images/wallet-alipay.png) | <img src="webview/src/assets/images/wallet-paypal.png" width="600" /> |

## Acknowledgements

Thanks to the original project [`jetbrains-cc-gui`](https://github.com/zhukunpenglinyutong/jetbrains-cc-gui) —
please give it a star and consider supporting the original author — and to the sibling VS Code port
[`opencode-vscode-plugin`](https://github.com/SenjuObito/opencode-vscode-plugin).

## Sponsor

If this project helps you, consider sponsoring to support ongoing maintenance~

[View sponsors list →](SPONSORS.md)

## License

[MIT](./LICENSE)
