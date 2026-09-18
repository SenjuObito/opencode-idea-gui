# OpenCode 服务生命周期管理、全链路日志与状态流转设计

本文档详细说明 IntelliJ IDEA 插件（`opencode-idea-gui`）中 **Node 环境探测**、**`opencode serve` 守护进程生命周期管理**、**全链路持久化日志文件落盘规范** 以及 **状态栏平滑过渡与 Toast 机制** 的设计与实现。

---

## 1. 架构与数据流向

```mermaid
flowchart TD
    A[Webview 前端 (React)] -->|cardDebugLog / 错误上报| B[Java 宿主: OpencodeBuddyChatWindow]
    B -->|PluginFileLogger 统一写入| L[(本地持久化日志: opencode-plugin.log)]
    
    C[Java 宿主: DaemonBridge] -->|启动 / 管理| D[Node.js 守护进程: daemon.js]
    D -->|环境诊断 / 启停管理| E[opencode serve]
    
    D -->|logger.js 输出结构化 stderr| C
    C -->|startStderrReaderThread & handleDaemonOutput| B
```

---

## 2. Node 运行时与服务环境诊断探针

### 2.1 环境探针（`cli-path.js`）
- `probeSystemNode()`：在后台探查系统 PATH 中的 Node.js 运行时及版本号；
- `probeCliVersion(cliPath)`：探查 `opencode` 可执行文件的具体版本号。

### 2.2 启动前环境诊断块（`opencode-serve-manager.js`）
在每次执行 `opencode serve` 子进程拉起前，会在 stderr 打印完整的诊断日志块：
- 守护进程当前使用的 Node 运行时路径（`execPath`）与版本；
- 系统 PATH 中的 Node 版本；
- `opencode` CLI 解析路径与版本；
- 当前工作目录（`cwd`）、PATH 环境变量；
- 服务端口与子进程 PID。

---

## 3. 全链路日志持久化落盘规范

### 3.1 本地日志落盘位置

所有来自 Webview、Java 宿主、以及 `ai-bridge` 的日志均会**统一持久化落盘**至：

| 平台 | 日志文件路径 |
|------|-------------|
| **macOS** | `~/Library/Logs/opencode-idea-gui/opencode-plugin.log` |
| **Linux / UOS** | `~/.opencode-idea-gui/opencode-plugin.log` |
| **Windows** | `%USERPROFILE%\.opencode-idea-gui\opencode-plugin.log` |

### 3.2 各层日志收集规范
1. **Webview 前端**：
   - 生产模式静默普通 `console.log`，必须使用 `cardDebugLog` 发送消息至宿主；
2. **Java 宿主**：
   - 使用 `PluginFileLogger.info(tag, msg)` / `error(tag, msg)`，统一格式化写入；
3. **AI-Bridge 桥接层**：
   - 使用 `ai-bridge/utils/logger.js`（`logInfo`, `logWarn`, `logError`, `logDebug`），输出至 stderr；
   - Java 宿主 `DaemonBridge.startStderrReaderThread` 与 `handleDaemonOutput` 100% 捕获并落盘。

---

## 4. 状态栏平滑过渡与 Toast 机制

1. **状态栏真实绑定**：
   - `useUsageTracking.ts` 移除历史遗留的 CLI provider 恒为 true 短路，直接反映 `daemonAlive`；
   - 在 `alive: true && serveReady: true` 时状态栏正常隐藏，启动失败时稳定展示带有重试按钮的警告条。
2. **早期错误 Toast 队列**：
   - 在 `main.tsx` 预注册 `showToast`，将 React 挂载前的早期错误暂存在 `window.__pendingToasts`，挂载后回放，防止启动崩溃报错丢失。
