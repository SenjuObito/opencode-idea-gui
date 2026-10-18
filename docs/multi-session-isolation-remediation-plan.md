# 多会话隔离 —— 完整修复方案（实施手册）

> 配套诊断文档：`docs/multi-session-isolation-architecture.md`（本文不重复论证，只给可执行改动）。
> 适用仓库：`opencode-buddy-core`（core）/ `opencode-idea-gui`（本仓库）/ `opencode-vscode-plugin`。
> 本文是**改前方案**，尚未落地。所有行号基于 2026-10-16 工作区状态（含 `7134f91` 补丁）。

---

## 0. 前置结论

### 0.1 一句话

会话身份（`sessionId`）必须是**跨层一等字段**：由传输层携带、逐层透传、缺失时**拒绝**而非猜测。
修复的本质是**减法**——把已有的"推断 + 兜底"删掉，换成"带上身份 + 缺身份就丢弃并计数"。

### 0.2 为什么不能继续打补丁

`7134f91`（`✨ 修复多会话各种问题`，67 文件 / +2227 −236）已核实：**身份链五处根因一处未动**——

| 根因 | 位置 | 补丁后 |
|---|---|---|
| 信封无 session | `core/daemon.js:218` `writeRawLine({ id, line })` | 未变 |
| 全局单槽 | `core/daemon.js:64,69,438` `backgroundStreamRequestId` | 未变 |
| `[STREAM_END]` 丢 sid | `OpenCodeMarkerParser.java:131-134` | 未变 |
| `PermissionRequest` 无 sessionId | `PermissionRequest.java:12-18` | 未变 |
| webview 空 id 回落 | `messageCallbacks.ts:174,433,560` | 未变 |

补丁做的是"在**已能拿到 id** 的分支上推断得更聪明"（定向 abort、per-session 序号、delta 会话一致合并、questionCard）；
其中 `useSessionTabs.ts:317-322` 甚至**新增了一条推断兜底**（活动 tab 未绑定即认领任意 id），方向与"拒绝"相反。

### 0.3 已有资产（不必从零建）

补丁顺带留下了两块正确的骨架，P2/P4 直接复用、不要重写：

- **per-session 序号屏障** `webview/src/hooks/windowCallbacks/sequenceBarrier.ts`（`per-session Map` + 全局兜底）→ P4 把兜底删掉即可。
- **回合幂等雏形** `streamingCallbacks.ts:609` `if (currentTurnId > 0 && window.__streamEndProcessedTurnId === currentTurnId) return;` → P2 把 `turnId` 换成服务端 `durable.seq` 并推广到全部终态。

### 0.4 关键工程事实：core 的打包链（改 core 后必须走对流程）

| 消费方 | 引用方式 | 改 core 后 |
|---|---|---|
| `opencode-vscode-plugin` | `node_modules/opencode-buddy-core` 是 **symlink** → `../../opencode-buddy-core` | **立即生效**，无需打包 |
| `opencode-idea-gui` | gradle `downloadAiBridgeCore`（`build.gradle:492-600`）从 sibling 目录 `npm run pack:zip` → `dist/ai-bridge.zip` → `build/core-cache/` → `packageAiBridge`（`:603-632`）→ 解压进插件 `ai-bridge/` | **必须重跑 gradle**；`daemon.js`/`package.json`/`scripts/pack-zip.mjs` 任一 `mtime` 更新会自动触发 repack（`:522-525`） |

**推论**：core 改动是跨两个插件共享的。任一阶段改 core，两个插件必须**同批次**验证（或用 `repackCore` 属性强制重打包）。

---

## 1. 杠杆点：所有 marker 汇聚在同一处（决定了改动量）

核实结论：core 的**每一个** marker 最终都经过 `process.stdout.write`，而该函数被 `daemon.js:208-245` 劫持——**这是唯一出口**。

```
utils/marker-protocol.js:6-8    emitJsonStringMarker → process.stdout.write(...)
utils/marker-protocol.js:11,16  emitSendError/emitSessionId → console.log(...)
utils/marker-protocol.js:27,36  emitMessageMarker/beginStream → console.log(...)
daemon.js:257-262               console.log 被重定向到 process.stdout.write
                                ↓
daemon.js:208-245               process.stdout.write 劫持 → writeRawLine({id, line})
```

**因此：只要 `daemon.js:212` 那一行能拿到 session，就覆盖了全部 marker——不需要去改 23 处发射点，也不需要给每个 `emitXxx` 加参数。**

这是整个方案的关键杠杆：改动集中在 **3 个函数 + 1 个信封**。

---

## 2. 不变量（验收标准，必须可测）

