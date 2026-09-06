# 大文件拆分优先级计划

**目标：** 优先拆分“最容易下手、风险最低、收益最高”的大文件，先降低后续维护成本，再逐步处理高耦合核心文件。

**范围：** 本计划只覆盖第一批和第二批建议拆分对象，不包含 `package-lock.json`、多语言 `locales/*.json`、纯资源镜像文件等非业务核心文件。

**选择标准：**
- **容易拆**：文件内部已经存在明显职责边界，或者仓库里已经有可复用的拆分模式。
- **风险低**：不直接处于权限系统、进程生命周期、全局会话状态等最核心路径。
- **收益高**：文件体积大、变更频繁、多人协作时容易产生冲突或回归。

---

## 第一批：建议立即执行

这 4 个文件最适合作为第一轮拆分目标。

### 1. `src/main/java/com/github/claudecodegui/handler/SettingsHandler.java`

**当前判断：** 第一优先级，最值得先拆。

**为什么先拆：**
- 文件接近 2000 行，内部包含大量 `handleXxx` 分支。
- 同时承担 `mode / model / provider / usage / input history / sound` 多类职责。
- 属于典型“总控 handler 过胖”，但又不是最核心底层状态对象，适合先瘦身。

**建议拆分方向：**
- 提取 `SettingsQueryHandler`：处理 `get_mode`、`get_node_path`、`get_working_directory`、`get_streaming_enabled` 等读取逻辑。
- 提取 `SettingsMutationHandler`：处理 `set_mode`、`set_model`、`set_provider`、`set_reasoning_effort` 等写入逻辑。
- 提取 `InputHistoryHandler`：处理 `get_input_history`、`record_input_history`、`delete_input_history_item`、`clear_input_history`。
- 提取 `SoundSettingsHandler`：处理声音通知配置与试听逻辑。
- 提取 `UsagePushService`：处理 usage 刷新、context bar 刷新、前端通知。

**本轮拆分目标：**
- 保持原消息类型不变。
- `SettingsHandler` 最终只保留消息分发和少量组装逻辑。
- 把业务实现下沉到多个 helper/service 类。

**完成标准：**
- `SUPPORTED_TYPES` 不变。
- 前端无需改协议。
- 代码阅读时可以按职责快速定位。

---

### 2. `webview/src/components/settings/index.tsx`

**当前判断：** 前端里最适合先拆的大文件。

**为什么先拆：**
- 已经接入多个 section 组件，说明 UI 层级边界已经存在。
- 还保留了大量页面级状态、窗口回调、provider/agent/prompt 管理逻辑。
- 仓库中已存在 `webview/src/components/settings/hooks/`，可以直接复用现有拆分模式。

**建议拆分方向：**
- 提取 `useSettingsPageState`：集中管理 tab、alert、toast、窗口宽度、自适应折叠等页面状态。
- 提取 `useSettingsThemeSync`：处理 IDE 主题读取、应用和同步。
- 提取 `useSettingsBasicActions`：处理节点路径、工作目录、streaming、快捷键、auto-open 等基础配置事件。
- 继续下沉现有 provider/agent/prompt 相关逻辑到独立 hooks，减少页面文件的 callback 数量。
- 将弹窗编排逻辑收敛到 `SettingsDialogs` 入口层。

**本轮拆分目标：**
- `index.tsx` 只保留页面装配、section 渲染和少量顶层协调逻辑。
- 多数 `handleXxx` 下沉到 hooks。

**完成标准：**
- 页面功能和交互保持一致。
- `index.tsx` 行数显著下降。
- hooks 命名能直接反映职责。

---

### 3. `src/main/java/com/github/claudecodegui/handler/ProviderHandler.java`

**当前判断：** 后端 handler 中很适合做第二刀。

**为什么适合：**
- `switch(type)` 已经天然按功能分组。
- Claude Provider 与 Codex Provider 两套逻辑在一个类里，边界清晰。
- 导入、导出、排序、切换都可以继续拆成独立协作类。

**建议拆分方向：**
- 提取 `ClaudeProviderOperations`：管理 Claude provider 的获取、增删改、切换。
- 提取 `CodexProviderOperations`：管理 Codex provider 的获取、增删改、切换。
- 提取 `ProviderImportExportSupport`：处理预览导入、文件选择、保存导入结果。
- 提取 `ProviderOrderingService`：处理排序逻辑。

**本轮拆分目标：**
- 保持 `ProviderHandler` 作为统一消息入口。
- 将大多数 `handleXxx` 实现委托给更小的职责类。

**完成标准：**
- 消息类型不变。
- Claude/Codex 路径各自清晰，不再交错散落。

---

### 4. `webview/src/hooks/useWindowCallbacks.ts`

**当前判断：** 风险低、收益高，而且已有测试保护。

**为什么适合：**
- 文件内部已经出现很多 helper 函数，是明显的可继续提炼信号。
- 存在现成测试：`webview/src/hooks/useWindowCallbacks.test.ts`。
- 逻辑可按“消息同步 / 会话切换保护 / 初始设置拉取 / 各类窗口事件处理”拆分。

