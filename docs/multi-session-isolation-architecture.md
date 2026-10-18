# 多会话隔离与消息路由 —— 根因诊断与目标架构

> 本文回答三个线上症状：① 多会话下消息串会话；② 权限/提问弹窗不弹出、直接显示「已取消」；
> ③ 点击停止按钮后标签页一直转圈。
>
> **结论：三者为同一架构缺陷的三种表现，不应继续打补丁。**
> 本文只做诊断与方案，不含代码改动。

---

## 0. TL;DR

**根因一句话：会话身份（sessionId）不是跨层消息的一等字段，而是靠「当前活跃 turn」推断出来的；
每一层在推断失败时都有「当作当前会话」的兜底，而没有任何一层在推断失败时选择拒绝。**

具体地，系统里同时存在 5 个「当前会话」型单槽，并且**它们各自独立地承担了会话归属的最终裁决权**：

| 层 | 单槽 | 位置 |
|---|---|---|
| core 传输 | `backgroundStreamRequestId` | `opencode-buddy-core/daemon.js:64` |
| core 事件 | `_activeTurns.get(sessionID)` 未命中的 fallback | `services/opencode/opencode-daemon-service.js:411,416-421` |
| 宿主窗口 | `session` / `sessionId` / `streamCoalescer` / `sessionCallbackAdapter` | `OpencodeBuddyChatWindow.java:77,88,94,211` |
| 宿主回调 | `SessionCallbackAdapter.sessionId`（构造时为 null） | `SessionCallbackAdapter.java:42,102-104` |
| webview | `currentSessionIdRef.current` 作为 `targetSessionId` 缺失时的回落 | `streamingCallbacks.ts:459-461,500-501`；`messageCallbacks.ts:174,433,560` |

再加上 webview 侧两个**全局单槽**——`loading/streamingActive`（`MessagesContext.tsx:50-53`）与单模态弹窗
（`useDialogManagement.ts:188-215`）——就解释了全部三个症状：

- 消息串会话 = 归属推断错 → 投给了错误会话；
- 弹窗不弹 = 归属推断错 → 被「按激活态门控」丢弃 → 服务端 Deferred 以 rejected 结算 → 卡片渲染「已取消」；
- loading 不消失 = 终止事件被归属推断错/守卫吞掉 → 全局 loading 单槽无人清零。

---

## 1. 现状链路（逐跳，含证据）

### 1.0 先确立事实：服务端原生提供什么（2026-10-16 实测 opencode v2.0.27）

**结论：消息、提问、权限——服务端全部原生携带 sessionID，且多数是 schema 必填字段。**
所以本缺陷不是「服务端没给」，而是「客户端在再发射时把它丢了」。

**（a）REST：会话身份在路由上**

| 对象 | 端点 | sessionID 位置 |
|---|---|---|
| 消息列表 / 单条 | `GET /api/session/{sessionID}/message[/{messageID}]` | 路径 |
| 权限 | `GET/POST /api/session/{sessionID}/permission` | 路径 |
| 权限答复 | `POST /api/session/{sessionID}/permission/{requestID}/reply` | 路径 |
| **提问（v2 叫 Form）** | `GET/POST /api/session/{sessionID}/form` | 路径 |
| Form 答复 | `POST /api/session/{sessionID}/form/{formID}/reply` | 路径 |
| Inbox / 事件日志 | `GET /api/session/{sessionID}/inbox`、`/api/experimental/session/{sessionID}/log` | 路径 |

> **命名陷阱**：v2 里「提问」不叫 `question`，叫 **`Form`**；按 `question` 搜 API 会一无所获。
> core 已改用 Form 端点（`opencode-buddy-core/services/opencode/opencode-sdk-client.js:1072,1099`）。

**（b）schema 里的必填 sessionID**（`/openapi.json` → `components.schemas`）

| Schema | required |
|---|---|
| `Permission.Request` | `["id","sessionID","action","resources"]` ← **必填** |
| `Form.Info` | `["id","sessionID","title","fields"]` ← **必填** |
| `Form.Detail` | `["id","sessionID","title","fields","state"]` ← **必填** |
| `Session.Inbox.User` / `.Synthetic` / `.Move` / `.Compaction` | 均含 **必填** `sessionID` |

**（c）SSE：每个会话级事件都带 `data.sessionID`（实测原始帧）**

```
data: {"id":"evt_…","type":"session.created","data":{"sessionID":"ses_…", …},
       "durable":{"aggregateID":"ses_…","seq":0}}
```

