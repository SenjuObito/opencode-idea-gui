# 子代理 v1/v2 兼容方案（`task` → `subagent`）

> 状态：**已实施**（D1–D6 + 别名链加固，见文末「实施结果」）。
> 本方案只做「识别层」加法，不动协议、不动 core、不动 Java。

## 1. 背景与事实

opencode v2 **没有移除子代理**，只是把 v1 的 `task` 工具改名为 `subagent`，并调整了入参：

| 维度 | v1 | v2 |
|---|---|---|
| 工具名 | `task` | `subagent` |
| agent 标识参数 | `subagent_type` | `agent` |
| 返回标识 | `task_id` | `sessionID`（可续聊的子会话） |
| 后台开关 | `run_in_background: true` | `background: true` |
| 权限 action | `task` | `subagent` |

证据：官方 `opencode.ai/v2/docs/tools` 的 Automation 段；本机 v2.0.32 二进制内的工具名映射
`if (e === "task") return "subagent"`，以及迁移提示原话
「The `subagent` tool takes `agent` instead of `subagent_type` and `sessionID` instead of `task_id`.」

**通道没有变**：子代理数据一直是「主会话消息流里的 `tool_use` + `tool_result`」推断。
core 里 `parentID` / `children` / `task_notification` 全部零命中，
`opencode-sdk-client.js:1067-1080` 已对 SSE 做 v1(`/event`) / v2(`/api/event`) 分叉适配。
因此本方案**不需要**任何通道改动。

## 2. 设计原则

1. **只做加法**：新增名字与字段别名，v1 的 `task` + `subagent_type` + `run_in_background` 路径行为完全不变。
2. **不引入引擎判断**：前端不判断 v1/v2，改为识别「名字并集 + 字段别名并集」，天然两版都对
   （与项目既有铁律「协议层改动必须同时兼容 v1 和 v2」一致，且避免在前端复制一份引擎状态）。
3. **消除三份硬编码**：工具名集合收敛到 `AGENT_TOOL_NAMES`；agent 类型解析收敛到新的共享 helper；
   异步判定继续收敛在 `isAsyncAgentInput`（它本来就是三处共用的中枢）。

## 3. 逐文件改动

### D1 `webview/src/utils/toolConstants.ts:25`

```ts
// Agent/subagent spawning tools (v1: task/agent, v2: subagent, Codex: spawn_agent)
export const AGENT_TOOL_NAMES = new Set(['task', 'agent', 'spawn_agent', 'subagent']);
```

加 `'subagent'` 一处即可让 `useFileChanges.ts:601`、`MessageItem.tsx:417/430`、
`ContentBlockRenderer.tsx:461` 全部生效（它们都已复用该集合）。

### D2 `webview/src/utils/subagentResult.ts` — 新增共享解析中枢

```ts
/** v1: subagent_type / agent_type；v2: agent。顺序即优先级，v2 别名放最后，v1 行为不变。 */
export function readAgentType(input: unknown): string {
  if (!input || typeof input !== 'object') return '';
  const r = input as Record<string, unknown>;
  for (const key of ['subagent_type', 'subagentType', 'agent_type', 'agentType', 'agent']) {
    const v = r[key];
    if (typeof v === 'string' && v.trim()) return v.trim();
  }
  return '';
}
```

同文件 `isAsyncAgentInput`（:52-68）增加 v2 开关：

```ts
if (record.run_in_background === true || record.runInBackground === true) return true;
if (record.background === true) return true;            // v2 subagent 后台开关
```

放在 `runInBackground` 之后、`isAsyncLaunchStatus/sText` 兜底之前，保持 `=== true` 严格判定。

### D3 `webview/src/hooks/useSubagents.ts`

- `:109` 硬编码 → `if (!isToolName(block.name, AGENT_TOOL_NAMES)) return;`（需 import `isToolName`）
- `:120-126` 的四层取值 → `const subagentType = readAgentType(input);`

### D4 `webview/src/components/toolBlocks/TaskExecutionBlock.tsx`

- `:54` `isAgentTool` → `const isAgentTool = isToolName(name, AGENT_TOOL_NAMES);`
- `:58` 解构出来的 `subagent_type: subagentType` → 改为 `const subagentType = readAgentType(input);`
  （解构里对应的那一行保留，用于屏蔽其余字段不外泄，避免动到 `rest` 的既有行为）

### D5 `webview/src/components/toolBlocks/AgentGroupBlock.tsx:46-52`

`getAgentType` 改为调用 `readAgentType(input)`（当前只读 `subagent_type` / `subagentType`）。

### D6 `webview/src/components/toolBlocks/GenericToolBlock.tsx:112`

`toolKeyMap` 增加 `'subagent': 'tools.subagent'`；
`webview/src/i18n/locales/en.json:1253` 的 `tools` 段增加 `"subagent": "Subagent"`。
项目已配置 `fallbackLng: 'en'`（`src/i18n/index.ts:36`），其余 9 个 locale 自动回退；
zh / zh-TW 可按需同步（可选，不阻塞）。

### D7 明确不做（本方案范围外）

