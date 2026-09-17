# OpenCode 插件与服务日志路径汇总

## 1. 插件端文件日志路径

### Windows
- **IDEA 插件 (`opencode-idea-gui`)**:
  `C:\Users\<当前用户名>\.opencode-idea-gui\opencode-plugin.log`
- **IDEA 系统日志**:
  `C:\Users\<当前用户名>\AppData\Local\JetBrains\<IDE版本>\log\idea.log` (或通过 IDEA 菜单 `Help` -> `Show Log in Explorer`)

### macOS
- **IDEA 插件 (`opencode-idea-gui`)**:
  `~/Library/Logs/opencode-idea-gui/opencode-plugin.log`
- **VS Code 插件 (`opencode-vscode-plugin`)**:
  `~/Library/Logs/opencode-vscode-plugin/opencode-plugin.log`

### Linux
- **IDEA 插件 (`opencode-idea-gui`)**:
  `~/.opencode-idea-gui/opencode-plugin.log`

## 2. 后端服务端日志路径

- **OpenCode Serve 核心服务日志**:
  - macOS/Linux: `~/.local/share/opencode/log/opencode.log`
  - Windows: `%USERPROFILE%\.local\share\opencode\log\opencode.log`

## 3. 常用排查关键字

在 `opencode-plugin.log` 中：
- `[cli-path]`：查看 OpenCode 可执行文件的寻址与路径解析详情。
- `[opencode-serve-manager]`：查看服务探测、复用（Attach）及子进程拉起详情。
- `[opencode-serve-manager:stdout]` / `[opencode-serve-manager:stderr]`：查看 `opencode serve` 进程的真实输出。
- `DAEMON`：查看守护进程生命周期与异常。