实测一次 `POST /api/session/{sid}/prompt` 捕获 21 类事件：

| 事件 | 是否带 sessionID |
|---|---|
| `session.created` / `session.execution.started` / `session.step.started` | ✅ `data.sessionID` |
| `session.inbox.enqueued` / `session.inbox.delivered` | ✅ `data.sessionID` |
| **`session.reasoning.delta`（×7）** | ✅ `data.sessionID` |
| `provider.updated` / `model.updated` / `plugin.updated` / `vcs.branch.updated` … | 无（非会话级，正确） |

→ **连流式 delta 都带 sessionID。** 服务端不存在「delta 无身份」这回事。

**（d）服务端还白送了单调序号：`durable.seq`**

每个事件带 `durable:{ aggregateID (=sessionID), seq }`——**每会话单调递增的事件序号**。
文档 §6.3 里计划「自造 turnId」因此是多余的：可靠定序服务端原生就有。

**（e）core 已经读到了，丢在再发射那一步**

`opencode-daemon-service.js:404` 解析出 `sessionID`；`event-normalize.js:43` / `:103`
也把它写进了 `normalized.sessionId` / `request.sessionId`。**丢失只发生在 marker 再发射处**（§1.2）。

```
opencode server ──SSE(长驻, 按 directory)──▶ core: _handleEvent
                                              │  查 _activeTurns.get(sessionID)
                                              │  命中 → ALS{id: turn.requestId}
                                              │  未命中 → 无 ALS
                                              ▼
                              process.stdout.write 被劫持 → {id, line}   ← 会话身份在此丢失
                                              ▼
                              Java DaemonBridge: 按 id 找 RequestHandler  ← 找不到就丢
                                              ▼
                              OpenCodeMarkerParser: [TAG] → onMessage(type, payload)
                                              ▼
                              OpenCodeMessageHandler(state ← per-request, 带 sessionId)
                                              ▼
                              SessionCallbackAdapter(自己的 sessionId) → callJavaScript(fn, sessionId, …)
                                              ▼
                              webview: isActiveSession(targetSessionId) ? 写当前会话 : 写后台 tab
```

### 1.1 传输层：会话身份不是信封字段（**这是架构缺陷的根**）

`daemon.js:208-223` 劫持 `process.stdout.write`，把每一行 marker 包成 NDJSON：

```js
const requestId = currentRequestId();
if (requestId) {
  for (const line of lines) if (line.length > 0) writeRawLine({ id: requestId, line });
}
```

信封里**只有 `id`（发起该请求的 requestId），没有 `session`**。
而 `currentRequestId()`（`daemon.js:68-70`）是：

```js
return requestContext.getStore()?.id ?? backgroundStreamRequestId ?? null;
```

`backgroundStreamRequestId`（`daemon.js:64`）由**任何** turn 命令到达即覆盖，与 session 无关：

```js
const turnCommand = isTurnCommand(method);   // daemon.js:436
if (turnCommand) backgroundStreamRequestId = id;   // :437-439  ← 单槽，最后到达者胜
```

于是在 core 内，"谁发的" 与 "属于哪个会话" 被**压缩成了同一个字符串**。
一旦二者不一致，下游没有任何信息可以纠正。

### 1.2 core：只有边界 marker 带 session，正文 marker 不带

`emitJsonStringMarker` / `emitMarker` 产生的 marker 中，**带 session 的只有"上升沿"事件**：
`[MESSAGE_START] <sid>`、`[STREAM_END] <sid>`、`[SESSION_ID] <id>`、`[SESSION_TITLE] {…}`、
`[TODO_UPDATED] {sessionID}`、`[STATUS] {sessionID}`、`[MESSAGE_REMOVED] {sessionID,messageID}`，
以及 **`[PERMISSION_REQUEST]` / `[QUESTION_REQUEST]`**（`event-normalize.js:43,103` 已填入 `sessionId`）。

而**"流式"与"下降沿"两类恰好都不带**——它们正是症状①②③所在：

| marker | 发射点 | payload | 后果 |
|---|---|---|---|
| `[CONTENT_DELTA]` | `:508,513,614,616,808,810` | delta 字符串 | 归属 100% 依赖 NDJSON `id` → **症状①** |
| `[THINKING_DELTA]` | 同上 | delta 字符串 | 同上 |
| `[QUESTION_CLOSED]` | `:749` | `{ requestId }` ← **无 session** | 清理打错对象 → **症状②** |
| `[PERMISSION_CLOSED]` | `:759` | `{ permissionId }` ← **无 session** | 同上 |