**建议拆分方向：**
- 提取 `windowCallbacks/messageSync.ts`：处理消息 identity 保留、optimistic message、streaming 内容修复。
- 提取 `windowCallbacks/sessionTransition.ts`：处理 transition guard、reset、release 等逻辑。
- 提取 `windowCallbacks/settingsBootstrap.ts`：处理初始配置请求与设置同步。
- 提取 `windowCallbacks/registerCallbacks.ts`：统一挂载到 `window` 的桥接回调。

**本轮拆分目标：**
- `useWindowCallbacks.ts` 作为总 Hook 保留，但只负责组装。
- 所有复杂数据修复逻辑移到纯函数模块，便于继续补测试。

**完成标准：**
- 现有测试全部保持通过。
- 纯函数模块可独立测试。
- Hook 主体更短、更容易审查。

---

## 第二批：建议第一批完成后执行

### 5. `src/main/java/com/github/claudecodegui/handler/FileHandler.java`

**建议原因：**
- 适合按数据来源拆分：打开文件、最近文件、文件系统扫描、活动终端/服务。
- 改动风险中低，属于结构整理型任务。

**建议拆分：**
- `OpenFileCollector`
- `RecentFileCollector`
- `FileSystemCollector`
- `RuntimeContextCollector`

---

### 6. `src/main/java/com/github/claudecodegui/handler/HistoryHandler.java`

**建议原因：**
- 可以按“加载历史 / 深搜 / 删除 / 导出 / 注入前端消息”拆分。
- 与核心状态有关，但仍低于 `ClaudeSession` 和 `PermissionService` 的风险级别。

**建议拆分：**
- `HistoryLoadService`
- `HistorySearchService`
- `HistoryExportService`
- `HistoryMessageInjector`

---

### 7. `src/main/java/com/github/claudecodegui/provider/claude/ClaudeHistoryReader.java`

**建议原因：**
- 读多写少，职责边界比较自然。
- 适合从“超大读取类”演进到“解析 + 聚合 + 索引 + 搜索”的组合结构。

**建议拆分：**
- `ClaudeHistoryParser`
- `ClaudeHistoryIndexService`
- `ClaudeUsageAggregator`
- `ClaudeHistorySearchService`

---

### 8. `webview/src/App.tsx`

**建议原因：**
- 变更频率最高，长期收益很高。
- 但它是前端根容器，状态集中，第一轮不建议直接大拆。

**建议策略：**
- 等 `settings/index.tsx` 和 `useWindowCallbacks.ts` 先瘦下来后，再回头处理。
- 优先抽离“页面模式切换”“顶层会话状态编排”“dialog orchestration”“启动初始化流程”。

---

## 暂缓处理：高风险文件

以下文件虽然也很大，但不建议作为当前第一波拆分目标。

### `src/main/java/com/github/claudecodegui/provider/claude/ClaudeSDKBridge.java`
- 涉及进程生命周期、流式输出、MCP、rewind、daemon 等高风险路径。
- 建议等外围 handler 和前端入口整理后再处理。

### `src/main/java/com/github/claudecodegui/ClaudeSession.java`
- 属于全局会话核心状态对象，牵涉面很广。
- 适合在上层调用边界稳定后，按 message store、usage、session metadata 再拆。

### `src/main/java/com/github/claudecodegui/permission/PermissionService.java`
- 涉及文件监听、并发、跨项目 dialog 协调、response 写回，风险最高。
- 先不要把它作为“练手型重构”目标。

### `src/main/java/com/github/claudecodegui/skill/SlashCommandRegistry.java`
- 虽然很大，但改动频率没有前面几个高，优先级可后置。

---

## 推荐执行顺序

1. 拆 `SettingsHandler.java`
2. 拆 `webview/src/components/settings/index.tsx`
3. 拆 `ProviderHandler.java`
4. 拆 `useWindowCallbacks.ts`
5. 拆 `FileHandler.java`
6. 拆 `HistoryHandler.java`
7. 拆 `ClaudeHistoryReader.java`
8. 最后回头拆 `App.tsx`

---

## 执行约束

每次只拆一个文件，避免多点同时改动造成回归难以定位。

每个文件都建议遵守以下节奏：
- 第一步：先提炼纯函数或 helper，不改协议。
- 第二步：再提炼 service / hook / support class。
- 第三步：最后清理入口文件，只保留编排逻辑。
- 每一步都保持行为不变，并优先补现有模式下的测试。

---

## 每次拆分的验收清单

- 原有消息类型、前后端协议、对外接口保持不变。
- 文件行数明显下降，职责边界更清楚。
- 新增类或 hook 名称能直接反映用途。
- 没有把一个大文件机械地切成多个“仍然难懂”的小文件。
- 测试能覆盖拆出的纯函数、关键分支或既有回调行为。

---

## 本计划的使用方式

如果按最低风险推进，建议先只做“第一批”中的前两个文件：

- `src/main/java/com/github/claudecodegui/handler/SettingsHandler.java`
- `webview/src/components/settings/index.tsx`

这两个文件拆完后，再重新评估 `App.tsx` 与 `ProviderHandler.java` 的剩余复杂度，继续滚动更新本计划。