| ID | 不变量 | 破坏时的现象 |
|---|---|---|
| **INV-A** | 任何跨层消息**必须**携带 `sessionId`；`sessionId` 缺失的流式/弹窗消息**必须**被拒绝并计数，**不得**回落"当前会话" | 串会话 |
| **INV-B** | 一个会话在任意时刻至多一个活跃回合，由**单调递增** `turnId` 标识；`sessionId + turnId` 唯一确定一条流 | trailing delta 污染 |
| **INV-C** | 回合终态（`idle \| error \| aborted \| timeout`）是**显式、幂等、按会话**的事件；到达后**必须**把该会话的 `loading/streamingActive` 置 false，**无论该会话是否激活** | loading 不停 |
| **INV-D** | 每个会话的 UI 运行时状态只由该会话自己的状态机写；**不存在全局单槽**，也不存在"全局 ↔ 快照"搬运 | 状态随切换漂移 |

**`turnId` 不需要自造**：服务端 SSE 每帧都带 `durable:{aggregateID(=sessionID), seq}`（见诊断文档 §1.0 d），即**每会话单调递增序号**。core 透传即可。

---

## 3. 身份来源与优先级（核心数据契约）

### 3.1 session 的三个来源（按权威度）

| 级别 | 来源 | 适用 | 现状 |
|---|---|---|---|
| **L1** | SSE 事件 `data.sessionID`（服务端原生） | 流式 / 弹窗 / 终态 / todo | `opencode-daemon-service.js:404-410` 已解析；`event-normalize.js:43,103` 已入 payload |
| **L2** | turn 注册时的 `sessionID`（请求路径） | `[MESSAGE_START]`/`[SESSION_ID]`/`[STREAM_END]` | `:1110,1253 beginStream(sessionId)` 已有 |
| **L3** | 缺失 | — | **必须显式 `null`，禁止借用别的请求 id** |

> **禁止**：`daemon.js:69` 的 `?? backgroundStreamRequestId` 这种"借用一个全局 id 冒充身份"。

### 3.2 信封格式（唯一契约变更）

```jsonc
// 改前（daemon.js:218）
{ "id": "7", "line": "[CONTENT_DELTA] \"hi\"" }

// 改后
{ "id": "7", "session": "ses_abc", "line": "[CONTENT_DELTA] \"hi\"" }
{ "id": null, "session": "ses_abc", "line": "[CONTENT_DELTA] \"hi\"" }   // 无请求归属但有会话
{ "id": null, "session": null,     "line": "[CONTENT_DELTA] \"hi\"" }    // 无归属 → unattributed
```

- `id`：保留，语义收窄为**请求级命令应答**（`done`/`success`/`heartbeat`/`status`）。
- `session`：**新增**，语义为"该行内容属于哪个会话"。
- 二者**正交**：`id` 回答"谁发的"，`session` 回答"属于哪个会话"。这正是当前被压缩成一件事的两件事。

### 3.3 路由优先级（宿主）

```
1. envelope.session != null  →  sessionAdapters.get(session)   [新，优先]
2. envelope.id != null       →  context.getRequestHandler(id)   [原样保留]
3. 都不行                    →  unattributed：计数 + WARN，绝不投给"当前会话"
```

---

## 4. 阶段 P1：身份入信封（只加不改，可观测）

> **本阶段不改变任何路由行为**，只把身份写上、把"无归属"变成可计数事件。
> 目的：拿到"身份覆盖率"数据，为 P1.5 的翻转提供 gate 判据。此阶段落地后**行为应与现在完全一致**（除日志）。

### 4.1 core：`request-context.js`

```js
// 现状：仅 { id }
// 改后：约定为 { id: string|null, sessionId: string|null }
// 文件本身无需改代码，只更新注释约定
```

### 4.2 core：`services/opencode/opencode-daemon-service.js:416-421`

```js
// 改前
const runWithContext = (fn) => {
  if (turn && turn.requestId) {
    return requestContext.run({ id: turn.requestId }, fn);
  }
  return fn();                       // ← 无 turn：完全丢失身份
};

// 改后
const runWithContext = (fn) => {
  return requestContext.run(
    {
      id: turn && turn.requestId ? turn.requestId : null,
      sessionId: sessionID ?? null,   // ← SSE 事件自带，无 turn 也照带
      turnId: turn && turn.turnId ? turn.turnId : null,
      seq: props?.durable?.seq ?? null,  // P2 使用
    },
    fn,
  );
};
```

> 这一处是**单点杠杆**：`_handleEvent` 是全部 SSE 事件的入口，`sessionID` 在 `:404-410` 已经解析出来，此前却在 `:420` 无 turn 时被直接丢弃。

### 4.3 core：`daemon.js`

**（a）单槽改为双字段（`:64-70`）**