**→ 「上升沿带身份、流式与下降沿不带」，这不是随机的，而是"谁先写谁先想到"的历史产物。**
修复方向（§6.2 A）因此极其明确：**不是新增身份，而是停止丢弃服务端已经给的身份**（§1.0）。

对比：宿主侧同文件的 `handleMessagesRemoved` **有**显式 session 过滤
（`OpenCodeMessageHandler.java:455-460`），而 `handleContentDelta` / `handleThinkingDelta` /
assistant 三条流式路径**没有**——说明防线是零散补的，不是设计出来的。

### 1.3 core：`turn` 未命中的分支会退化成「最后一个会话」

`opencode-daemon-service.js:399-426`：

```js
const sessionID = props?.sessionID || props?.sessionId || …;
const turn = sessionID ? _activeTurns.get(sessionID) : null;
const runWithContext = (fn) => {
  if (turn && turn.requestId) return requestContext.run({ id: turn.requestId }, fn);
  return fn();                       // ← 未命中：无 ALS，落回 backgroundStreamRequestId
};
```

**未命中不等于「不知道」，而等于「归给最后一个 turn」**。这不是边界情况，以下路径全部无条件发射，
且不受 `turn` 保护：

| marker | 位置 | 是否被 `turn` 保护 |
|---|---|---|
| `[SESSION_TITLE]` | `:456-462` | ✗ |
| `[SESSION_COMPACT_RESULT]` | `:663-672`（注释自陈"压缩不是 turn"） | ✗ |
| `[TODO_UPDATED]` | `:763-769` | ✗ |
| `[QUESTION_CLOSED]` | `:736-752` | ✗ |
| `[PERMISSION_CLOSED]` | `:753-762` | ✗ |
| `[CONTENT_DELTA]`（shell 输出） | `:608-617`，条件 `!turn \|\| turn.toolUseDone.size === 0` | 部分 |

> `:612-617` 尤其典型：`!turn` 时**照样**把 shell 原始输出当 content delta 发射。
> 多会话并发下，一个无 turn 的 shell 结果会被贴到「最后一个发送过的会话」的 id 上。

### 1.4 宿主：按 id demux，查不到就静默丢弃

`DaemonBridge.java:804-814`：

```java
if (!obj.has("id")) { return; }
String id = obj.get("id").getAsString();
RequestHandler handler = context.getRequestHandler(id);
if (handler == null) { LOG.debug("[DaemonBridge] No handler for request " + id); return; }
```

两种后果，正好对应两个症状：

- `id` 对应的请求**仍在飞行**（另一个会话的 turn）→ 这行被投进**那个会话**的 handler → **串会话**；
- `id` 对应的请求**已结束** → `LOG.debug` 丢掉 → **弹窗/终态事件永久丢失**。

### 1.5 宿主：`[STREAM_END]` 解析丢掉了 session，适配器的 sessionId 又是懒加载的

`OpenCodeMarkerParser.java:131-134`：

```java
if (line.startsWith("[STREAM_END]")) {
    callback.onMessage("stream_end", "");   // ← 传空串，[STREAM_END] 后面的 <sid> 被丢弃
```

于是 `onStreamEnd` 的会话身份只能来自 `SessionCallbackAdapter.getTargetSessionId()`
（`SessionCallbackAdapter.java:102-104`）：

```java
private String getTargetSessionId() { return sessionId != null ? sessionId : ""; }
```

而 `sessionId` 字段在 `OpencodeBuddyChatWindow.setupSessionCallbacks()`
（`OpencodeBuddyChatWindow.java:2520-2531`）里是**用 5 参构造器创建的 → 初始为 null**，
只能等 `onSessionIdReceived`（`SessionCallbackAdapter.java:189-205`）事后回填，
且 `registerSession`（`:1604-1618`）**不会**调用 `adapter.setSessionId(...)`。

**因此在 `[SESSION_ID]` 到达之前，宿主对前端说的每一句话都是 `targetSessionId = ""`。**

### 1.6 webview：空 sessionId ⇒ 一律回落「当前会话」（拒绝的反面）

`streamingCallbacks.ts:459-462`：

```ts
window.onContentDelta = (arg0, arg1) => {
  const [targetSessionId, delta] = typeof arg1 !== 'undefined' ? [arg0, arg1] : [null, arg0];
  if (targetSessionId && window.__sessionTabsStore && !isActiveSession(targetSessionId)) { …后台…; return; }
  // ← targetSessionId 为空 → 直接落到「当前会话」
  ensureStreamingActive();
  streamingContentRef.current += delta;
```

