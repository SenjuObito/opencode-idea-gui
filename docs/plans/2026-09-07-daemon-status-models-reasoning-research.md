# 调研笔记：服务启动卡住 / 模型列表 / 推理力度（2026-09-07）

> 状态：**调研进行中**（Plan 阶段，未实施任何修改）。
> 背景：插件启动后一直显示「正在启动 OpenCode 服务，请稍后」；模型列表获取不对且未按供应商分组；推理力度需根据所选模型动态加载。参考项目：`I:\source\repos\opencode-vscode-plugin`。

---

## 1. 「正在启动 OpenCode 服务，请稍后」卡住的根因分析

### 1.1 前端显示条件（opencode-idea-gui）

- 文案定义：`webview/src/i18n/locales/zh.json:84` → `"chat.daemonStatusLoading": "正在启动 OpenCode 服务，请稍后"`
- 展示组件：`webview/src/components/ChatInputBox/ChatInputBoxHeader.tsx:56-65`
  - 显示条件：`(!daemonStatusLoaded || !daemonAlive)`
  - `!daemonStatusLoaded` → 显示 loading 转圈（即本问题现象）
  - `daemonStatusLoaded && !daemonAlive` → 显示「未运行」+ 重试按钮
- 状态来源 hook：`webview/src/hooks/providers/useUsageTracking.ts:13-37`
  - 监听 DOM 事件 `updateDaemonStatus`（CustomEvent）
  - 关键逻辑（第 25-29 行）：**只有当 `data.alive === false` 或 `data.serveReady === true` 时才把 `daemonStatusLoaded` 置为 true**；`alive=true && serveReady=false` 时保持 loading 转圈——即必须等到 serve 真正就绪
  - 重试动作：`retryDaemonStatus()` → `window.sendToJava('check_daemon_status:')`（第 55 行）
- 桥接适配：`webview/src/main.tsx:486-496` 把宿主的 `window.updateDaemonStatus(json)` 函数调用转成 CustomEvent 给 hook

### 1.2 已确认的关键断点（疑似根因）

**在 opencode-idea-gui 的 Java 宿主端（`src/main/java`）里完全搜不到 `check_daemon_status` 和 `updateDaemonStatus`：**

- `grep -rn "check_daemon_status" src/main/java` → **0 结果**
- `grep -rn "updateDaemonStatus" src/main/java` → **0 结果**
- `MessageDispatcher` 注册的所有 handler 的 `SUPPORTED_TYPES` 中均无 `check_daemon_status`（查了 `WindowEventHandler.java:17-25` 等）

**对比参考项目 opencode-vscode-plugin（host 是 TypeScript）：**

- `src/host/handlers/WindowEventHandler.ts:721-758` `sendDaemonStatus()`：先发 `{alive, serveReady:false}`，随后触发 `opencode.preconnect`（确保 `opencode serve` 拉起），**preconnect 完成后才把 `serveReady` 翻成 `true` 推送**；失败则进入可重试状态
- `src/extension.ts:304-340` warmup：activation 时 `daemon.start()` + 并行 `opencode.preconnect` + 模型缓存预热

**结论（初步）：** webview 的 loading 判断逻辑是从 vscode-plugin 移植过来的（等待 `serveReady:true`），但 Java 宿主端**没有实现** `check_daemon_status` 处理器，也**没有任何地方**调用 webview 的 `updateDaemonStatus` 推送 `{alive, serveReady}` 状态 → webview 永远收不到事件 → `daemonStatusLoaded` 永远 false → 永久显示「正在启动 OpenCode 服务，请稍后」。即使 ai-bridge 守护进程实际已成功拉起 `opencode serve`，前端也无从得知。

### 1.4 根因确认（补充验证后定论）

- `ChatWindowDelegate.handleFrontendReady()`（`src/main/java/com/opencodebuddy/ui/ChatWindowDelegate.java:480-512`）：frontend_ready 时推送了权限模式、会话状态、tab 状态等，但**没有任何 daemon/serve 状态推送**——确认 Java 宿主端整条链路缺失。
- Java 侧 preconnect 能力本身存在：`OpenCodeSDKBridge.preconnect()`（`provider/opencode/OpenCodeSDKBridge.java:239-243`，向 daemon 发 `opencode.preconnect`），且 `SessionLifecycleManager.java:267, 436` 在会话创建时会调用它拉起 serve。**即后端其实有能力启动 serve，只是从不把状态告知前端。**