```js
// 改前
let backgroundStreamRequestId = null;
function currentRequestId() {
  return requestContext.getStore()?.id ?? backgroundStreamRequestId ?? null;
}

// 改后
let backgroundStream = null;        // { requestId, sessionId } | null
function currentContext() {
  const store = requestContext.getStore();
  if (store) return store;
  return backgroundStream ? { ...backgroundStream } : null;
}
```

**（b）turn 命令登记（`:436-439`）**

```js
// 改前
const turnCommand = isTurnCommand(method);
if (turnCommand) {
  backgroundStreamRequestId = id;
}

// 改后
const turnCommand = isTurnCommand(method);
if (turnCommand) {
  backgroundStream = {
    requestId: id,
    sessionId: typeof params.sessionId === 'string' && params.sessionId ? params.sessionId : null,
  };
}
```

**（c）stdout 劫持（`:208-223`）—— 唯一信封改动点**

```js
// 改前
const requestId = currentRequestId();
if (requestId) {
  const lines = text.split('\n');
  for (const line of lines) if (line.length > 0) writeRawLine({ id: requestId, line });
  ...
}

// 改后
const ctx = currentContext();
if (ctx && (ctx.id || ctx.sessionId)) {
  const lines = text.split('\n');
  for (const line of lines) {
    if (line.length > 0) {
      writeRawLine({ id: ctx.id ?? null, session: ctx.sessionId ?? null, line });
    }
  }
  if (typeof callback === 'function') callback();
  return true;
}
```

**（d）console.error 信封（`:279-285`）与 done/错误路径（`:301,526-538,563,580`）**
同步改用 `currentContext()`；`backgroundStream` 的清零判断由 `backgroundStreamRequestId === id` 改为 `backgroundStream?.requestId === id`。

> **注意 `process.exit` 拦截（`:291-317`）**：`backgroundStreamRequestId = null`（`:297`）需同步为 `backgroundStream = null`。
> 建议改完后全仓 `grep -n backgroundStreamRequestId` 应为 **0 命中**。

### 4.4 宿主：`DaemonBridge.java`

**（a）解析信封（`:804-831`）**

```java
// 改前
if (!obj.has("id")) { return; }
String id = obj.get("id").getAsString();
if (id.startsWith("hb-")) { return; }
RequestHandler handler = context.getRequestHandler(id);
if (handler == null) { LOG.debug("[DaemonBridge] No handler for request " + id); return; }
...
if (obj.has("line")) { handler.callback.onLine(obj.get("line").getAsString()); return; }

// 改后
String session = (obj.has("session") && !obj.get("session").isJsonNull())
    ? obj.get("session").getAsString() : null;
String line = obj.has("line") ? obj.get("line").getAsString() : null;

if (line != null && session != null) {
    // P1：先观测（统计 + 透传），路由行为不变
    envelopeStats.record(session, line);
}
// P1 阶段：仍按 id 找 handler（保持不变）
if (!obj.has("id") || obj.get("id").isJsonNull()) {
    if (line != null) unattributed(line, session);   // 计数 + DEBUG 日志
    return;
}
...原有 id 分支...
```

> **P1 的关键约束：这里绝对不能改成 `session` 优先。** 因为若用户环境里 core 未同步升级（旧 core 不发 `session`），一旦改成 session 优先，全部流式行会瞬间变成 unattributed → **消息完全不显示**。翻转是 P1.5 的事，且必须有 gate 判据（§5.1）。

**（b）新增 `unattributed` 处理**

```java
private void unattributed(String line, String session) {
    long n = unattributedCount.incrementAndGet();
    // 每 100 条打一次，避免刷屏
    if (n <= 5 || n % 100 == 0) {
        LOG.warn("[DaemonBridge] unattributed line (#" + n + "): " + abbreviate(line, 120));
    }
}
```

### 4.5 宿主：`OpenCodeMarkerParser.java`（带内冗余，顺带修正）

`[STREAM_END]`（`:131-134`）与 `[MESSAGE_START]`（`:200-203`）当前把 TAG 后的 `<sid>` 硬编码丢弃：

```java
// 改前
if (line.startsWith("[STREAM_END]")) { callback.onMessage("stream_end", ""); return; }
// 改后
if (line.startsWith("[STREAM_END]")) { callback.onMessage("stream_end", stripSeparator(line.substring("[STREAM_END]".length())).trim()); return; }
```

```java
// 改前
if (line.startsWith("[MESSAGE_START]")) { callback.onMessage("message_start", ""); return; }
// 改后
if (line.startsWith("[MESSAGE_START]")) { callback.onMessage("message_start", stripSeparator(line.substring("[MESSAGE_START]".length())).trim()); return; }
```