`messageCallbacks.ts:174,433,560` 同构：

```ts
const effectiveSessionId = targetSessionId || options.currentSessionIdRef?.current;
```

**「身份缺失 = 当作当前会话」是贯穿 core/宿主/webview 三层的同一个决策。**
这是串会话、弹窗丢、loading 不清的共同机制。

### 1.7 webview：弹窗按激活态门控，且后台路由会静默丢弃

`permissionCallbacks.ts:62-72`：

```ts
if (targetSessionId && tabsStore && !tabsStore.isActiveSession(targetSessionId)) {
  tabsStore.updateBackgroundTab(
    (t) => t.sessionId === targetSessionId || t.id === targetSessionId,
    (t) => { … t.pendingPermissions = [...list, request]; }
  );
  return;                       // 非激活会话：不弹窗
}
openPermissionDialog(request);
```

而 `useSessionTabs.ts:326-340` 的 `updateBackgroundTab` 是 `prev.map(...)`：

```ts
setTabs((prev) => prev.map((tab) => { if (predicate(tab)) {…} return tab; }));
```

**谓词不命中任何 tab 时，它什么都不做——请求被彻底丢弃**（既不入队也不弹窗）。
新会话 tab 尚未绑定 `sessionId`、或 tab 已关闭、或 `request.sessionId` 与前端 id 空间不一致时，
就命中这个死角。

**关键精度：弹窗请求对象本身没有会话身份。**
`PermissionRequest`（`src/main/java/com/opencodebuddy/permission/PermissionRequest.java:11-18`）
的字段只有 `channelId / toolName / inputs / suggestions / resultFuture / project / resolved`
——**没有 `sessionId`**。传给前端的会话 id 是 `SessionCallbackAdapter.onPermissionRequested:230`
的 `permissionHandler.showPermissionDialog(request, getTargetSessionId())`，
即**从适配器字段借来的**（§1.4/§1.5：可空，空时返回 `""`）。

因此「弹窗按 sessionId 区分」这一层**并不成立**：会话归属 100% 取决于
"是哪个 adapter 接到的这条请求"，而不是请求自己声明的。
多会话并发时，adapter 单槽（窗口级）被切换 → 归属随切换漂移 → 前端谓词不命中 → 静默丢弃。
**修法必须是给 `PermissionRequest` 加 `sessionId` 字段并在构造处填实**，而不是继续在
`getTargetSessionId()` 上加兜底。

### 1.8 症状②的「已取消」从哪来

「已取消」不是弹窗的产物，而是**弹窗从未出现**之后的必然结果。
`ContentBlockRenderer.tsx:391-420`：

```ts
const isCancelled = answerBlock?.is_error === true || answerBlockText.includes('question rejected');
const status = isCancelled ? 'cancelled' : parsedAnswerCount > 0 ? 'answered' : 'unanswered';
```

渲染见 `QuestionAnswerSummary.tsx:116,122`（`already cancelled` → "已取消"）。

服务端在 `failTool(state, ref, "question rejected")` 时写入 `is_error`。
触发条件：turn 被 abort / 超时 / 其它入口答复。
所以链路是：**归属错 → 请求丢弃 → 无答复 → 服务端结算 rejected → 卡片渲染「已取消」**。

---

## 2. 症状①：消息串会话

**根因链（按可疑度）：**

1. `backgroundStreamRequestId` 单槽（`daemon.js:64,68-70,437-439`）——并发多会话时它恒等于
   「最后一个到达的 turn 命令」，任何无 turn 上下文的 marker 都会被贴到它上面。
2. 无 `turn` 保护仍发射的 marker（§1.2/§1.3）——尤其 `[CONTENT_DELTA]` 无 session、shell 输出分支。
3. `DaemonBridge` 按 id 投递（`:804-831`）——id 若仍活跃，直接投进别的会话；已结束则丢。
4. 宿主流式路径**缺少** session 过滤（对比 `handleMessagesRemoved` 有过滤）。
5. `SessionCallbackAdapter.sessionId` 懒加载（§1.5）→ 早期事件 `targetSessionId=""`。
6. webview 空 id 回落（§1.6），以及 `isActiveSession` 的「活动 tab 未绑定则认领」兜底
   （`useSessionTabs.ts:317-322`）。

**判定：架构问题。** 症状①不是某个赋值写错，而是「身份靠推断」的必然产物。

---

## 3. 症状②：弹窗不弹，显示已取消