**最终定论：** webview 的 loading 判断（必须收到 `serveReady:true` 才消失）移植自 vscode-plugin，但 Java 宿主端既没有 `check_daemon_status` 处理器、`frontend_ready` 时也不推送 `updateDaemonStatus` → 前端永远收不到 `{alive, serveReady}` → 永久显示「正在启动 OpenCode 服务，请稍后」。用户看到的"daemon 拉不起来"实际是**状态推送缺失**，daemon 本身多半已正常拉起（发送消息时 preconnect 会被触发）。

### 1.5 idea-gui 模型列表现状（Java 侧走的是另一条路）

`src/main/java/com/opencodebuddy/handler/CliModelsHandler.java`（1-100 行已读）：
- 处理 `get_cli_models` 消息，但**不走 daemon 的 `opencode.getModels`**，而是每次 spawn 一个独立 Node 进程运行 `channel-manager.js <provider> listModels`（50s 超时、64K stdout 上限），与 vscode-plugin 的 daemon+SDK+缓存链路完全不同
- 需要确认 `channel-manager.js listModels` 返回的模型条目是否带 `provider/model` 格式 id 和 `variants` 字段（这决定分组与推理力度是否能直接生效）

webview 侧分组/推理文件**全部同源存在**：`modelSelectUtils.ts`、`ModelSelect.tsx`、`ReasoningSelect.tsx`、`reasoningUtils.ts`、`types.ts`、`ButtonArea.tsx`（含 `getAvailableReasoningLevels` 引用）——即前端能力齐备，问题只可能出在数据 shape（Java 推给 `setCliModels` 的内容）上。

### 1.6 推理力度现状（已确认）

- **variant 映射已存在**：idea-gui 的 `ai-bridge/services/opencode/opencode-daemon-service.js:599-665` 已实现 `reasoningEffort` → opencode `variant` 参数（注释、代码与 vscode-plugin 完全一致），随 `session.promptAsync` / `session.command` 发送。
- **webview 接线已存在**：`ButtonArea.tsx:146-150, 303` 已从 `selectedModelInfo?.variants` 计算 `getAvailableReasoningLevels` 并传给 `ReasoningSelect`。
- Java 侧 `reasoningEffort` 状态文件也齐备（`SessionState.java`、`SettingsHandler.java`、`SessionSendService.java`、`ModelProviderHandler.java`）。
- **结论：推理力度整条链路已在代码层就绪，唯一缺口同模型列表——`variants` 数据能否随模型目录到达前端。**

### 1.7 模型列表链路完整梳理（已确认）

数据流：`webview useCliModels` → bridge `get_cli_models` → Java `CliModelsHandler`（每次 spawn 独立 Node 进程跑 `channel-manager.js opencode listModels`，50s 超时）→ `channel-manager.js` → `channels/opencode-channel.js listModels` → `services/opencode/models-service.js listModels()` →（优先）daemon 内 SDK `config.providers()` 拉 `provider/models` + `variants` → stdout 单行 JSON → Java `extractJsonObject()` 取最后一行 → `window.setCliModels(payload)` → webview `normalizeModels()` 保留 `id/label/description/variants`。

