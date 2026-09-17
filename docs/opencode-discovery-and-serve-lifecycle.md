# OpenCode CLI 寻址与 OpenCode Serve 进程生命周期设计

本文档系统阐述 **OpenCode IDEA GUI** 插件在各平台（Windows、macOS、Linux）下对 OpenCode CLI 的寻址发现、`opencode serve` 守护进程拉起与复用、IPv4/IPv6 双栈网络探测、子进程管道防护以及全链路日志追踪的完整技术实现。

---

## 1. 架构总览

```mermaid
flowchart TD
    A[IDEA Java 宿主] -->|启动/守护| B[Node.js ai-bridge Daemon]
    B -->|1. 优先网络探测| C{本地 4096 端口是否存在存活 Serve?}
    C -->|是: 127.0.0.1 / localhost / ::1| D[Direct Attach: 复用已有服务]
    C -->|否| E[2. 全平台智能寻址 OpenCode CLI]
    E -->|找到可执行文件| F[3. 启动子进程: opencode serve --port 4096]
    F -->|实时消费 stdout/stderr| G[动态端口捕获 & 防管道溢出阻塞]
    G -->|等待就绪| H[@opencode-ai/sdk v2 Client 绑定并订阅 SSE]
    D --> H
```

---

## 2. OpenCode CLI 智能寻址机制

CLI 寻址由 `ai-bridge/utils/cli-path.js` 统一调度，按以下优先级执行：

1. **用户自定义配置（`OPENCODE_BIN` / `OPENCODE_PATH` / `OPENCODE_CLI_PATH`）**：
   - **精确文件匹配**：若指定的是文件（如 `D:\nodejs\opencode.cmd`），直接使用。
   - **目录自动解析**：若指定的是目录（如 `D:\nodejs` 或 `~/.opencode/bin`），自动向下寻找该目录内的 `opencode.cmd`、`opencode.exe`、`opencode.bat`、`opencode`。
2. **系统 PATH 查找（`where.exe` / `which`）**：
   - 在 Windows 上执行 `where.exe opencode`，智能过滤优先选择 `.exe`、`.cmd`、`.bat` 可执行文件，避免选中无后缀 bash 脚本。
3. **版本管理器与常见目录全盘深度扫描**：
   - **NVM-windows**：动态扫描 `%APPDATA%\nvm\v*\opencode.cmd` 下的所有版本目录。
   - **FNM**：动态扫描 `%USERPROFILE%\.fnm\node-versions\*\installation\opencode.cmd`。
   - **PNPM 全局目录**：`%LOCALAPPDATA%\pnpm\opencode.cmd`（Windows）/ `~/.local/share/pnpm/`（Unix）。
   - **Scoop Shims**：`%USERPROFILE%\scoop\shims\opencode.exe`。
   - **默认用户目录**：`~/.opencode/bin/`、`~/.local/share/opencode/bin/`、`~/.local/bin/`。
4. **兜底回退**：
   - 回退至裸命令名 `"opencode"`，在 Windows 下配合 `shell: true`（通过 `cmd.exe /c`）由系统的 `PATHEXT` 解析执行。

---

## 3. `opencode serve` 服务复用与启动（双栈探测 & 动态端口）

### 3.1 优先复用（Attach）与 IPv4/IPv6 双栈探测
为避免重复拉起多个后台服务并解决 Windows 下 `localhost` 优先解析为 IPv6 `::1` 导致探测失败的问题，插件在探测已有服务时采用**全候选双栈探测**：
- 候选地址列表：
  1. `http://127.0.0.1:${port}`（IPv4 环回，Windows 最可靠）
  2. `http://localhost:${port}`（主机名解析）
  3. `http://[::1]:${port}`（IPv6 环回）
  4. 环境变量 `OPENCODE_URL`（若显式指定）
- 只要其中任意一个地址返回有效 HTTP 响应，立即**直接复用该服务**，不启动多余进程。

### 3.2 动态端口捕获与防错位
- 当 4096 端口被非 OpenCode 服务占用时，OpenCode CLI 会自动漂移分配新端口（如 4097）。
- 插件在拉起子进程时，实时解析 `stdout` 输出中的端口号（如 `Server listening at http://127.0.0.1:4097`），并把探测与 SDK Client 的 `baseUrl` 动态绑定到该实际端口上，避免在 4096 盲目死等导致超时强杀。

### 3.3 Windows Stdout 管道溢出防护
- Windows 匿名管道缓冲区较小（4KB ~ 64KB）。插件对子进程的 `stdout` 和 `stderr` 均挂载了 `data` 事件监听，实时排空并转储日志，彻底杜绝因子进程输出日志填满管道而引起的挂死现象。

---

## 4. 全链路日志定位

在排查客户端无法启动、多 Node 版本冲突或服务崩溃时，可通过以下专用日志文件进行定位：

### 4.1 日志路径

| 平台 | 日志文件路径 |
| :--- | :--- |
| **Windows** | `C:\Users\<当前用户名>\.opencode-idea-gui\opencode-plugin.log` |
| **macOS** | `~/Library/Logs/opencode-idea-gui/opencode-plugin.log` |
| **Linux** | `~/.opencode-idea-gui/opencode-plugin.log` |

### 4.2 关键日志关键字与诊断步骤

1. **查找 CLI 二进制**：
   - 过滤 `[cli-path]`
   - 检查尝试查找的 Node 路径、NVM 目录、环境变量覆盖以及最终命中的可执行文件。
2. **服务探测与复用**：
   - 过滤 `[opencode-serve-manager]`
   - 检查 `Probing existing server candidate` 是否成功，以及是否触发了 `Reusing existing server`。
3. **子进程启动与输出**：
   - 过滤 `[opencode-serve-manager:stdout]` 和 `[opencode-serve-manager:stderr]`
   - 观察 `opencode serve` 的真实启动 Banner、端口分配、Node 运行时报错及退出码。