**根因链：**

1. 归属错或 `isInactive()` 早退（`SessionCallbackAdapter.java:221-232,507-518`）→ 请求根本没到前端。
2. 到了前端但非激活会话 → `updateBackgroundTab` 谓词不命中 → **静默丢弃**（§1.7）。
3. 到了但 id 空间不一致 → `isActiveSession` 为 false → 同样被"后台化"甚至丢弃。
4. marker 在无 turn 时被降级为 `{type:'daemon',event:'log'}`（`daemon.js:233-242`）→ 宿主当日志丢。
5. 服务端随后结算 rejected → 卡片「已取消」（§1.8）。

**放大的局部缺陷（都是同一架构的次生问题，不是独立 bug）：**

- `OpencodePermissionRegistry` 是**进程级静态 Map**（`PENDING`）+ 超限 `PENDING.clear()`（`:36,50-54`）
  → 高并发下**成批丢失在途请求**。
- `isOpencodePermissionId` 用 `startsWith("per_")` 硬编码（`:42-44`）→ 服务端 id 前缀一变即失效。
- 权限路径**未注册 Java future**（`PermissionHandler.showPermissionDialog:258-293` 只 `callJavaScript`，
  不排 safety-net）→ 既无超时兜底，也无 future 可匹配。
- 单模态弹窗 + 全局队列（`useDialogManagement.ts:188-215`）→ 同一时刻只有一个会话能弹窗。
- 新建会话 bootstrap 立即清空全部 pending（`SessionLifecycleManager.java:490`
  → `PermissionHandler.java:379-424`）。

**判定：架构问题为主（无会话隔离 + 单模态 + 静默丢弃），叠加若干次生缺陷。**

---

## 4. 症状③：停止后标签页一直 loading

标签页转圈 = `tab.streamingActive || tab.loading`（`SessionTabBar.tsx:329,370-371`）。

**关键结构：清零点被"收敛"到了一个点，而这个点可以被三件事吞掉。**

两道主动清零守卫**在 streaming 期间会被抑制**：

- Java：`SessionCallbackAdapter.java:140-145`
  ```java
  if (!loading && streamCoalescer.isStreamActive()) { LOG.debug("Suppressing…"); return; }
  ```
- webview：`messageCallbacks.ts:793-796`
  ```ts
  if (!isLoading && isStreamingRef.current) return;   // "onStreamEnd handles it uniformly"
  ```

于是唯一的（最终）清零点就是 `onStreamEnd`（`streamingCallbacks.ts:638-640` / `:959-967`）。
而它有三条吞掉路径：

1. **会话切换守卫**：`streamingCallbacks.ts:592`
   ```ts
   if (window.__sessionTransitioning) return;   // 早退，不清理、不复位 refs
   ```
   `__sessionTransitioning` 由 `beginSessionTransition` 置位（`useSessionManagement.ts:116-165`），
   只由 `window.setSessionId`（`sessionCallbacks.ts:34`）或 **15s 超时**（`:186-195`）释放。
   中止后 `[STREAM_END]` 通常几秒内到达 → **正好落在窗口里被吞**。

2. **后台分流**：`streamingCallbacks.ts:559`
   ```ts
   if (targetSessionId && !isActiveSession(targetSessionId)) { updateBackgroundTab(...); return; }
   ```
   一旦 `targetSessionId` 与前端"活动"判定不一致（id 空间不一致、`currentSessionIdRef` 滞后），
   清零被重定向到后台 tab → **活动会话的 App 级 loading 永不清零**。

3. **兜底依赖服务端**：`abortCurrentTurn`（`opencode-daemon-service.js:1369-1393`）**自身不发射任何终态事件**，
   只置 `turn.aborted = true` + `sdk.abort()`，靠 SSE 的 `session.idle`/`session.error` 把 in-flight 的
   `await Promise.race(...)`（`:1171`）结算掉。若服务端不补发（abort 后无 idle），
   最坏不再有本地 180s 兜底（已移除 `TURN_TIMEOUT_MS`）。

同时还有**反向再点亮**：

```ts
const ensureStreamingActive = () => {          // streamingCallbacks.ts:452-457
  if (isStreamingRef.current) return;
  isStreamingRef.current = true;
  setStreamingActive(true);                    // ← 覆盖 interruptSession 的清零
};
```