- **`variants` 已透传**：`models-service.js:94-102` 构建 `{id:'provider/model', label, description, variants}`，webview `normalizeModels`（`useCliModels.ts:45-67`）保留 variants。分组渲染（`modelSelectUtils.ts` 按 id 的 `/` 前缀分桶）也就绪。
- **缺口 1（默认模型）**：`models-service.js` 输出**不带 `defaultModel`** 字段（`opencode-sdk-client.js:450-452` 取到了 `data.defaults` 存到 `p._defaults` 但没有暴露）；而 webview 侧两处都已支持：`useCliModels.ts:115` 的 handler 会读 `defaultModel`，`ButtonArea.tsx:192` 的自动纠正已实现 `cliDefaultModel ?? availableModels[0].id`（已核实，**前端无需改动**）→ 只需 bridge 侧把 `p._defaults`（`{providerID, modelID}`）拼成 `provider/model` 放进 listModels 输出即可。
- **缺口 2（无缓存）**：vscode-plugin 宿主有 `opencode.cliModelsCache` globalState 缓存 + warmup 预热；idea-gui 的 Java handler 每次 spawn 新 Node 进程冷启动（含 SDK import + serve 拉起，首次可能 10s+），且插件启动时不预热。
- **缺口 3（拉起方式绕过 daemon）**：`CliModelsHandler` 走 `channel-manager.js` 独立进程，其内部 `ensureServerReady()` → `serveManager.start(4096)` 会拉起 serve（serve-manager 有端口复用逻辑，所以不会双开），但**绕过了持久 daemon 的 `opencode.getModels` 通道**——vscode-plugin 是通过 daemon 请求 `opencode.getModels` 复用常驻进程。这也意味着模型列表加载成功与否与 daemon 状态条毫无联动。

---

## 1.8 三个问题最终定论

| 现象 | 根因 | 修复要点 |
|---|---|---|
| 永久显示「正在启动 OpenCode 服务」 | Java 宿主从不调用 `window.updateDaemonStatus` 推送 `{alive, serveReady}`，也无 `check_daemon_status` 处理器；webview 逻辑要求收到 `serveReady:true` 才解除 loading | Java 侧补状态推送链路（对齐 vscode-plugin `WindowEventHandler.sendDaemonStatus` + warmup 时序） |
| 模型列表不对/不分组 | 数据链路本身能产出 `provider/model` + `variants`（分组/推理 UI 均同源就绪）；缺陷是：无 `defaultModel`、无缓存预热、走独立进程绕过 daemon、首次加载慢、超时 15s 兜底会误报 error | 补 `defaultModel` 透传；改走 daemon `opencode.getModels`（或至少加缓存+预热）；确保 payload shape 与 vscode-plugin 一致 |
| 推理力度不动态 | 代码链路已就绪（variants → getAvailableReasoningLevels → ReasoningSelect；发送时 effort→variant），只要模型目录带 `variants` 到达前端即生效 | 验证 `config.providers()` 返回的模型确有 variants 字段即可，重点仍是保证模型目录链路可用 |

### 1.3 idea-gui 的服务启动链路（三层架构，与 vscode-plugin 同构）

```
IntelliJ Java 宿主 (DaemonBridge)
  └─ Node 守护进程 daemon.js (NDJSON over stdio)
       └─ opencode serve --port 4096 (opencode-serve-manager.js)
            └─ HTTP + SSE ← @opencode-ai/sdk v2 客户端 (opencode-sdk-client.js)
```

- `src/main/java/com/opencodebuddy/provider/common/DaemonBridge.java`
  - 维护单个长驻 Node 守护进程；Java 向 stdin 写 JSON 请求（每行一个）；daemon 生命周期事件 `type="daemon"`
  - `start()`（155 行起）阻塞等待 daemon 发出 ready 信号
  - 340-380 行：定位 `daemon.js`（`BridgeDirectoryResolver` 等），`NodeDetector.buildNodeScriptCommand(nodePath, daemonScript)` 构造命令，`ProcessBuilder` 启动
- `ai-bridge/services/opencode/opencode-serve-manager.js`（与 vscode-plugin 的同名文件，逐行基本一致）
  - `doStart(port)`（135-239 行）：`cp.spawn(binary, ['serve', '--port', String(port)])`，默认端口 4096
  - 就绪检测 `waitForReady()`（82-110 行）：`http.get` 轮询根 URL，**任何 HTTP 状态（含 404）都算就绪**；300ms 间隔、单次连接超时 2s、总预算 15s
  - Windows `.cmd/.bat` shim 需要 `shell: true`（`isWindowsCmdShim`）；二进制定位委托 `ai-bridge/utils/cli-path.js`
  - `stop()`：Windows 用 `taskkill /F /T` 杀进程树