> 这是**带内身份**（marker 文本自带），与信封身份**互为校验**：二者不一致时以信封为准并 WARN。
> 保留它是因为信封解决"归属"，带内身份能额外发现"行被错误搬运"这类 bug，成本几乎为零。

### 4.6 webview：只加计数器，不改行为

在 `streamingCallbacks.ts:459-460` 与 `messageCallbacks.ts:174,433,560` 的空 id 分支加统计，**P1 不改变回落行为**：

```ts
// P1：仅观测
if (!targetSessionId) {
  window.__orphanStats = window.__orphanStats || { delta: 0, snapshot: 0, dialog: 0 };
  window.__orphanStats.delta++;
}
```

### 4.7 P1 验收

| 项 | 判据 |
|---|---|
| core | `grep backgroundStreamRequestId` = 0；`npm test` 全绿；新增 `daemon-envelope.test.js` 断言三种 ctx 产物 |
| 宿主 | 新增 `DaemonBridge` 单测：`{"id":null,"session":"ses_x","line":"..."}` 走 unattributed 且**不**投给任何 RequestHandler |
| webview | `tsc --noEmit` 通过；新增单测：`onContentDelta` 空 id 递增 `__orphanStats.delta` |
| 端到端 | 多会话并行发送，日志出现 `unattributed=N`；**串会话量不增加**（行为不变是预期） |
| **身份覆盖率** | `session != null` 的流式行占比；**这是进入 P1.5 的唯一 gate** |

---

## 5. 阶段 P1.5：路由翻转（gate 通过后）

> 这一步才真正实现 INV-A。单独成一个阶段，因为它**有破坏性**。

### 5.1 前置 gate（全部满足才可翻转）

1. 全量 marker（含 `[CONTENT_DELTA]`/`[THINKING_DELTA]`/`[QUESTION_CLOSED]`/`[PERMISSION_CLOSED]`）的 `session != null` 覆盖率为 **100%**；
2. `unattributed` 计数在**正常会话**（非报错/非切换）中为 0；
3. core 已在两个插件同步（vscode symlink / idea repack 均验证）。

### 5.2 宿主路由翻转

```java
// P1.5：session 优先
if (session != null && line != null) {
    SessionCallbackAdapter adapter = chatWindow.getSessionAdapter(session);
    if (adapter != null) { adapter.onLine(line); return; }        // 按会话投递
    unattributedNoAdapter(session, line);                          // 有身份但无 adapter：计数 + WARN
    return;
}
// fallback：请求级应答
if (id != null) { ...原逻辑... }
```

> 新增 `getSessionAdapter(session)`（`OpencodeBuddyChatWindow.java:89` 的 `sessionAdapters` 已存在，直接暴露 getter）。
> `DaemonBridge` 需要能拿到窗口级 adapter 注册表——通过 `HandlerContext` 注入，**不要**用静态方法。

### 5.3 webview 禁止回落（INV-A 落地）

```ts
// streamingCallbacks.ts:459-461  改后
const [targetSessionId, delta] = typeof arg1 !== 'undefined' ? [arg0, arg1] : [null, arg0];
if (!targetSessionId) {
  window.__orphanStats.delta++;
  console.warn('[streaming] dropping delta with no sessionId');   // 保留告警
  return;                                                          // ← 不再写"当前会话"
}
```

同样处理 `messageCallbacks.ts:174,433,560`：

```ts
// 改后
const effectiveSessionId = targetSessionId;   // 不再 `|| currentSessionIdRef.current`
if (!effectiveSessionId) { window.__orphanStats.snapshot++; return; }
```

以及 `sequenceBarrier.ts:37-40`：

```ts
// 改前
const isForegroundSession = (sessionId?: string | null): boolean => {
  if (!sessionId) return true;          // ← 空视为前台
  ...
// 改后
  if (!sessionId) return false;         // ← 空视为非法，由调用方丢弃
```

`useSessionTabs.ts:317-322` 的"活动 tab 未绑定即认领"兜底**删除**——它是本次补丁新增的推断路径，与 INV-A 直接冲突。

### 5.4 P1.5 验收

| 项 | 判据 |
|---|---|
| INV-A 单测 | `onContentDelta("", "x")` → 不写入任何会话，`__orphanStats.delta === 1` |
| 回归 | 多会话并行发送 30 分钟，`unattributed` 保持 0，**无消息串会话** |
| 反向验证 | 人工构造 `{session:null}` 流式行 → 被丢弃且计数，**不得**出现在任何会话 |

---

## 6. 阶段 P2：回合终态显式化（修 loading 不停）

### 6.1 core：新增 `[TURN_END]`

定义：

```
[TURN_END] {"sessionId":"ses_x","turnId":<seq>,"reason":"idle|error|aborted|timeout"}
```