`interruptSession()`（`useMessageSender.ts:443-476`）清了 `loading/streamingActive/isStreamingRef`
**但没有"锁定"该回合**——它自己的注释也承认"backend may still push a few trailing deltas
that were already in flight; those call ensureStreamingActive() and would resume patching"。
所以一个 trailing delta 就能把转圈重新点亮，之后只能靠 `onStreamEnd` 再灭一次。

另外宿主的中止 echo 有前置条件（`SessionHandler.java:538-559`）：

```java
if (targetSession != null) { … interrupt().thenRun(echo onStreamEnd + showLoading false) … }
```

`targetSession` 为 null（`HandlerContext.getSession(ses_id)` 查不到，池 key 不一致）时
**既不中止 daemon，也不回 echo**。

**判定：架构缺环。** 系统没有「回合终态」这个一等概念：
没有 per-session turnId、没有单调的 settled 事件、没有幂等的 `aborted` 终态，
只有一堆布尔 + 守卫，且清零点被收敛到单点。

---

## 5. 架构判定：为什么不能继续打补丁

三个症状是**同一个反模式**的三个投影：

> **在缺少会话身份时，每一层都选择"猜测"（当成当前会话 / 当成最后一个 turn），
> 而不是"拒绝并上报"。**

补丁式修法（给某个分支加一个 session 判断、给某处再补一次 `setLoading(false)`）会：

- 每加一个守卫，就多一条"吞掉事件"的路径（现状已经有 2 道 loading 抑制 + 1 道 transition 早退）
  → **症状会从"串会话"变成"丢消息"，用户感受更差**；
- 无法覆盖未来新增的 marker / 新增的会话操作（fork、compact、subagent）；
- 让"到底谁是这个会话的正确 handler"继续由 5 个单槽投票决定。

因此必须改的是**身份模型**，不是分支条件。

---

## 6. 目标架构

### 6.1 四条不变量（必须可测）

| ID | 不变量 |
|---|---|
| **INV-A** | 任何跨层消息**必须**携带 `sessionId`；`sessionId` 为空的流式/弹窗消息**必须**被拒绝并计数，**不得**回落到"当前会话"。 |
| **INV-B** | 一个会话在任意时刻至多有一个**活跃回合**，由单调递增的 `turnId` 标识；`sessionId + turnId` 唯一确定一条流。 |
| **INV-C** | 回合终态（`idle \| error \| aborted \| timeout`）是**显式、幂等、按会话**的事件；到达后**必须**把该会话的 `loading/streamingActive` 置 false，无论该会话是否处于激活态。 |
| **INV-D** | 每个会话的 UI 运行时状态（`loading` / `streamingActive` / `isThinking` / `pendingPrompts`）**只由该会话自己的状态机写**；不存在全局单槽，也不存在"快照搬运"。 |

### 6.2 分层改造

#### (A) 传输层：信封带 session，废除单槽

- `writeRawLine({ id, session, line })`——新增 `session` 字段。
- 删除 `backgroundStreamRequestId`（`daemon.js:64,68-70,437-439`），改为
  `currentStreamOwner()` 返回 `{ requestId, sessionId }`，**仅从 turn 派生**；
  无 turn 时显式 `sessionId = null`，且**不再借用别的请求的 id**。
- `emitJsonStringMarker(tag, payload, { sessionId })`——`[CONTENT_DELTA]` / `[THINKING_DELTA]`
  也带上（或由信封的 `session` 覆盖）。
- 宿主 `DaemonBridge`：流式行改为**按 `session` 路由**到该会话的回调注册表
  （`sessionAdapters` 已经存在），`id` 只用于请求级命令。
  `session == null` → 走 `unattributed` 通道：日志 + 计数 + 丢弃（**绝不写当前会话**）。

> 这一步落地后，`[STREAM_END]` 丢 session（`OpenCodeMarkerParser.java:131-134`）
> 和适配器懒加载（§1.5）同时失去意义——身份从信封来。

#### (B) 回合终态显式化（修症状③）

- 定义 `[TURN_END] { sessionId, turnId, reason }`，`reason ∈ idle|error|aborted|timeout`。
- `abortCurrentTurn` **本地立即**发 `[TURN_END]{reason:'aborted'}`（幂等，不等服务端），
  不再依赖 SSE 补发；HTTP abort 仍照发。
- webview 改为会话级状态机（见 §6.3），`interrupt` 直接推进到 `settled(turnId)`，
  并把该 turnId 记灰（**忽略该 turnId 的后续 delta**，替代 `ensureStreamingActive` 的反置）。