- ai-bridge 目录结构与 vscode-plugin 的 ai-bridge 基本相同（daemon.js、services/opencode/ 下 models-service.js、opencode-daemon-service.js、opencode-sdk-client.js、opencode-serve-manager.js 等都在）

---

## 2. 模型列表获取 / 供应商分组（vscode-plugin 参考实现）

### 2.1 数据获取链

1. webview 触发：`webview/src/hooks/providers/useCliModels.ts:101` → bridge 事件 `get_cli_models`
2. 宿主 handler：`src/host/handlers/CliModelsHandler.ts:70` → `daemon.request('opencode.getModels')`；缓存优先（globalState key `opencode.cliModelsCache`）
3. daemon 分发：`ai-bridge/daemon.js:489-492` → `models-service.js listModels()`
4. 实际取数：`ai-bridge/services/opencode/models-service.js:74-106`
   - `sdk.getProviders(directory)` → `client.config.providers()`（opencode HTTP `GET /config/providers`）
   - 遍历 `providers[].models`（`Record<modelId, modelInfo>`）
   - 模型 id 格式：**`${providerId}/${modelId}`**（87 行）
   - `label` = 去掉 provider 前缀、title-case 模型名（31-43 行 `formatLabel`）
   - `variants` = `Object.keys(modelInfo.variants)` 过滤掉 `disabled`（93-101 行）→ 这就是推理力度档位
5. 兜底：`spawnSync(opencode, ['models'])` 解析 stdout 中的 `provider/model` token；仍为空则放 `opencode-default`

### 2.2 前端按供应商分组

- 分组键：id 中 `/` 前缀 → `getModelProviderGroup()`，`webview/src/components/ChatInputBox/modelSelectUtils.ts:31-35`
- 是否分组：任一模型 id 含 `/` 即启用分组（46-51 行）
- 分节构建：`buildModelDropdownSections()`，同文件 111-178 行——置顶「Pinned」区 + 按供应商前缀分桶（保持首次出现顺序）
- 渲染：`webview/src/components/ChatInputBox/selectors/ModelSelect.tsx:253-255, 400-452`，分组头 label = 供应商 key
- `ModelInfo` 形状（`types.ts:192-198`）：`{ id: "provider/model", label, description?, variants?: string[] }`

### 2.3 选中/持久化

- 持久化 id 即完整 `providerID/modelID` 字符串
- webview localStorage key `model-selection-state`（`useModelStatePersistence.ts:19`）
- 宿主 globalState keys：`lastSelectedModel` / `lastReasoningEffort` / `opencode.cliModelsCache`（`SettingsService.ts:153-183`）
- 发送时 daemon 把 `provider/model` 拆回 `{providerID, modelID}`（`opencode-daemon-service.js:139-161`）

### 2.4 idea-gui 现状（待补）

- Java 侧存在 `handler/CliModelsHandler.java`（尚需通读，确认它调用 daemon 的方式与返回 shape 是否与 vscode-plugin 一致）
- webview 侧存在 `webview/src/components/ChatInputBox/`（与 vscode-plugin 的 webview 同源移植，`modelSelectUtils.ts`、`ModelSelect.tsx`、`types.ts` 等文件均在，需确认分组逻辑是否已在 idea-gui 的 webview 中生效、或被简化掉）

---

## 3. 推理力度（Reasoning Effort）按模型动态加载（vscode-plugin 参考）

### 3.1 数据来源

- daemon 在模型条目上带 `variants: string[]`（来自 `modelInfo.variants` 的非 disabled keys，如 `['low','medium','high']`）
- webview `useCliModels.ts:59-62` 保留 `variants` 字段

### 3.2 可见档位计算

- `getAvailableReasoningLevels(provider, selectedModel, modelVariants)`，`webview/src/components/ChatInputBox/types.ts:484-513`：
  - 有 variants → 与已知档位 `['low','medium','high','xhigh','max']` 求交集（`REASONING_LEVELS` 定义 444-475 行）
  - 无 variants → 回退静态规则（claude/codex 特判、其他 provider 为 low..xhigh）