`turnId` 直接取服务端 `durable.seq`（§2 INV-B），core 在 `_handleEvent` 里记入 `turn.turnId`。

**前置：turn 对象补两个字段**（`_createTurn`，`:902-930` 当前**只有 `requestId`，没有 `sessionId`**）

```js
function _createTurn(requestId = null, sessionId = null) {
  return {
    requestId,
    sessionId,        // ← 新增：终态发射需要它
    turnId: null,     // ← 新增：由 `_handleEvent` 从 props.durable.seq 填入
    ...
  };
}
// 调用点 :1114 与 :1262
const turn = _createTurn(currentReqId, sessionId);
```

**发射点（全部收敛到 `_settleTurn`，`:881`；它被 `:129,385,620,661,678` 五处调用）**

```js
function _settleTurn(turn, { success, error }) {
  if (turn.settled) return;          // 现有幂等：正好符合 INV-C
  turn.settled = true;
  emitTurnEnd(turn.sessionId, turn.turnId, error ? 'error' : 'idle');   // ← 新增
  // ...其余现有逻辑不变...
}
```

> `emitTurnEnd` 内部必须对 `turnId == null` 容错（服务端未给 `durable.seq` 时退化为 `0`），
> 前端幂等键取 `sessionId + (turnId ?? 0)`。

**`abortCurrentTurn`（`:1369-1393`）本地立即发射，不等服务端**

```js
export async function abortCurrentTurn(targetSessionId) {
  const turn = targetSessionId ? _activeTurns.get(targetSessionId) : null;
  if (turn) {
    turn.aborted = true;
    // ← 新增：本地立即、幂等
    emitTurnEnd(turn.sessionId, turn.turnId, 'aborted');
  }
  // HTTP abort 照发（现有逻辑不变）
}
```

> 现状问题：`abortCurrentTurn` 自身不发射任何终态，靠 SSE 的 `session.idle` 补发；本地 180s 兜底已移除。

### 6.2 宿主：透传 `turn_end`

`OpenCodeMarkerParser` 加分支 → `onMessage("turn_end", json)`；
`SessionCallbackAdapter` 加 `onTurnEnd(payload)` → `callJavaScript("onTurnEnd", getTargetSessionId(), json)`。

### 6.3 webview：会话级状态机（替换布尔 + 守卫）

```
idle --send--> running(turnId)
running(n) --delta(n)----> running(n)        接受
running(n) --delta(m≠n)--> running(n)        丢弃（不再 ensureStreamingActive）
running(n) --interrupt---> settled(n,aborted) 本地立即、幂等
running(n) --onTurnEnd---> settled(n,reason)
running(n) --timeout-----> settled(n,timeout)
settled(n) --send--------> running(n+1)
```

**必须删除的三道守卫**（它们的存在只是为了规避"非幂等"，有了 `turnId` 后不再需要）：

| 位置 | 现状 | 处理 |
|---|---|---|
| `SessionCallbackAdapter.java:140-145` | 流活跃期抑制 `loading=false` | 删除 |
| `messageCallbacks.ts:793-796` | `isStreamingRef.current` 时忽略 `loading=false` | 删除 |
| `streamingCallbacks.ts:592` | `__sessionTransitioning` 早退 | 删除（终态必须处理，见 INV-C） |

**`ensureStreamingActive()`（`:452-457`）改为按 turnId 判断**：

```ts
const ensureStreamingActive = (turnId?: number) => {
  if (turnId != null && turnId === window.__settledTurnId) return;   // 已结算回合：不再点亮
  if (isStreamingRef.current) return;
  isStreamingRef.current = true;
  setStreamingActive(true);
  startStallWatchdog();
};
```

**终态不再做 `isActiveSession` 分流**（INV-C）：`onStreamEnd`/`onTurnEnd` **总是**写目标会话的 tab 状态；激活态显示由"当前活动 tab 的 turn 状态"派生。

### 6.4 宿主：`SessionHandler.handleInterruptSession`（`:542-559`）

```java
// 改前：查不到 session 就静默什么都不做
if (targetSession != null) { ...interrupt().thenRun(echo)... }

// 改后
if (targetSession == null) {
    LOG.warn("[SessionHandler] interrupt for unknown session: " + targetSessionId);
    handlerContext.emitToFrontend("onTurnEnd", targetSessionId, "{\"reason\":\"aborted\"}");  // 仍回 echo
    return;
}
```

### 6.5 P2 验收

| 项 | 判据 |
|---|---|
| core 单测 | `abortCurrentTurn` **同步**产出一次 `[TURN_END]{reason:'aborted'}`；重复调用只产一次 |
| webview 单测 | `running(3)` 收 `delta(3)` 接受；`interrupt` → `settled(3)`；随后 `delta(3)` 丢弃且 `streamingActive===false` |
| 手动 | 点停止，**1s 内** tab 转圈消失；连点停止 / 停止后立即切 tab，均不复现 |