- **删除**两道 `loading=false` 抑制守卫（`SessionCallbackAdapter.java:140-145`、
  `messageCallbacks.ts:793-796`）与 `__sessionTransitioning` 对终态的早退（`streamingCallbacks.ts:592`）——
  它们的存在只是为了规避非幂等，有了 `turnId` 幂等后不再需要。
- `onStreamEnd` / `[TURN_END]` **不再做 `isActiveSession` 分流**：终态**总是**写该会话的 tab 状态；
  活动会话的 App 级显示由"当前活动 tab 的 turn 状态"派生（单一真源）。

#### (C) 弹窗：持久队列，永不丢弃（修症状②）

- `updateBackgroundTab` 的"谓词不命中即无操作"（`useSessionTabs.ts:326-340`）改为
  **未命中 → 放入 `orphanPrompts`（按 sessionId 归集）并在 UI 上显式提示**，绝不静默 return。
- 弹窗模型二选一（建议前者）：
  1. **全局模态 + 会话标注**：弹窗头部显示来源会话标题；队列按到达顺序，不限会话。
  2. 非激活会话 → tab 角标（已有 `SessionTabBar.tsx:166,332`）→ 切过去自动弹。
- 修 `OpencodePermissionRegistry`：去掉进程级静态 + 超限 `clear()`；改为实例级、不丢
  （或 LRU 但**先结算再淘汰**）；`per_` 前缀硬编码改为按服务端返回的 id 注册。
- 权限路径纳入统一 `pendingPrompts` 注册表（含 future + safety-net），与 ask/plan 一致
  （现在只有后两者挂 safety-net）。
- **`PermissionRequest` 增加 `sessionId` 字段**（见 §1.7 关键精度），在
  `PermissionHandler.showPermissionDialog` 的调用方（`SessionCallbackAdapter:230`）
  之外、由**产生请求的那一层**填实；前端改为读 `request.sessionId`，
  不再依赖 `getTargetSessionId()` 这个可空的适配器字段。

#### (D) id 单一化（消灭 id 空间混用）

- 明确两套 id：`tabId`（UI 本地，仅 webview 内部）与 `sessionId`（`ses_*`，跨层唯一）。
  **所有跨层消息只允许用 `sessionId`。**
- `SessionCallbackAdapter` **构造时**注入 sessionId（不要 5 参 null 构造 + 事后回填）；
  `registerSession` 内 `adapter.setSessionId(targetSessionId)`。
- `HandlerContext.getSession(id)`（`handler/core/HandlerContext.java:105-119`）：
  `sessionLookup` 必须接上，或 pool 双键注册（tabId + sessionId），
  消除「查不到 → 落到当前会话」的隐式路径。
- `SessionHandler.handleInterruptSession` 的 `if (targetSession != null)` 分支：
  查不到时**必须报错并回 echo**，不能静默什么都不做。

#### (E) 单一真源（消除全局单槽）

- webview 的 `loading/streamingActive/isThinking/status/pendingPrompts` 从 App 级全局
  （`MessagesContext.tsx:50-53`）搬到 `Map<sessionId, SessionRuntime>`；
  活动 tab 只是"读哪个 key"。
- 随之删除 `saveCurrentTabSnapshot` / `selectTab` 的"全局 ↔ 快照"双向搬运
  （`useSessionTabs.ts:411-450,472-473,510-533`）——它现在是双重真源与竞态的来源。

### 6.3 会话级状态机（formal）

```
状态：idle
     running(turnId)
     settling(turnId)        // 收到任意终态信号，等待幂等落定
     settled(turnId, reason) // 终态，可再次 running(新的 turnId)

迁移：
  idle             --send--------▶ running(n)      : 发 [MESSAGE_START]/[SESSION_ID]，登记 turnId=n
  running(n)       --delta(n)----▶ running(n)      : 仅接受 turnId===n 的 delta
  running(n)       --delta(m≠n)--▶ running(n)      : 丢弃（不 ensureStreamingActive）
  running(n)       --interrupt---▶ settled(n,aborted)  : 本地立即，幂等；后续 delta(n) 全丢
  running(n)       --[TURN_END]--▶ settled(n,reason)
  running(n)       --timeout-----▶ settled(n,timeout)
  settled(n,*)     --send--------▶ running(n+1)
```

**不变量：** `turnId` 单调递增；同一 `turnId` 的终态只生效一次；
`settled` 一定写目标会话的 runtime state（不论是否激活）。