- 选中模型的 variants 接线：`ButtonArea.tsx:145-151` —— `availableModels.find(m => m.id === selectedModel)?.variants`
- 选择器显隐：`ReasoningSelect.tsx:80-82` —— **variants 为空数组 = 不支持推理 → 隐藏选择器**
- 当前值失效时自动纠正：`ReasoningSelect.tsx:88-97`（回退到倒数第二档或第一档）

### 3.3 持久化与应用

- webview：`set_reasoning_effort` bridge 事件（`useModelProviderState.ts:212-215`）；localStorage 快照含 `reasoningEffort`
- 宿主：`SettingsHandler.ts:289-307` 写 `SessionState`；校验白名单 `['low','medium','high','xhigh','max']`（`SessionState.ts:25-29`）
- **发送时映射为 opencode 的 model `variant` 参数**：`opencode-daemon-service.js:631-634`，随 `session.promptAsync` / `session.command` 一起传（`opencode-sdk-client.js:157, 222`）
- 会话恢复：读取服务端 `model.variant` 校验后回填（`HistoryHandler.ts:321-344`）

### 3.4 idea-gui 现状（待补）

- 需检查 idea-gui webview 的 `ReasoningSelect.tsx` / `types.ts` / `ButtonArea.tsx` 是否同源（大概率是移植过的），以及 Java 宿主 `SettingsHandler.java`、`SessionState`、发送参数是否带 variant/reasoningEffort

---

## 4. vscode-plugin 的 daemon / serve 启动与状态机（完整参考）

### 4.1 二进制定位（daemon 内执行，`ai-bridge/utils/cli-path.js`）

- `resolveOpenCodeCliPath()`（231-241 行）优先级：
  1. env：`OPENCODE_BIN` / `OPENCODE_PATH` / `OPENCODE_CLI_PATH`
  2. PATH 查找（Windows `where.exe`，Unix `which`）
  3. `~/.opencode/bin/{bin}`、`~/.local/bin/{bin}`、`~/.local/share/opencode/bin/{bin}`
- Windows 下依次尝试 `opencode.cmd` / `.bat` / `.exe` / 裸名（162-164 行）；`.cmd/.bat` spawn 时必须 `shell: true`（32-34 行 `isWindowsCmdShim`）
- PATH 增补：`~/.opencode/bin`、`~/.local/share/opencode/bin`、`~/.local/bin`、`~/.cargo/bin`、Windows 加 `%APPDATA%\npm`（214-229 行）

### 4.2 serve 拉起与复用

- `opencode-serve-manager.js doStart()`：
  - 先 `waitForReady(url, 1500)` 检查端口已有服务 → **复用**（用户手动起的 serve 不会被重复拉起）
  - 否则 spawn `opencode serve --port 4096`（固定端口；`OPENCODE_PORT` env 可覆盖）
  - 15s 超时；`_startPromise` 单例去重并发
- SDK 客户端：`getClient(baseUrl='http://localhost:4096')`，懒加载缓存；每目录 SSE 订阅（`client.event.subscribe`）

### 4.3 宿主端 daemon 状态机（`src/host/provider/OpenCodeDaemonBridge.ts`）

- 心跳 15s、空闲死亡 45s、活跃请求死亡 180s、自动重启最多 3 次/30s 窗口
- 状态枚举 `ACTIVE | DEATH_CLAIMED | STOPPED`
- webview 状态 payload：`DaemonStatusPayload {alive, serveReady, phase: 'starting'|'ready'|'failed', code, detail, installCmd}`（`DaemonStatus.ts:21-32`）
- 失败码：`NOT_INSTALLED | START_TIMEOUT | ... | DAEMON_DIED`，附安装提示命令

### 4.4 状态推送（idea-gui 缺失的那一环）

- `WindowEventHandler.ts:721-758 sendDaemonStatus()`：先推 `{alive, serveReady:false}` → 触发 `opencode.preconnect`（拉起 serve）→ 成功后推 `serveReady:true`；失败进入未运行/可重试态
- activation warmup：`extension.ts:304-340`，`daemon.start()` + 并行 preconnect + 模型缓存，失败只 warn 不阻塞

---

## 7. 实施计划（已定稿，待用户批准后执行）

### 改动 1：Java 宿主端补 daemon 状态推送链路（修复永久 loading）