---

## 7. 阶段 P3：弹窗持久队列（修"已取消"）

### 7.1 根修：`PermissionRequest` 加 `sessionId`

```java
// PermissionRequest.java:12-18  改后新增
private final String sessionId;     // 产生该请求的会话，不可为空
```

- 由**产生请求的那一层**填实：`OpenCodeMessageHandler.handlePermissionRequest`（`:342-347`）已在算 `sessionId`，改为**必填校验**（为空则拒绝注册并 WARN）。
- `SessionCallbackAdapter.onPermissionRequested`（`:221-232`）改为 `permissionHandler.showPermissionDialog(request, request.getSessionId())`。
- `PermissionHandler.showPermissionDialog`（`:267-272`）**删除回落** `context.getSession()` 分支。
- 前端 `permissionCallbacks.ts:59` 改为 `const targetSessionId = request.sessionId;`，为空则走 orphan 通道（下方）。

### 7.2 `updateBackgroundTab` 不再静默丢弃（`useSessionTabs.ts:326-340`）

```ts
// 改后
const updateBackgroundTab = useCallback((predicate, updater, fallback?: (reason: 'no-match') => void) => {
  let matched = false;
  setTabs((prev) => {
    const next = prev.map((tab) => {
      if (predicate(tab)) { matched = true; const u = { ...tab }; updater(u); return u; }
      return tab;
    });
    return next;
  });
  if (!matched) {
    window.__orphanStats = window.__orphanStats || { delta: 0, snapshot: 0, dialog: 0 };
    window.__orphanStats.dialog++;
    fallback?.('no-match');
  }
}, []);
```

### 7.3 orphan 弹窗队列（按 sessionId 归集，永不丢弃）

```ts
// 新增：webview/src/hooks/useOrphanPrompts.ts
type OrphanPrompt = { sessionId: string | null; kind: 'permission' | 'question'; payload: any; at: number };
// Map<sessionId, OrphanPrompt[]>，sessionId 为 null 时进 __unknown 桶
```

- 目标会话的 tab **稍后**出现（新建会话 bootstrap 完成后、tab 重新打开）→ 自动认领并弹出。
- UI 显式提示（`SessionTabBar.tsx:166,332` 已有角标位）：有 orphan 的 tab 显示待处理角标。
- `SessionLifecycleManager.java:174-201,243-249` 的"新建会话/bootstrap 清空全部 pending"（`PermissionHandler.java:379-424` 的 `clearPendingRequests`）改为**按 sessionId 清**，不得 `clear()` 全表。

### 7.4 注册表去静态化 + 权限纳入 safety-net

| 位置 | 问题 | 改法 |
|---|---|---|
| `OpencodePermissionRegistry.java:36,50-52` | 进程级 `static Map` + 超限 `PENDING.clear()` → 成批丢在途请求 | 改实例级；淘汰策略"先结算再淘汰"（LRU 但不丢未结算） |
| `OpencodePermissionRegistry.java:42-43` | `per_` 前缀硬编码 | 按服务端返回的 id 注册，不猜前缀 |
| `PermissionHandler.java:258-307`（opencode 路径） | 未注册 future、无 safety-net（对比 `:207-249` legacy 路径**有**） | 纳入统一 `pendingPrompts` 注册表 + `scheduleSafetyNet` |
| `PermissionHandler.java:352-363` | `context.getSession()` 为 null 时 NPE 被吞，决策静默丢失 | 显式判空 + 上报，不吞 |

### 7.5 P3 验收

| 项 | 判据 |
|---|---|
| 单测 | `updateBackgroundTab` 谓词不命中 → 断言进入 orphan 队列（而非 no-op） |
| 单测 | `PermissionRequest` 无 sessionId 时构造被拒 |
| 手动 | 非激活会话触发权限 → 切回该 tab **必定**看到弹窗；不再出现无弹窗的"已取消" |
| 手动 | 新建会话期间触发权限 → 不被清空，切过去能看到 |

---

## 8. 阶段 P4：收口（id 单一化 + per-session 运行时状态）

### 8.1 Java：id 单一化