- **core 不做工具名映射**：core 是 v1/v2 共用传输层，在里面改名要么误伤 v1 的 `task`，要么引入引擎分支；
  前端并集识别更简单、也不丢信息。
- **不接子会话详情**：v2 返回的 `sessionID` 可用于 `/api/session/{childID}/message` 拉子代理完整消息，
  属于新增能力（另立「方案 B」），本轮只恢复到「有列表条目 + 状态正确」。
- **不修 `subagentHistories` 空转**：`window.onSubagentHistoryLoaded` / `onSubagentHistoryChunk`
  在 webview 有实现但 Java 侧 0 处调用，属既有缺口，另开一轮处理。

## 4. 测试计划

基线已实测：`useSubagents.test.ts` 8 + `subagentResult.test.ts` 26 = **34/34 通过**（HEAD，改动前）。

新增用例：

| 用例 | 输入 | 期望 |
|---|---|---|
| v2 后台子代理 | `name:'subagent'`, `{agent:'explore', prompt:'…', background:true}` | 被识别；`type==='explore'`；`isAsync===true`；无终态前 `status==='running'` |
| v2 前台子代理 | 同上但无 `background` | `isAsync===false`；有 `tool_result` 即 `completed` |
| v2 分组渲染 | `MessageItem` / `ContentBlockRenderer` 收到 `subagent` tool_use | 生成 AgentGroupBlock → TaskExecutionBlock，不再退化成 GenericToolBlock |
| v1 回归 | `name:'task'`, `{subagent_type:'explore', run_in_background:true}` | 行为与改动前完全一致 |
| 别名优先级 | `{subagent_type:'a', agent:'b'}` | 取 `'a'`（`agent` 只在前面都缺时生效） |
| 异步开关 | `isAsyncAgentInput({background:true})` | `true`；`{background:'false'}` → `false`（字符串不被 truthy 误判） |

命令：

```bash
cd webview
export PATH="/c/Users/10049/.workbuddy/binaries/node/versions/22.22.2-3:$PATH"
./node_modules/.bin/vitest run src/hooks/useSubagents.test.ts src/utils/subagentResult.test.ts \
  src/components/MessageItem/MessageItem.test.tsx src/components/MessageItem/ContentBlockRenderer.test.tsx \
  src/hooks/useFileChanges.test.ts
```

## 5. 验收与回滚

- 构建：`cd webview && npm run build`（45~60s）。
- 手工：用 v2 CLI（`opencode v2.0.32`）起会话，让模型调用子代理 →
  StatusPanel 出现条目且 type 有值；后台子代理在子会话结束前保持 running；v1 CLI 同样验证一遍确认无回归。
- 回滚边界：改动为 5 个前端文件 + 1 个 en.json 键，纯识别层，无协议/无 Java/无 core 改动，
  `git revert` 即完全回到 v1-only 行为，不会残留状态。

## 6. 风险

- `input.agent` 与将来其他字段重名的可能（当前 `parseSpawnAgentMeta` 只用 `agent_id/agentId`、`agent_path/agentPath`，
  与 `agent` 不冲突；且 `agent` 排在取值链最后）。
- v2 `background` 与 v1 `run_in_background` 语义是否完全等价未做运行时实测；
  若不等价，最坏表现是「后台子代理提前 completed」，兜底逻辑（`ASYNC_LAUNCH_TEXT` / tool-use status）仍在。
- v2 的 tool part 元数据是否带 `toolUseResult`（core 侧 grep 零命中）未验证 →
  卡片上的用量/时长可能为空，**不影响列表与状态**。

## 7. 实施结果（2026-10-18）

落地：D1 `toolConstants.ts:25`；D2 `subagentResult.ts`（`readAgentType` + `background`）；
D3 `useSubagents.ts:109/120-126`；D4 `TaskExecutionBlock.tsx:54/58`（解构里顺带剔除 v2 的 `agent`，
避免它和 `readAgentType` 的结果在参数区重复渲染）；D5 `AgentGroupBlock.tsx:46-52`；
D6 `GenericToolBlock.tsx` + `en.json` 的 `tools.subagent`。
外加别名链加固：`toolInputNormalization.ts` 的 `normalizeSpawnAgentInput` 改调 `readAgentType(input) || 'default'`
（保留 spawn_agent 自己的兜底，行为不变），消除第二份别名实现。

验证：`tsc --noEmit -p tsconfig.json` 干净；webview 全量 **162 文件 / 1453 用例通过**；
新增 10 例（v2 前台/后台子代理识别、`background` 严格判定、`readAgentType` 别名优先级、
`AGENT_TOOL_NAMES` 含 v1/v2 名字）。
`tsconfig.test.json` 原有 60 条类型告警**属既有**（集中在 bridgeStartup / sessionFileLedger 等，
与本次无关），本次改动文件 0 新增告警。

## 8. 决策记录（用户 2026-10-18 裁定）

- P4 i18n：**只加 `en.json`**，不同步其余 9 语言（依赖 `fallbackLng: 'en'`）。
- P5 MCP 同名工具误判（`mcp__x__task` 被剥成 `task`）：**不动**。
- P6 跨仓同步到 `opencode-vscode-plugin`：**本轮不做**。
- P7 core 侧工具名映射：**不做**。

