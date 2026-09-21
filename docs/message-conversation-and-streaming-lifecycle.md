# OpenCode IDEA 插件：消息对话、流式生命周期与同步机制架构文档

本文档系统阐述了 **OpenCode IDEA GUI** 插件中关于**消息对话生命周期**、**流式传输与打字机调度**、**乐观用户消息防丢失与排序**、**Todo 任务状态实时同步**、**字体缩放适配** 以及 **模型目录热更新** 的完整技术设计与排查指南。

---

## 1. 核心架构与模块分工

整个消息对话系统由三大核心分层组成：
1. **IntelliJ Plugin (Java / 宿主层)**：
   - 负责与 Node AI-Bridge 进程通信，解析 SSE 协议事件；
   - 维护会话状态树 (`SessionState`) 与消息队列 (`List<Message>`)；
   - 通过 `SessionCallbackAdapter` 将流式分块 (`onContentDelta` / `onThinkingDelta`) 及全量快照 (`updateMessages`) 推送到前端 JCEF Webview。
2. **AI-Bridge (Node.js 桥接服务)**：
   - 管理 OpenCode CLI/Daemon 常驻进程；
   - 进行提示词组装、多模态附件分流与流式 Token 提取。
3. **Webview (React / 前端 UI)**：
   - 维护界面渲染状态 (`useChatComputations`, `MessagesContext`)；
   - 流式缓冲与平滑渲染调度 (`streamingCallbacks.ts`)；
   - 乐观消息暂存、回退保护与快照合并 (`messageSync.ts`)。

---

## 2. 核心功能与机制设计

### 2.1 模型列表加载与插件打开热更新
- **初始静默刷新**：在 `useCliModels.ts` 中，当插件打开并挂载 Hook 时，后台异步触发一次 `get_cli_models` 请求获取最新的 Provider 与自定义模型配置；若本地存在缓存则优先渲染旧缓存，待新模型列表返回后无感更新 `modelsCache`。
- **缓存隔离与防抖**：切换不同 Provider 时按 Provider 维度隔离缓存，避免跨 Provider 模型污染。

### 2.2 UI 字体缩放系统 (Font Scale)
- **多端与 JCEF 适配**：在 `fontScale.ts` 中，除了向 `<html>` 注入 `--font-scale` CSS 变量外，直接为 `#app` 节点赋值内联样式 `app.style.zoom = scale`。
- **自适应视口尺寸**：在 `base.less` 中，`#app` 的宽高采用 `calc(100vh / var(--font-scale, 1))` 进行反向补偿，确保在 1~6 档字体缩放下页面整体等比缩放且填满视口。

### 2.3 流式消息与防跨轮次回溯机制
- **流式开始与结束**：
  - `stream_start` 到达时，清空流式累加器并初始化占位气泡。
  - `stream_end` 到达时，如果本轮失败且未产生任何流式文本，自动清理空占位气泡，禁止在快照解析中跨轮次回溯抓取历史 Assistant 消息。
- **气泡寻址与防乱序**：
  - 采用唯一的 `__turnId` 绑定当前正在流式的 Assistant 消息，废除脆弱的数组下标索引。
  - 在 `messageSync.ts` 的 `appendOptimisticMessageIfMissing` 中，若后端快照已携带当前活跃流式回复，乐观用户提示词保证插入在该流式回复之前，确保 `[User Prompt -> Assistant Reply]` 始终严格有序。

### 2.4 状态面板任务 (Todo) 实时无感同步
- **专用 SSE 桥接通道**：
  - 后端接收到 OpenCode 的 `todo_updated` / `todo.updated` 事件后，通过 `CallbackHandler.notifyTodoUpdated` -> `SessionCallbackAdapter.onTodoUpdated` -> `window.onTodoUpdated(json)` 推送到前端。
  - 前端 `registerCallbacks.ts` 解析 JSON 并调用 `setSseTodos(todos)`，无需用户手动点击即可实时在状态面板中更新勾选状态与任务项。

### 2.5 用户消息防丢失与失败持久化
- **前置拦截记录**：在 `SessionHandler.java` 中，无论是 Node.js 版本校验失败、参数异常还是调用失败，先将 User 消息与错误描述存入 `SessionState`，再向前端推送更新，确保用户输入的提示词内容永不丢失。
- **前端乐观消息保护**：若快照未同步，前端保留乐观消息展示，避免网络波动或报错时提问从聊天界面瞬间闪退。