| 位置 | 改法 |
|---|---|
| `OpencodeBuddyChatWindow.java:2523-2541` | 5 参构造（`sessionId=null`）**删除**，主 adapter 必须带身份出生（`:1553-1567` 的 6 参构造即正确形态） |
| `OpencodeBuddyChatWindow.java:1604-1618` `registerSession` | 加 `adapter.setSessionId(targetSessionId)`（`SessionCallbackAdapter.java:95-100` 已存在但**无任何外部调用者**） |
| `SessionCallbackAdapter.java:102-104` `getTargetSessionId` | 空值不再回落 `""`；改为抛错/返回 null 并由调用方拒绝 |
| `HandlerContext.java:105-119` `getSession` | `sessionLookup` 接上，或 `sessionPool` 双键注册（`tabId` + `sessionId`） |
| `OpencodeBuddyChatWindow.java:2855-2868` `isSessionActive` | 删除 `permissionServiceKey` 兜底分支 |
| `MessagesContext.tsx:47-57` | 全局单槽搬迁到 `Map<sessionId, SessionRuntime>` |
| `useSessionTabs.ts:411-447,453-474` | 删除 `saveCurrentTabSnapshot` / `selectTab` 的"全局 ↔ 快照"双向搬运（双重真源与竞态来源） |
| `sequenceBarrier.ts:31` | 删除全局兜底 `__minAcceptedUpdateSequence`，只留 per-session |

### 8.2 `PermissionHandler.clearPendingRequests`（`:379-424`）

改为按会话清理；`webview/src/main.tsx` 的 `__resetTransientUiState` 同理。

### 8.3 P4 验收

| 项 | 判据 |
|---|---|
| 静态 | `grep -n "currentSessionIdRef.current" webview/src/hooks/windowCallbacks` 仅出现在**新会话创建**处，不得出现在任何"消费→回落"处 |
| 构建 | webview `tsc --noEmit` + `vitest run` 全绿；Java `./gradlew compileJava compileTestJava --offline` 成功 |
| 回归 | 三症状的手动复现步骤全部通过 |

---

## 9. 测试 seam（逐阶段）

### 9.1 core（`opencode-buddy-core`）

```js
// 新增 daemon-envelope.test.js
// 1) 有 turn：{id:'7',session:'ses_a'}
// 2) 无 turn 但有 SSE session：{id:null,session:'ses_a'}  ← 回归 daemon.js:420 的丢失
// 3) 完全无归属：{id:null,session:null}
// 4) abort：同步产出一次 [TURN_END]{reason:'aborted'}，重复调用仅一次
// 5) _handleEvent 注入无 turn 的 message.part.delta：
//    断言产出的行 session 必须为 SSE 的 sessionID，且【不得】等于任何 requestId
```

### 9.2 宿主（`opencode-idea-gui`）

```java
// DaemonBridgeTest：{"id":null,"session":"ses_x","line":"..."} → unattributed，不投任何 RequestHandler
// DaemonBridgeTest：{"id":"7","session":null,"line":"..."} → 仍走 id 路由（P1 兼容）
// SessionCallbackAdapterTest：构造后 getTargetSessionId() 不为空（回归 §1.5）
// PermissionRequestTest：无 sessionId 构造被拒
```

### 9.3 webview

```ts
// onContentDelta("", "x") → 不写入任何会话；__orphanStats.delta === 1
// 状态机：running(3)+delta(3) 接受 → interrupt → settled(3) → delta(3) 丢弃，streamingActive===false
// updateBackgroundTab 谓词不命中 → 进入 orphan 队列
// sequenceBarrier：空 sessionId → isForegroundSession === false
```

---

## 10. 风险、兼容与回滚

| 风险 | 说明 | 缓解 |
|---|---|---|
| **旧 core + 新宿主** | 旧 core 不发 `session` 字段 | P1 **只加不改**；翻转前用覆盖率 gate（§5.1）。新宿主对无 `session` 的行保留 id 路由 |
| **新 core + 旧宿主** | 旧宿主忽略 `session` 字段 | JSON 多一个键天然向后兼容；`id:null` 的新增行（原被丢弃）会让旧宿主 `LOG.debug` 丢弃 → 行为不劣化 |
| **翻转后消息不显示** | 若某条 marker 的 session 覆盖不到 | 必须先过 gate；保留 `unattributed` 计数 + WARN，可一键回退（下述开关） |
| **core 改动影响 vscode 插件** | symlink 直生效 | P1 只加字段，vscode 侧不受影响；P1.5 起两插件**同批次**验证 |
| **idea 未 repack** | 沿用旧 `dist/ai-bridge.zip` | 用 `-PrepackCore=true` 强制重打包；或删 `opencode-buddy-core/dist/` |

**回滚开关（建议实现）**

```js
// core: 环境变量 AI_BRIDGE_ENVELOPE_SESSION=0 时退化为旧信封 {id,line}
// 宿主: 系统属性 opencode.bridge.sessionRouting=false 时强制走 id 路由
```

每个阶段独立可回滚：P1（纯加字段）、P1.5（路由开关）、P2/P3/P4 各自独立提交。

---

## 11. 变更清单汇总