## 9. 遗留（不在本轮范围）

**P1 后台子代理完成态可能永久 running** —— 且经复查是 **v1/v2 共有的缺口**，不是 v2 独有：

- `useSubagents.determineStatus` 与 `TaskExecutionBlock.tsx:102-104` 的 async 完成判定只认
  ① `taskEvent`（Claude Code 的 `task_notification`，opencode 没有）或
  ② `history?.completed`（来自 `subagentHistories`）。
- `subagentHistories` **恒空**：webview 通过 `sendBridgeEvent('load_subagent_session', …)`
  向宿主请求，但 Java 侧 grep `load_subagent_session` / `loadSubagent` / `SubagentSession`
  **全部零命中** → 请求无人处理，回调永不触发。
- 后果：opencode 后台子代理（v1 `run_in_background`、v2 `background`）在拿到终态前会一直显示 running。

修法需先实测（不要凭猜放宽判定）：起 v2 serve 跑一次后台 subagent，确认「子会话结束时父会话收到什么」
（大概率是 subagent tool part 的 state 变 completed，即一个新的 tool_result）。
**不能**直接改成「有终态 result 且非 launch ack → completed」：v1 后台 task 的 ack 文本未知
（"Async agent launched" 是 Claude Code 约定），一旦 ack 识别不住就会让 v1 后台子代理提前 completed。

### 9.1 实测记录（2026-10-18，本机 opencode v2.0.32）

方法：`opencode serve --port 4300 --print-logs` 起隔离 serve → 用日志里的 password 做
Basic auth（`-u opencode:<pwd>`）+ `x-opencode-directory` → 拉 `/openapi.json`、订阅
`GET /api/event`、`POST /api/session` 建会话、`POST /api/session/{id}/prompt` 触发子代理。

**端到端未跑通**：3 次 prompt 全部 `session.step.failed` / `session.execution.failed`，
`error.type = provider.quota`，HTTP 429 `FreeUsageLimitError`（免费模型额度限流）。
但拿到了以下协议层事实：

1. **v2 事件信封没有 `properties`**：真实形状是
   `{ id, created, type, location, data, durable: { aggregateID, seq, version } }`。
   core `opencode-daemon-service.js:414-416` 的 `props = evt.properties ?? evt.data ?? {}`
   **已适配** ✓（不是缺口）。
2. **v2 事件名是 `session.*` 体系**：实测见到 `server.connected`、`session.created`、
   `session.inbox.enqueued/delivered`、`session.execution.started/failed`、
   `session.step.started/failed`、`session.instructions.updated`、`provider.quota`。
   core 的 switch 已覆盖 `session.tool.input/called/success/failed`、`session.step.ended`、
   `session.usage.updated` 等 → **工具链路（含子代理 tool_use）在 v2 下是通的** ✓。
3. **`/api/agent` 返回 `data: []`** —— 本机没有任何用户定义的 agent；而 step 事件里
   `agent: "build"`（内置）。v2 文档限定 subagent 工具「only subagent-mode agents can be
   used」→ **默认配置下 v2 很可能压根没有可调度的子代理 agent**（需用户在 `opencode.json`
   里定义 `mode: "subagent"` 的 agent）。这条与「改名」叠加，解释为什么 v2 下看不到子代理。
4. **`/openapi.json` 共 119 条路由，`todo` 零命中** —— 服务端路由层面再证 todo 已移除
   （此前证据来自文档与 SDK 类型）。
5. **`/api/session/{id}/background`（POST）** 的语义是「把该会话前台阻塞中的可后台化工具
   转入后台观察」，**不是**子代理列表/查询接口。
6. **`session.inbox.*` 是用户提示队列**，不是后台完成通知：`Session.Inbox.Info` 只允许
   `user | synthetic | compaction | move` 四种 item。排除掉一个假设。
7. **`Session.Info.parentID` 存在**（子会话可寻址），`Config.Info.subagent_depth`、
   `SessionStats.subagents` 存在 → 方案 B（子会话详情）有数据基础。

**P1 仍未拿到运行时证据**：后台子代理完成时父会话收到什么，取决于 v2 是

- 形态 A：`background` 调用**立即**返回 tool_result（launch ack），完成时另有通知事件；还是
- 形态 B：tool_result **延迟**到子会话结束才发（即 `session.tool.success` 就是终态）。

两条路径下当前前端都卡 running（async 分支不认 `hasTerminalResult`）。
保守修法（待形态确认后落地）：给 async 完成判定加第三条
`hasTerminalResult && !isAsyncLaunchText(result)` ——
对 opencode v1 **不触发**（v1 输入里没有 `run_in_background`/`background`，`isAsync` 恒 false）；
对 Claude Code 后台 agent **不触发**（ack 文本被 `isAsyncLaunchText` 排除）；
对形态 B 正确完成 ✓；**仅形态 A 且 ack 文本不是 "Async agent launched" 时会误判提前完成** ✗。
→ 必须先确认形态再合并。