> **`turnId` 不需要自造**：服务端 SSE 事件已带 `durable:{aggregateID(=sessionID), seq}`（§1.0 d），
> 即**每会话单调递增的序号**。core 只要把它透传（`[TURN_END] {sessionId, seq, reason}`），
> 前端就得到免费的、服务端保证的定序，无需自己维护计数器，也不怕进程重启导致的序号错乱。

---

## 7. 分阶段落地（每阶段独立可验收）

| 阶段 | 内容 | 验收 |
|---|---|---|
| **P1 立即可观测** | (A) 信封加 `session` + 宿主 `unattributed` 通道；(D) 前端**禁止**空 sessionId 回落（改为丢弃 + 计数） | 复现「多会话并行发送」，日志中出现 `unattributed=N`；串会话消息量下降到 0 或明确可归因 |
| **P2 修 loading** | (B) `[TURN_END]` + 会话级状态机 + 删除三道守卫 | 停止按钮后 1s 内 tab 转圈消失；连点停止/切换会话均不复现 |
| **P3 修弹窗** | (C) orphan 队列 + 注册表去静态化 + 权限纳入 safety-net | 非激活会话触发权限，切回该 tab 必定看到弹窗；不再出现无弹窗的「已取消」 |
| **P4 收口** | (D) Java id 单一化 + (E) per-session runtime state | 删除全部"单槽 + 快照搬运"代码；`tsc` / 单测全绿 |

> 依赖：P1 是 P2/P3 的前提（没有身份就无法正确投递终态/弹窗）。
> P4 是纯重构，可与 P3 并行。

---

## 8. 测试 seam（跨两个仓库）

**core（`opencode-buddy-core`）**
- `_handleEvent` 注入伪 SSE 事件，断言：无 turn 的 `message.part.delta` **产出的 marker 必须带
  `session:null`**，且**不得**被贴上任意 requestId。
- `abortCurrentTurn` 断言：**同步**产出 `[TURN_END]{reason:'aborted'}`，且重复调用只产一次。

**宿主（`opencode-idea-gui`）**
- `DaemonBridge` 注入 `{session:null,line}` 断言：走 unattributed，**不投给任何** `RequestHandler`。
- `SessionCallbackAdapter` 断言：构造后 `getTargetSessionId()` **不为空**（回归 §1.5）。

**webview**
- `onContentDelta("", delta)` 断言：**不写入任何会话**（替换现有"回落当前会话"的隐含行为）。
- 状态机：`running(3)` 收到 `delta(3)` → 接受；`interrupt` → `settled(3)`；随后 `delta(3)` → 丢弃且
  `streamingActive` 保持 false。
- `updateBackgroundTab` 谓词不命中 → 断言进入 orphan 队列（而非 no-op）。

---

## 9. 与既有文档的关系

- `docs/message-conversation-and-streaming-lifecycle.md`：现状流式生命周期（本文 §1 是其"身份"视角的补充）。
- `docs/permission-and-question-flow.md`：权限/提问链路现状（本文 §3 说明其为何会丢）。
- `docs/undo-redo-and-fork-flow.md`：revert/fork（同样依赖"按 id 投递"，改造后需同步回归）。
- `docs/engine-resolution-architecture.md`：引擎 v1/v2 解析（正交，不受本文影响）。

---

## 10. 已知的次生缺陷清单（改造时一并处理，勿单独打补丁）

| # | 位置 | 问题 |
|---|---|---|
| 1 | `OpencodePermissionRegistry.java:36,50-54` | 进程级静态 Map + 超限 `clear()` → 成批丢在途请求 |
| 2 | `OpencodePermissionRegistry.java:42-44` | `per_` 前缀硬编码 |
| 3 | `PermissionHandler.java:258-293` | opencode 权限路径未注册 future/未挂 safety-net |
| 4 | `SessionLifecycleManager.java:490` → `PermissionHandler.java:379-424` | 新建会话立即清空全部 pending |
| 5 | `PermissionHandler.java:352-363` | `context.getSession()` 为 null 时 NPE 被吞，决策静默丢失 |
| 6 | `OpenCodeMarkerParser.java:131-134` | `[STREAM_END]` 丢弃 sessionId |
| 7 | `daemon.js:233-242` | 无 ALS 的 marker 被降级为 log 事件丢弃 |
| 8 | `SessionHandler.java:538-559` | 中止查不到 session 时既不下发也不回 echo |
| 9 | `SequenceBarrier.ts:37-42` | 空 sessionId 返回 `true`，会写全局 minAcceptedSequence |
| 10 | `useSessionTabs.ts:326-340` | `updateBackgroundTab` 谓词不命中即静默 no-op |