| 文件 | 阶段 | 改动 | 破坏性 |
|---|---|---|---|
| `core/daemon.js:64-70,208-223,279-285,297,436-439,526-538` | P1 | 单槽→双字段；信封加 `session` | 否（加字段） |
| `core/services/opencode/opencode-daemon-service.js:416-421` | P1 | `runWithContext` 无 turn 也带 session | 否 |
| `core/services/opencode/opencode-daemon-service.js:881,1369` | P2 | `_settleTurn`/`abortCurrentTurn` 发 `[TURN_END]` | 否 |
| `core/utils/marker-protocol.js` | P2 | 新增 `emitTurnEnd` | 否 |
| `DaemonBridge.java:804-831` | P1 / P1.5 | 解析 session；新增 unattributed；翻转路由 | P1.5 是 |
| `OpenCodeMarkerParser.java:131-134,200-203` | P1 | 保留 `<sid>` | 否 |
| `OpenCodeMarkerParser.java` | P2 | 新增 `[TURN_END]` 分支 | 否 |
| `SessionCallbackAdapter.java:95-104,140-145,221-232,507-518` | P2/P4 | 删守卫；构造注入；`onTurnEnd` | 是（P4） |
| `PermissionRequest.java:12-18` | P3 | 加 `sessionId` | 是（构造签名） |
| `PermissionHandler.java:258-307,267-272,352-363,379-424` | P3 | 纳入 safety-net；删回落；按会话清理 | 否 |
| `OpencodePermissionRegistry.java:36,42-43,50-52` | P3 | 去静态化、去 `per_`、不 `clear()` | 否 |
| `OpenCodeMessageHandler.java:342-347,563-586,387-401` | P1/P3 | session 必填校验；流式加过滤 | 部分 |
| `SessionHandler.java:542-559` | P2 | 查不到 session 也回 echo | 否 |
| `OpencodeBuddyChatWindow.java:1604-1618,2523-2541,2855-2868` | P4 | 回填 sessionId；删 5 参构造；删兜底 | 是 |
| `streamingCallbacks.ts:452-457,459-461,500-501,592,609` | P1/P2 | 计数；禁回落；turnId 幂等；删 transition 早退 | P1.5 是 |
| `messageCallbacks.ts:174,433,560,525,793-796` | P1.5/P2 | 禁回落；删 loading 抑制 | 是 |
| `permissionCallbacks.ts:59-79,130-154,179-182` | P3 | 读 `request.sessionId`；orphan 通道 | 否 |
| `useSessionTabs.ts:317-322,326-340,411-474` | P1.5/P3/P4 | 删认领兜底；orphan 队列；删快照搬运 | 是 |
| `sequenceBarrier.ts:31,37-40` | P1.5/P4 | 空 id 非前台；删全局兜底 | 是 |
| `MessagesContext.tsx:47-57` | P4 | 全局单槽 → per-session Map | 是 |
| `useMessageSender.ts:443-476` | P2 | interrupt 推进 `settled(turnId)` | 否 |

---

## 12. 执行顺序 checklist

```
[ ] P1  core: request-context 约定 + _handleEvent 带 session + daemon.js 信封 + 宿主解析/计数 + 带内 sid 保留
[ ] P1  验收：unattributed 计数出现；身份覆盖率统计可得；行为不变
[ ] P1.5 过 gate → 宿主 session 优先 + webview 禁回落 + sequenceBarrier 空值非前台 + 删认领兜底
[ ] P1.5 验收：多会话 30 分钟无串会话；人工空 session 必被丢弃
[ ] P2  [TURN_END] + turnId(durable.seq) + 会话状态机 + 删三道守卫 + interrupt 回 echo
[ ] P2  验收：停止后 1s 内转圈消失
[ ] P3  PermissionRequest.sessionId + orphan 队列 + 注册表去静态化 + 权限 safety-net + 按会话清理
[ ] P3  验收：非激活会话权限弹窗必现；无"已取消"假象
[ ] P4  5 参构造删除 + registerSession 回填 + HandlerContext 双键 + per-session runtime state + 删快照搬运
[ ] P4  验收：tsc/vitest/gradle 全绿；三症状手动复现全过
[ ] 两插件同批次验证（idea repack / vscode symlink）
```

---

## 13. 与既有文档的关系

- `docs/multi-session-isolation-architecture.md` —— 诊断与目标架构（本文的论证来源）。
- `docs/message-conversation-and-streaming-lifecycle.md` —— 流式生命周期现状。
- `docs/permission-and-question-flow.md` —— 权限/提问链路现状。
- `docs/undo-redo-and-fork-flow.md` —— revert/fork（同样依赖按 id 投递，改造后需同步回归）。
- `docs/cli-path-and-engine-mode-architecture.md` / `docs/engine-resolution-architecture.md` —— 引擎解析（正交）。