对齐 vscode-plugin `src/host/handlers/WindowEventHandler.ts:721-758 sendDaemonStatus()` + `src/extension.ts:304-340 warmup`：

- 新建 `handler/DaemonStatusHandler.java` 处理 `check_daemon_status`（webview 重试按钮已在发、目前无人处理）：
  1. 立即推 `window.updateDaemonStatus({alive, serveReady:false})`
  2. 异步调用 `OpenCodeSDKBridge.preconnect(...)`（daemon 内拉起 `opencode serve`）
  3. 成功 → 推 `{alive:true, serveReady:true}`（loading 消失）；失败 → 推可重试态
- `ChatWindowDelegate.handleFrontendReady()`（480 行）追加一次状态推送，打开插件即自动解除 loading
- 接入 `DaemonBridge` 的事件监听机制（79 行附近），daemon 死亡时推 `{alive:false}` 显示「未运行+重试」

### 改动 2：模型列表对齐

- **2a defaultModel**：`ai-bridge/services/opencode/models-service.js listModels()` 把 `p._defaults` 拼成 `provider/model` 加进输出；前端已就绪无需改
- **2b 缓存+预热**：`CliModelsHandler.java` 先推上次缓存（IntelliJ 持久化组件存 JSON）再异步刷新；frontend_ready / preconnect 成功后预热
- **2c daemon 通道（可选）**：`CliModelsHandler` 改走 `DaemonBridge.request("opencode.getModels")`（daemon.js:481 已注册），复用常驻进程免冷启动；channel-manager 路径保留为回退

### 改动 3：推理力度（验证为主）

- 链路已就绪（variants→档位→UI→发送 effort→variant，全部文件已核实存在），依赖改动 2 让含 variants 的目录稳定到达
- 验证真实服务下 `config.providers()` 返回模型带 variants、切换模型档位跟随、不支持时选择器隐藏

### 实施顺序与测试

1 → 2a+2b → 2c（可选）→ 3 验证。测试：ai-bridge node 测试补 defaultModel 断言；webview vitest 全跑 + 新增「收到 serveReady:true 后 loading 消除」用例；Java 新增 DaemonStatusHandler 单测；`./gradlew build`；沙箱实例手工验收。

---

## 6. 关键文件索引

| 仓库 | 关注点 | 文件 |
|---|---|---|
| idea-gui | loading 文案 | `webview/src/i18n/locales/zh.json:84` |
| idea-gui | loading 条渲染 | `webview/src/components/ChatInputBox/ChatInputBoxHeader.tsx:56-79` |
| idea-gui | 状态 hook | `webview/src/hooks/providers/useUsageTracking.ts:13-57` |
| idea-gui | 函数→CustomEvent 桥 | `webview/src/main.tsx:486-496` |
| idea-gui | daemon 进程管理（Java） | `src/main/java/com/opencodebuddy/provider/common/DaemonBridge.java` |
| idea-gui | serve 拉起（Node） | `ai-bridge/services/opencode/opencode-serve-manager.js` |
| idea-gui | 模型列表 handler（Java） | `src/main/java/com/opencodebuddy/handler/CliModelsHandler.java` |
| vscode-plugin | 状态推送（核心参考） | `src/host/handlers/WindowEventHandler.ts:721-758` |
| vscode-plugin | warmup 时序 | `src/extension.ts:304-340` |
| vscode-plugin | daemon 状态机 | `src/host/provider/OpenCodeDaemonBridge.ts` |
| vscode-plugin | 状态 payload/错误码 | `src/host/provider/DaemonStatus.ts` |
| vscode-plugin | 模型列表构建+variants | `ai-bridge/services/opencode/models-service.js:74-106` |
| vscode-plugin | 供应商分组 | `webview/src/components/ChatInputBox/modelSelectUtils.ts:31-178` |
| vscode-plugin | 推理档位计算 | `webview/src/components/ChatInputBox/types.ts:444-513` |
| vscode-plugin | 推理选择器 UI | `webview/src/components/ChatInputBox/selectors/ReasoningSelect.tsx` |
| vscode-plugin | effort→variant 映射 | `ai-bridge/services/opencode/opencode-daemon-service.js:631-701` |
