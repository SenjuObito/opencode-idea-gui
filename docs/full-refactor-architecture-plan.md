# OpenCode IDEA GUI 全量架构重构技术方案

> 版本：1.0  
> 日期：2026-10-21  
> 状态：目标方案，待评审后实施  
> 决策基线：
> 1. 删除文件 IPC 权限体系。
> 2. 删除 `switch_session`。
> 3. IDE 重启不恢复内部会话 tab，只开新会话。
> 4. 前端运行态彻底改为 per-session / per-tab store，不做 ref 快照交换。
>
> 本文取代 `docs/multi-session-identity-refactoring.md` 中与本文冲突的目标态描述，并吸收
> `docs/multi-session-isolation-architecture.md` 的诊断和
> `docs/webview-flicker-freeze-multi-session-migration.md` 的性能治理经验。
> 本文不保留旧协议兼容层；允许大范围删改。

---

## 0. 结论摘要

当前系统的根因不是“多会话功能没写完”，而是：

> **一个 webview 内承载 N 个会话，但架构仍然假设“当前只有一个会话”。**

具体表现为：

- `tabId`、真实 `sessionId`、临时 UUID、`permissionServiceKey` 被混在同一组 Map 里。
- 宿主有 `session`、`activeSessionId`、`sessionId`、`handlerContext.session`、`sessionCallbackAdapter`、`streamCoalescer` 多个“当前会话”单槽。
- webview 有 `currentSessionIdRef`、`activeTabIdRef`、单份 streaming refs，以及一套“切 tab 时保存/恢复 ref”的补丁。
- `switch_session` 同时承担“切视图”“加载会话”“服务端对账”三种职责。
- 权限存在“文件 IPC”和“SSE 原生”两代体系，且回复路径丢失会话身份。
- SSE 身份在 core 已经基本正确，但宿主和 webview 仍有“空身份回落当前会话”的残留。

目标不是修这些字段，而是把模型改成：

> **Tab 是宿主和 UI 的共同主键；Session 是 Tab 的可空绑定；所有事件按 Tab 路由，所有会话状态按 Tab 归属；活动 Tab 只决定“显示谁”，绝不决定“消息属于谁”。**

---

## 1. 重构目标

### 1.1 必须达到的目标

1. 新建会话、历史会话加载、多会话并行、切换会话完全正确。
2. 会话内修改标题、撤销/重做、fork 消息、上传附件、引用文件上下文、token 消耗、文件上下文开关、模型选择、模式选择、推理力度选择、bash 命令、opencode 命令、`@` 引用文件上下文全部会话隔离。
3. CPU 和内存不随 tab 数量线性失控；后台会话不触发活动视图重渲染。
4. JCEF 不因大 payload、IPC 饱和、主线程解析、EDT/CEF 阻塞而卡 UI、冻结、白屏。
5. 没有“猜当前会话”的路径；缺失身份必须拒绝并计数，而不是回落。

### 1.2 明确不做

1. 不为旧 `switch_session`、旧权限文件协议、旧 `setSessionId(sessionId, oldSessionId)` 保留兼容层。
2. 不在 IDE 重启后恢复 webview 内部 tab；启动固定一个新 tab。
3. 不把 `tabId` 当 `sessionId` 使用，也不把 `permissionServiceKey` 当会话身份。
4. 不通过“保存/恢复单份 ref”实现多会话；运行态必须按 tab 分区。
5. 不通过“当前活动 tab”推断任何消息归属。

---

## 2. 现状根因

### 2.1 两代架构叠加

项目从 `jetbrains-cc-gui` 的单会话 GUI 演化而来：

```
一个原生 Content tab
  └─ 一个 ChatWindow
       └─ 一个 webview
            └─ 一个会话
                 ├─ 单份 streaming refs
                 ├─ switch_session 换会话
                 └─ 文件 IPC 权限
```

后来增加了“一个 webview 内多个会话 tab”，但没有替换身份模型，于是出现：

```
一个原生 Content tab
  └─ 一个 ChatWindow
       └─ 一个 webview
            ├─ tab A -> session A
            ├─ tab B -> session B
            └─ 共享：单份 refs / 单份 activeSessionId / 单份 session 字段
```

### 2.2 核心缺陷清单

| # | 缺陷 | 证据 | 后果 |
|---|---|---|---|
| 1 | `tabId`、UUID、`permissionServiceKey`、真实 `ses_*` 混用 | `OpencodeBuddyChatWindow.java:1620-1653`、`3646-3648` | 路由靠猜，reload 出现幽灵 tab |
| 2 | 首条消息不切换 host 当前会话 | `createSessionForTab` 只在 `session == null` 时切换，`:1546-1549` | 模型、usage、持久化、通知指向旧会话 |
| 3 | `create_new_session` 随机 UUID + `setupSessionCallbacks` 二次注册 | `SessionLifecycleManager.java:117-148`、`:488-504` | 真实 `ses_*` 不进入 map，UI 无法绑定 |
| 4 | 权限请求带 sessionId，回复只带 channelId | `PermissionHandler.java:327-380` | 后台权限依赖 registry 补，路径不对称 |
| 5 | `switch_session` 混合三种职责 | `OpencodeBuddyChatWindow.java:1707-1745` | 切 tab 触发 hydrate/reconcile，产生竞态 |
| 6 | 单份 streaming refs | `useStreamingMessages.ts:59-80`、`streamingRuntime.ts` | 切到执行中的 tab 串文本、串位置 |
| 7 | `claimUnboundTab` 猜测归属 | `useSessionTabs.ts:464` 及 streaming/message callbacks | 迟到事件污染新空白 tab |
| 8 | `getHostedSessionIds` 用 coalescer keys | `OpencodeBuddyChatWindow.java:3646-3648` | reload 用伪会话重建 tab |
| 9 | `restoreSessionTabs` 忽略 activeTabId | `useSessionTabs.ts:606-653` | reload 后活动 tab 错 |
| 10 | `currentSessionId ?? tab.sessionId` 阻止解绑 | `useSessionTabs.ts:294` | 新建会话仍带着旧 sessionId |
| 11 | 早期 `setSessionId` 占位无 drain | `main.tsx:534-541` | React 挂载前身份丢失 |
| 12 | 权限文件 IPC 与 SSE 权限并存 | `PermissionService.java`、`OpenCodeMessageHandler.java:270-386` | 两套 future、两套清理、两套路由 |
| 13 | 全量 `updateMessages` 与窗口级 event queue | `StreamMessageCoalescer.java`、`WebviewEventQueue.java` | 大 payload 阻塞其他 tab 的 delta |
| 14 | 关闭 tab 的资源清理不完整 | adapter/sink/coalescer/pending prompts | 内存和后台回调泄漏 |

### 2.3 必须删除的旧机制

以下机制在目标架构中不允许存在：

- `permissionServiceKey` 作为任何会话 id。
- `sessions` / `sessionCoalescers` key 混用。
- `create_new_session` 预生成随机 UUID。
- `switch_session`。
- `claimUnboundTab`。
- `setSessionId(newId, oldId)` 旧签名。
- `getHostedSessionIds()` 从 coalescer key 推断。
- `__pendingSessionId` 占位。
- 以 `context.getSession()` 作为多会话命令的归属依据。
- `runtimeSnapshotsRef` 的“切 tab 保存/恢复 ref”作为最终模型。
- 文件 IPC 权限体系。
- IDE 重启恢复内部 tab 的 `TabStateService` 会话绑定。

---

## 3. 目标架构总览

```
┌─────────────────────────────────────────────────────────────────────┐
│ IDEA Host                                                           │
│                                                                     │
│  TabRegistry<TabId, TabRecord>                                      │
│    TabRecord                                                         │
│      tabId: TabId                                                    │
│      sessionId: SessionId?                                           │
│      session: TabSession                                             │
│      router: TabStreamRouter                                         │
│      actor: TabSessionActor                                          │
│      settings: TabSettings                                           │
│      prompts: PendingPromptSet                                       │
│      attachments: TabAttachmentStore                                 │
│                                                                     │
│  activeTabId: TabId   // 仅通知/窗口标题，不参与消息路由              │
│                                                                     │
│  TabCommandRouter  ──▶ TabSessionActor ──▶ OpenCodeSDKBridge        │
│  TabStreamRouter   ◀── DaemonBridge (session sink)                   │
│  TabEventBus       ──▶ BridgeScheduler ──▶ JCEF executeJavaScript    │
│  PromptBroker      ──▶ Permission/Question 回复                       │
└─────────────────────────────────────────────────────────────────────┘
              │                                        ▲
              │ session envelope                       │ NDJSON
              ▼                                        │
┌─────────────────────────────────────────────────────────────────────┐
│ opencode-buddy-core                                                 │
│  SSE(per directory) ──▶ _handleEvent(sessionID, turnID, seq)         │
│    ──▶ markers {[CONTENT_DELTA], [MESSAGE], [TURN_END], ...}        │
│    ──▶ stdout envelope {id, session, turn, seq, line}               │
└─────────────────────────────────────────────────────────────────────┘
              │
              │ window.__opencodeDispatch([events])
              ▼
┌─────────────────────────────────────────────────────────────────────┐
│ Webview                                                             │
│  TabStore (per TabId)                                               │
│    messages / runtime / draft / attachments / settings / prompts     │
│                                                                     │
│  HostDispatcher ──▶ TabStore.apply(event)                           │
│  TabView(activeTabId) ──▶ MessageList / ChatInput / StatusPanel      │
│  background tab: 只写 store，不触发活动视图 render                    │
└─────────────────────────────────────────────────────────────────────┘
```

### 3.1 分层职责

| 层 | 唯一职责 | 不允许 |
|---|---|---|
| core SSE | 把 SSE 翻译成带身份的 marker | 任何“当前会话”概念 |
| daemon 传输 | 给每行贴 `{id, session, turn, seq}` 信封 | 借用其他请求的 session |
| host router | 按 `sessionId`/`tabId` 把行路由到唯一 Tab | 按 active tab 兜底 |
| TabSessionActor | 按 Tab 串行处理消息/状态变更 | 跨 Tab 共享可变状态 |
| TabEventBus | 把宿主事件映射成带 Tab 的 bridge 事件 | 在桥接层判断“该给谁” |
| webview TabStore | 按 TabId 存全部 UI 运行态 | 全局单槽、当前会话回落 |
| webview view | 只渲染 activeTabId 指向的 store | 写会话归属 |

---

## 4. 身份模型与不变式

### 4.1 四种 ID

| ID | 类型 | 产生者 | 生命周期 | 用途 |
|---|---|---|---|---|
| `tabId` | 稳定 UUID | webview 创建 tab 时 | tab 存在期间不变 | 所有 UI/宿主路由主键 |
| `sessionId` | `ses_*` | opencode server | 会话存在期间不变 | 服务端会话身份，Tab 的可空绑定 |
| `turnId` | 服务端 `durable.seq` | core | 一个回合 | 回合终态幂等、乱序拒绝 |
| `requestId` | 每命令 UUID | webview/host | 一次请求 | 控制面请求-回执配对，不是会话归属 |

### 4.2 强类型

宿主用强类型阻止混用：

```java
public record TabId(String value) {
    public TabId { requireNonBlank(value); }
}

public record SessionId(String value) {
    public SessionId { requireNonBlank(value); }
}

public record TurnId(long value) {}
```

`TabRecord.sessionId` 是 `@Nullable SessionId`，绝不存放 UUID、`tabId`、`permissionServiceKey`。

前端使用 branded type：

```ts
export type TabId = string & { readonly __brand: 'TabId' };
export type SessionId = string & { readonly __brand: 'SessionId' };
export type TurnId = number & { readonly __brand: 'TurnId' };
export type MessageId = string & { readonly __brand: 'MessageId' };
```

### 4.3 全局不变式

| ID | 不变式 | 验证方式 |
|---|---|---|
| INV-1 | 所有会话级跨层事件必须带 `tabId`；宿主→webview 会话事件同时带 `sessionId` | 协议 schema 校验；单测 |
| INV-2 | `sessionId` 只能是真实 `ses_*` 或 null；禁止 UUID/tabId/permission key | 类型 + 静态检查 |
| INV-3 | 一个 tab 最多绑定一个 session；一个 session 最多属于一个 tab | `TabRegistry` 原子检查 |
| INV-4 | activeTabId 只影响视图和窗口级通知，不参与消息、权限、命令路由 | grep 检查 + 并发测试 |
| INV-5 | 回合终态按 `(tabId, turnId)` 幂等；迟到/重复终态不改变状态 | 单测 |
| INV-6 | 关闭 tab 必须释放 session、sink、actor、coalescer、prompts、attachments、缓存 | 泄漏测试 |
| INV-7 | 缺失身份的事件必须丢弃并计数，不允许回落 | 单测 + 监控 |
| INV-8 | 重载后 `tabId` 不变；旧 epoch 事件必须被拒绝 | reload 测试 |

### 4.4 状态所有权

| 状态 | 唯一 owner | 生命周期 |
|---|---|---|
| tab 集合、activeTabId | webview `TabStore` | 窗口 |
| tab 的宿主记录 | 宿主 `TabRegistry` | tab 存在 |
| 每 tab 消息 | 宿主 `TabRecord.state` + webview `TabStore.messages`（镜像） | tab 存在 |
| 流式运行态 | webview `TabRuntimeStore[tabId]` | tab 存在 |
| loading / thinking / status / turn | 每 tab Runtime | 每回合 |
| 标题 | 宿主 `TabRecord.title` + webview 镜像 | tab 存在 |
| 模型 / 模式 / 推理力度 | 宿主 `TabSettings` + webview 镜像 | tab 存在 |
| token / context 使用量 | 宿主 session state + webview 镜像 | 每回合/每 tab |
| 文件上下文开关 | `TabSettings.contextEnabled` | tab 存在 |
| 任务 / Todo 面板 | webview `TabStatusStore[tabId].todos` + 宿主事件路由 | tab 存在 |
| 子代理列表 / 子代理 transcript | webview `TabStatusStore[tabId].subagents/histories` + 宿主事件路由 | tab 存在 |
| 编辑（文件变更）面板 | webview `TabStatusStore[tabId].fileChanges` + 宿主事件路由 | tab 存在 |
| 任务事件（task_*） | webview `TabStatusStore[tabId].taskEvents` | tab 存在 |
| 状态面板展开/选中子页 | webview `TabStatusStore[tabId].panelState` | tab 存在 |
| 草稿 / 附件 / 文件引用 | webview `TabDraftStore[tabId]` | tab 存在 |
| pending permission/question/plan | 宿主 `PromptBroker` + webview `TabPromptStore[tabId]` | 请求存在 |
| revert boundary / fork 元数据 | `TabRecord` + webview 镜像 | tab 存在 |
| 服务端历史 | opencode server | 长期 |

---

## 5. 宿主端设计

### 5.1 `TabRecord`

```java
final class TabRecord {
    final TabId tabId;
    final String cwd;

    @Nullable SessionId sessionId;
    @Nullable TabSession session;

    final TabSessionActor actor;
    final TabStreamRouter router;
    final TabEventSink eventSink;

    TabState state;              // NEW / CREATING / BOUND / LOADING_HISTORY / STREAMING / CLOSED
    TabSettings settings;        // model / mode / reasoning / contextEnabled / titleSource
    TabDraft hostDraft;          // 仅宿主需要的发送快照，不作为 UI 草稿 owner
    PendingPromptSet prompts;
    TabAttachmentStore attachments;

    long epoch;                  // 每次重载/重新绑定递增
    long revision;               // 宿主→webview 事件序号
}
```

`TabRecord` 不允许有“当前是否 active”字段。active 由 webview 的 `activeTabId` 决定；宿主只保留 `activeTabId` 用于窗口标题、通知和 IDEA 原生标签状态，不参与路由。

核心附属类型：

```java
final class TabSettings {
    String provider;
    String model;
    String permissionMode;
    String reasoningEffort;
    boolean contextEnabled;
    boolean autoOpenFileEnabled;
    String title;
    TitleSource titleSource; // USER / AUTO
}

final class TabUsage {
    int usedTokens;
    int maxTokens;
    double percentage;
    Double costUsd;
    long updatedAt;
}

final class TabDraft {
    String text;
    List<AttachmentRef> attachments;
    List<FileReference> fileTags;
    List<QuoteRef> quotes;
}
```

`TabDraft` 在宿主侧只作为“发送输入的稳定快照/校验材料”，真正的草稿 owner 是 webview 的 `TabDraftStore`；宿主不得用 active tab 覆盖其他 tab 的草稿。

### 5.2 `TabState` 状态机

```
NEW
  ├─ session.send（首次）──▶ CREATING
  │                          ├─ [SESSION_ID] ──▶ BOUND
  │                          └─ error ──▶ NEW
  ├─ session.load ──▶ LOADING_HISTORY ──▶ BOUND
  └─ tab.close ──▶ CLOSED

BOUND
  ├─ turn.start ──▶ STREAMING
  ├─ turn.end ──▶ BOUND
  ├─ session.load（空 tab）──▶ LOADING_HISTORY
  └─ tab.close ──▶ CLOSED

STREAMING
  ├─ turn.end ──▶ BOUND
  ├─ interrupt ──▶ BOUND（等终态）
  └─ tab.close ──▶ CLOSED（先 abort）
```

规则：

- `NEW` 没有 session，不创建空 `OpencodeSession` 作为伪会话。
- `CREATING` 只允许一个 in-flight send；后续发送在 UI 层排队或拒绝。
- `BOUND` 是唯一允许执行会话命令的状态。
- `STREAMING` 不允许并发 send；`interrupt` 允许。
- `CLOSED` 后任何事件丢弃并计数。

### 5.3 `TabRegistry`

```java
final class TabRegistry {
    Map<TabId, TabRecord> tabs;
    Map<SessionId, TabId> sessionIndex;
    TabId activeTabId;

    TabRecord create(TabId tabId, String cwd);
    TabRecord require(TabId tabId);
    @Nullable TabRecord findBySession(SessionId sessionId);

    void bind(TabId tabId, SessionId sessionId); // 原子；拒绝重复绑定
    void unbind(TabId tabId);
    void activate(TabId tabId);                  // 只影响窗口通知
    void close(TabId tabId);                     // 全量释放

    List<TabSnapshot> snapshots();
}
```

必须使用 `synchronized` 或单线程 actor 保护注册表。`bind` 必须：

1. 校验 tab 存在且未绑定其他 session；
2. 校验 session 未被其他 tab 占用；
3. 写入 `tab.sessionId` 和 `sessionIndex`；
4. 注册 daemon sink；
5. 递增 `epoch`；
6. 发出 `session.bound`。

失败则回滚，不允许中途半绑定。

### 5.4 `TabSessionActor`

每个 tab 一个串行执行器，处理：

- 发送请求；
- SSE marker / stream line；
- 权限/问题事件；
- 标题/usage/revert 更新；
- 回合终态；
- 关闭。

```java
final class TabSessionActor {
    void submit(TabTask task);
    void close();
}
```

为什么需要 actor：

- 当前 SSE reader 线程、EDT、coalescer 线程同时改 `SessionState`，顺序靠零散锁和 `invokeLater`。
- 多会话下，`message.removed`、`turn.end`、delta 可能交错。
- actor 保证**同一 tab 内**顺序，Tab 之间并行；这是“多会话并行 + 单会话有序”的正确模型。

actor 内只做内存状态变更和生成不可变快照；JSON 序列化、磁盘、JCEF 调用均投递到后续执行器，不能在 actor 内做重活。

### 5.5 命令路由

所有宿主命令先经过 `TabCommandRouter`：

```java
void handle(TabCommand command) {
    TabRecord tab = registry.require(command.tabId());
    tab.actor.submit(() -> dispatch(command, tab));
}
```

`TabCommand` 必须带 `tabId`。禁止：

- `context.getSession()`；
- `WindowEventHandler.sessionIdOrEmpty()`；
- 从 active tab 推断目标会话。

新增/改造命令：

| 命令 | 目标 | 说明 |
|---|---|---|
| `tab.create` | TabRegistry | 创建空 tab，不创建 session |
| `tab.close` | TabRegistry | 中断、释放 |
| `tab.activate` | activeTabId | 只用于窗口通知 |
| `tab.title.set` | TabRecord | 用户标题 |
| `session.send` | TabSessionActor | 首次发送可创建 session |
| `session.interrupt` | TabSessionActor | 必须带 tabId |
| `session.load` | TabSessionFactory | 绑定历史会话 |
| `session.fork` | TabSessionFactory | 目标 tab 预创建 |
| `session.revert` / `unrevert` | TabSessionActor | per-tab |
| `session.compact` | TabSessionActor | per-tab |
| `session.rename` | TabSessionActor | per-tab |
| `settings.model` / `mode` / `reasoning` | TabRecord | 未绑定则存 pending settings |
| `context.toggle` / `refresh` | TabRecord | per-tab |
| `attachment.prepare` | TabAttachmentStore | per-tab |
| `commands.list` | TabRecord | 按 cwd 缓存 |
| `permission.reply` | PromptBroker | 带 tabId/sessionId/requestId |
| `question.reply` | PromptBroker | 同上 |
| `plan.reply` | PromptBroker | 同上 |

### 5.6 会话创建：不再有随机 UUID

#### 首次发送流程

1. UI 在本地 tab `tabId` 上发送 `session.send`，`sessionId = null`。
2. 宿主 `TabRegistry.require(tabId)`。
3. `TabSessionFactory.createForTab(tab)`：
   - 创建 `OpencodeSession`，但**不赋予任何假 sessionId**；
   - 创建 `TabStreamRouter` 和 `TabSessionActor`；
   - 创建一次性的 `PendingSend`，以 `requestId` 关联到该 tab。
4. 宿主调用 core `opencode.send`，参数带 `requestId` 和 `sessionId=null`。
5. core 创建/复用服务端 session，首行发 `[SESSION_ID] ses_x`。
6. 宿主 router 收到 `[SESSION_ID]`：
   - 调用 `TabRegistry.bind(tabId, ses_x)`；
   - 注册 daemon session sink；
   - 递增 epoch；
   - 发 `session.bound { tabId, sessionId }`；
   - 把此前早到的行（如果有）按顺序交给同一 router。
7. 后续所有事件都带 `session=ses_x`，通过 sink 进入该 tab。

**关键**：`sessionId=null` 期间只允许用 `requestId` 路由**该次发送的 bootstrap 行**，不允许用 active tab 或 `permissionServiceKey` 兜底。

#### 为什么不在宿主预生成 UUID

- UUID 不是 opencode session，进入任何 session map 都是污染。
- UI 无法在 `setSessionId` 中匹配它。
- core 已经能创建会话并返回 `ses_*`，没有必要预生成。
- 如果 UI 在会话创建前需要展示“新会话”标题，使用 tab 标题即可，不需要 sessionId。

### 5.7 历史会话加载

`session.load` 只允许：

- `tab.state == NEW` 或空 tab（无消息、无附件、无 active turn）；
- 目标 session 未被其他 tab 绑定。

流程：

1. UI 决定复用当前空 tab 或创建新 tab（策略见 §8）。
2. `TabSessionFactory.attach(tab, sessionId, cwd, modelOverride)`：
   - 创建 `OpencodeSession`；
   - `bind(tabId, sessionId)`；
   - `preconnect(sessionId, cwd)`；
   - 从服务端加载历史；
   - 通过 `TabEventBus` 发送 `message.snapshot`；
   - 发 `session.bound` + `session.loaded`。
3. 不调用 `switchSession`，不修改宿主 active 指针。
4. UI 自己把目标 tab 设为 active。

### 5.8 关闭 Tab

`TabRegistry.close(tabId)`：

1. 标记 `CLOSED`，停止接收新命令。
2. 若 `STREAMING`：先 `interrupt(sessionId)`；超时后强制清理。
3. 拒绝该 tab 的所有 pending prompts（发送 `prompt.closed`）。
4. 取消 safety net 定时器。
5. `daemonBridge.unregisterSessionSink(sessionId)`。
6. `actor.close()`、`router.dispose()`、`coalescer.dispose()`。
7. 删除 `sessionIndex`、附件临时文件、消息缓存、序列化缓存。
8. 发出 `tab.closed`。
9. 若这是最后一个 UI tab：UI 创建一个新的空 tab（不恢复旧会话）。

关闭必须幂等；并发关闭同一 tab 只执行一次。

### 5.9 删除旧类

删除或替换：

- `permission/PermissionService.java`
- `permission/PermissionManager.java`
- `permission/PermissionFileProtocol.java`
- `permission/PermissionRequestWatcher.java`
- `permission/PermissionDecisionStore.java`
- `permission/PermissionDialogRouter.java`
- `permission/PermissionSessionRegistry.java`
- `settings/TabStateService.java` 的 session 绑定与恢复用法
- `TabSessionRestorePolicy.java`
- `SessionLifecycleManager` 的 `createNewSession` / `switchSession` 路径
- `OpencodeBuddyChatWindow` 的 `session` / `activeSessionId` / `sessionId` / `permissionServiceKey` 单槽字段

保留 `PermissionRequest` 作为 UI DTO，但改由 `PromptBroker` 创建和消费。

---

## 6. Webview 端设计

### 6.1 单一 Host Dispatcher

不再注册几十个 `window.xxx` 回调。启动早期注册一个：

```ts
window.__opencodeDispatch = (json: string) => {
  hostDispatcher.enqueue(json);
};
```

Java 侧所有事件都变成：

```json
[
  {
    "v": 2,
    "epoch": 17,
    "type": "content.delta",
    "tabId": "tab-uuid",
    "sessionId": "ses_x",
    "turnId": 1042,
    "seq": 1047,
    "payload": { "delta": "hello" }
  }
]
```

优点：

- 身份统一，新增事件不需要新增全局函数。
- 早期事件可以先进入 `PendingEventQueue`，React 挂载后 drain，不再需要 `__pendingSessionId`。
- 宿主可以按 tab 批量、合并、限流。
- 测试只需驱动一个 dispatcher。

`HostDispatcher` 规则：

- 接受单个事件对象或事件数组；数组按顺序应用。
- 校验 `v/type/tabId/epoch`。
- 丢弃 `epoch < currentEpoch` 的事件。
- 无 `tabId` 的非全局事件丢弃并计数。
- `tab.created` 之前到达的 tab 事件进入 pending（有上限 + TTL）。
- 全局事件只允许：`tab.resync`、`daemon.status`、`ui.preferences`、`theme.changed`、`update.notice`。其他事件一律要求 `tabId`。

### 6.2 `TabStore`

```ts
interface TabState {
  tabId: TabId;
  sessionId: SessionId | null;
  title: string;
  titleSource: 'user' | 'auto';
  status: 'new' | 'creating' | 'bound' | 'loading' | 'streaming' | 'closed';
  messages: ReadonlyArray<Message>;
  loaded: boolean;

  runtime: TabRuntime;
  draft: TabDraft;
  settings: TabSettings;
  usage: TabUsage;
  context: TabContextState;
  revert: RevertState;
  fileChanges: FileChange[];
  prompts: TabPromptState;

  revision: number;
  lastSeq: number;
}
```

`TabStore` 是唯一会话状态容器：

```ts
class TabStore {
  get(tabId: TabId): TabState;
  create(tabId: TabId, cwd: string): void;
  bind(tabId: TabId, sessionId: SessionId): void;
  apply(event: HostEvent): void;
  close(tabId: TabId): void;
  subscribe(tabId: TabId, listener: () => void): () => void;
}
```

实现建议：

- `Map<TabId, TabState>`；
- 每个 tab 一个版本号；
- 事件只更新目标 tab；
- 不可变更新，但保留未变消息的引用；
- 不对其他 tab 做 `setState`。

### 6.3 运行态按 Tab 存储

删除单份 `streamingContentRef` / `isStreamingRef` / `streamingMessageIndexRef` / `streamingTurnIdRef`。

改为：

```ts
interface TabRuntime {
  isStreaming: boolean;
  content: string;
  thinking: string;
  turnId: TurnId | null;
  messageIndex: number;
  lastActivityAt: number;
  lastContentFlushAt: number;
  lastThinkingFlushAt: number;
  autoExpandedThinkingKeys: Set<string>;
  trailingStructuralBoundary: Boundary | null;
  stalled: boolean;
}
```

- 每个 tab 独立累加。
- 后台 tab 的 delta 只改 `runtime` 和 `messages`，不触发 active view。
- 切 tab 只改 `activeTabId`，不搬运任何运行态。
- 重载后由宿主 snapshot + `turn.started` 恢复；不在前端做 ref 快照。

### 6.4 视图订阅

- `useActiveTab()` 只订阅 `activeTabId`。
- `useTabMessages(tabId)` 通过 `useSyncExternalStore` 订阅单个 tab。
- `useTabRuntime(tabId)` 只订阅该 tab 的流式字段。
- `MessageList`、`StatusPanel`、`ChatInputBox` 分别订阅所需切片，避免一个大 context 触发全树重渲染。

不得再使用全局 `MessagesContext` 承载所有会话状态。`MessagesContext` 可以保留为 UI 级临时状态，但不能作为会话消息 owner。

### 6.5 消息存储

建议拆成：

```ts
interface TabMessageStore {
  order: MessageId[];
  byId: Map<MessageId, Message>;
  streamingAssistantId: MessageId | null;
}
```

要求：

- snapshot 用 id 对账，不按数组下标合并。
- `updateMessageTail` 只替换尾部。
- `content.delta` 只更新 streaming assistant。
- `message.removed` 按 id 删除。
- `turn.ended` 清 streaming，不依赖“是否 active”。
- 保留 `messageSync` 中的乐观消息、防 shrink、防重复 tool 尾部等纯函数，但输入输出改为 per-tab store。

### 6.6 草稿与输入状态

每个 tab 独立保存：

- 文本；
- 附件；
- `@` 文件引用 chips；
- quotes；
- 光标/选择区；
- 滚动位置；
- 输入框高度；
- 发送队列。

切换 tab 恢复草稿只操作目标 tab，不读写其他 tab。

### 6.7 删除旧机制

- `runtimeSnapshotsRef`
- `captureStreamingRuntime` / `restoreStreamingRuntime` 的切 tab 用途
- `currentSessionIdRef` 的会话归属用途（可保留给纯 UI 兼容，但不可作为路由依据）
- `claimUnboundTab`
- `__sessionTransitioning` 全局锁
- `__pendingSessionId`
- `setSessionId(sessionId, oldSessionId)`
- `restoreSessionTabs` 的“用 sessionId 当 tab 身份”逻辑
- `updateBackgroundTab` 的谓词式猜测更新，改为 `store.update(tabId)`

---

## 7. 通信协议

### 7.1 Webview → Host

统一信封：

```json
{
  "v": 2,
  "type": "session.send",
  "requestId": "req-uuid",
  "tabId": "tab-uuid",
  "sessionId": "ses_x" | null,
  "payload": {}
}
```

宿主校验：

1. `v == 2`；
2. `tabId` 存在；
3. `sessionId` 如果非空，必须等于该 tab 的绑定；
4. `requestId` 未被处理过（幂等）；
5. payload schema 合法。

非法请求返回：

```json
{
  "type": "command.rejected",
  "tabId": "tab-uuid",
  "requestId": "req-uuid",
  "payload": { "code": "TAB_NOT_FOUND", "message": "..." }
}
```

### 7.2 Host → Webview

统一信封：

```json
{
  "v": 2,
  "epoch": 17,
  "type": "content.delta",
  "tabId": "tab-uuid",
  "sessionId": "ses_x",
  "turnId": 1042,
  "seq": 1047,
  "payload": { "delta": "hello" }
}
```

规则：

- 会话级事件必须带 `tabId` + `sessionId`。
- `turn.*` 事件必须带 `turnId`。
- 快照事件带 `revision`。
- `epoch` 在 reload/resync 后递增，UI 丢弃旧 epoch。
- 事件批量发送：`window.__opencodeDispatch(JSON.stringify(events))`。

### 7.3 事件目录

#### 生命周期

| type | 方向 | 关键字段 | 说明 |
|---|---|---|---|
| `tab.created` | H→W | tabId | 宿主持有该 tab |
| `tab.closed` | H→W | tabId | 宿主已释放 |
| `tab.activated` | W→H | tabId | 只用于窗口标题/通知 |
| `session.bound` | H→W | tabId, sessionId | 真实 session 绑定 |
| `session.loaded` | H→W | tabId, sessionId | 历史加载完成 |
| `session.loading` | H→W | tabId, loading | 历史/恢复状态 |
| `tab.title.changed` | H→W | tabId, title, source | 标题 |
| `tab.resync` | H→W | epoch, tabs[] | webview reload 恢复 |
| `command.rejected` | H→W | tabId, requestId, code | 命令失败 |

#### 回合与消息

| type | 方向 | 关键字段 | 说明 |
|---|---|---|---|
| `turn.started` | H→W | tabId, sessionId, turnId | 开始 |
| `content.delta` | H→W | tabId, sessionId, turnId, seq, delta | 文本增量 |
| `thinking.delta` | H→W | tabId, sessionId, turnId, seq, delta | 思考增量 |
| `message.snapshot` | H→W | tabId, sessionId, revision, messages, baseIndex | 权威快照 |
| `message.tail` | H→W | tabId, sessionId, revision, baseIndex, messages | 尾部快照 |
| `message.removed` | H→W | tabId, sessionId, ids[] | 删除 |
| `turn.ended` | H→W | tabId, sessionId, turnId, reason | 终态 |
| `usage.updated` | H→W | tabId, sessionId, usage | token |
| `status.updated` | H→W | tabId, sessionId, status | 状态栏 |
| `error` | H→W | tabId, sessionId, code, message | 错误 |

#### 功能状态

| type | 方向 | 关键字段 | 说明 |
|---|---|---|---|
| `settings.changed` | H→W | tabId, model/mode/reasoning | 确认/回滚 |
| `context.updated` | H→W | tabId, enabled, context | 编辑器上下文 |
| `todos.updated` | H→W | tabId, sessionId, todos | 任务面板 |
| `subagent.updated` | H→W | tabId, sessionId, toolUseId, status, summary | 子代理列表 |
| `subagent.history` | H→W | tabId, sessionId, toolUseId, messages, completed | 子代理 transcript |
| `task.event` | H→W | tabId, sessionId, toolUseId, status, summary | task_* 事件 |
| `fileChanges.updated` | H→W | tabId, sessionId, changes | 编辑面板 |
| `revert.changed` | H→W | tabId, sessionId, boundary | undo/redo |
| `fork.completed` | H→W | sourceTabId, targetTabId, sessionId | fork |
| `attachment.prepared` | H→W | tabId, attachmentId, path | 附件就绪 |

#### 权限/问题

| type | 方向 | 关键字段 | 说明 |
|---|---|---|---|
| `permission.requested` | H→W | tabId, sessionId, requestId, data | 权限弹窗 |
| `question.requested` | H→W | tabId, sessionId, requestId, data | 提问弹窗 |
| `plan.requested` | H→W | tabId, sessionId, requestId, data | 计划审批 |
| `prompt.closed` | H→W | tabId, sessionId, requestId, reason | 服务端关闭/超时 |
| `prompt.rejected` | H→W | tabId, requestId, reason | 回复失败 |

### 7.4 快照与增量

- 文本/思考使用 delta。
- 工具块、usage、revert、消息删除使用权威快照/事件。
- 快照必须带 `revision`；UI 只接受 `revision > current`。
- 超过 30 条消息优先 `message.tail`。
- 大历史分页：`message.snapshot` 带 `baseIndex` + `total`，UI 可请求更早页。
- 如果 delta 因队列溢出被丢弃，宿主发 `snapshot.required`，UI 请求 `session.hydrate {tabId}`。

### 7.5 顺序与幂等

- `seq`：会话级单调，来自 `durable.seq`；用于丢弃乱序 delta。
- `turnId`：回合级；用于终态幂等。
- `revision`：宿主该 tab 的快照版本；用于 reload 后拒绝旧快照。
- `epoch`：webview 重建代数；用于拒绝 reload 前的事件。
- 重复 `turn.ended` 只处理一次。
- 重复 `permission.reply` 只生效一次。

---

## 8. SSE / core 链路

### 8.1 core 保持现有方向

core 已经基本完成“身份信封”重构，保留：

- 每 directory 一条 SSE 订阅；
- `_activeTurns` 按 `sessionID`；
- `_handleEvent` 从 `data.sessionID` / `properties.sessionID` 取身份；
- `turnId` = 本回合首个 `durable.seq`；
- `requestContext.run({id, sessionId, turnId, seq}, ...)`；
- marker 发射；
- `process.stdout.write` 统一加 `{id, session, turn, seq, line}`。

### 8.2 需要补齐的 core 约束

1. 新会话创建阶段：在 `[SESSION_ID]` 之前，允许 `session=null`，但该行必须带 `id`；宿主只允许用该 `requestId` 路由到对应 tab 的 `PendingSend`。
2. `[SESSION_ID]` 必须尽早发出，并在同一 request 的后续行中带上 canonical `session`。
3. 所有回合终态必须发 `[TURN_END]`，不能只依赖 `[STREAM_END]`。
4. `permission.asked` / `question.asked` / `todo.updated` / `message.removed` / `session.updated` 即使没有 active turn 也必须带 session。
5. 无 session 且无 id 的数据行直接丢弃并计数，不进入 stdout 兜底。

### 8.3 宿主路由

`DaemonBridge`：

- `done` 按 `requestId` 完成 future。
- `line`：
  1. `session != null` 且有 sink → sink；
  2. `session == null` 且有 `requestId` → 该 request 的 `PendingSend`（仅新会话 bootstrap）；
  3. 其他 → orphan buffer + 计数。
- `stderr` 同样优先按 session sink，其次 request。
- `registerSessionSink(sessionId, sink)` 在 bind 时调用。
- `unregisterSessionSink` 在 close 时调用。
- 任何 orphan 都要暴露指标；UI 可在诊断面板显示。

### 8.4 回合终态

`[TURN_END] {sessionId, turnId, reason}`：

- 由 actor 处理；
- 只清目标 tab 的 streaming/loading；
- 不等同于 UI active；
- 重复/迟到按 `(tabId, turnId)` 幂等；
- 与 `[STREAM_END]`、`showLoading(false)` 并存时，先到者生效，其余忽略。

### 8.5 错误与断连

- `[TURN_ERROR]` / `[SEND_ERROR]` → 目标 tab error，不清其他 tab。
- SSE 断开 → core `_settleTurn` → `[TURN_END] reason=disconnected`。
- daemon 退出 → 宿主给所有 `STREAMING` tab 发 `turn.ended reason=daemon_restart`，保留会话历史，允许重发。
- 新会话创建超时（例如 30s 无 `[SESSION_ID]`）→ 失败并释放 `PendingSend`。

---

## 9. 功能流程

### 9.1 启动

1. webview 创建一个空 tab：`tabId = UUID`。
2. 发 `tab.create {tabId}`。
3. 宿主创建 `TabRecord`，`sessionId=null`，不创建 `OpencodeSession`，不启动权限文件服务。
4. 发 `tab.created`。
5. UI 显示空会话。
6. 没有 `activeSessionId`、没有 `permissionServiceKey`、没有伪 session。

### 9.2 新建会话

1. UI `新会话` 创建新 tab 或复用当前空 tab。
2. 发 `tab.create` + `tab.activate`。
3. 不调用宿主创建会话。
4. 首次发送时才创建真实 opencode session。
5. 如果用户在新 tab 选择模型/模式/推理力度，只写入该 tab 的 `settings`。

### 9.3 首次发送与绑定

1. UI 收集文本、附件、`@` 引用、上下文开关、模型/模式/推理力度。
2. `session.send { tabId, requestId, sessionId:null, payload }`。
3. 宿主 actor 创建 `PendingSend`、`OpencodeSession`、router。
4. core 创建 session，返回 `[SESSION_ID]`。
5. 宿主 `bind(tabId, sessionId)`，注册 sink，发 `session.bound`。
6. 后续 delta/snapshot 只进该 tab。
7. UI 将 `tab.sessionId` 设为真实 ID；不修改任何其他 tab。

### 9.4 历史会话加载

策略：

- 当前 active tab 为空（`NEW`、无消息、无附件、无 active turn）→ 复用它。
- 否则创建新 tab。
- 有 active turn 的 tab 禁止 replace。
- 一个 `sessionId` 只属于一个 tab。若该 session 已在其他 tab 打开，直接激活已有 tab，不再创建第二个绑定。

流程：

1. UI 发 `session.load { tabId, sessionId, cwd }`。
2. 宿主校验 session 未被占用。
3. `TabSessionFactory.attach`，bind，preconnect，加载历史。
4. 发 `session.bound` + `message.snapshot` + `session.loaded`。
5. UI 激活该 tab。
6. 不调用 `switch_session`，不改宿主 active 指针。

### 9.5 多会话并行

- core 按 session 维护 turn 队列；不同 session 可并行。
- 宿主每个 tab 一个 actor/router/coalescer。
- webview 每个 tab 一个 runtime。
- active tab 只决定渲染。
- 后台 session 的 turn 终态照样清自己的 loading。
- 任何 tab 的 delta 不写其他 tab 的 store。

### 9.6 会话内修改标题

1. UI 本地更新该 tab 标题，标记 `titleSource='user'`。
2. 发 `session.rename { tabId, title }`。
3. 宿主更新 `TabRecord.title`，调用 core `session.rename`。
4. core/SSE 返回 `[SESSION_TITLE]`，宿主按 session→tab 更新。
5. 发 `tab.title.changed { tabId, title, source }`。
6. 自动标题事件不得覆盖 `titleSource='user'` 的标题。
7. 失败时发 `tab.title.changed` 回滚旧标题。

### 9.7 撤销 / 重做

1. UI 对某条消息发 `session.revert { tabId, messageId }`。
2. 宿主 actor 调用 core `opencode.revert`，携带该 tab 的 sessionId。
3. SSE `[REVERT_STATE]` → 宿主更新该 tab 的 `revertBoundaryId`，发 `revert.changed`。
4. UI 只在该 tab 隐藏边界后的消息，显示 restore bar。
5. 下一次发送时服务端 `message.removed`：
   - 宿主按 session→tab 删除消息；
   - 发 `message.removed { tabId, ids }`；
   - UI 从该 tab 删除，不能影响其他 tab。
6. `unrevert` 同理，只作用于该 tab。

### 9.8 Fork 消息

1. UI 先创建目标 tab（`tab.create`），状态为空。
2. 发 `session.fork { sourceTabId, targetTabId, messageId }`。
3. 宿主校验 source/target，调用 core fork。
4. core 返回新 `sessionId`。
5. 宿主 bind target tab，加载 fork 后的历史，发 `session.bound` + snapshot。
6. UI 激活 target tab；source tab 完全不变。
7. 失败则关闭 target tab 并提示。

### 9.9 上传附件

1. UI 在 tab 草稿中选择文件，生成 `attachmentId`。
2. 大文件走 `attachment.prepare` 分块上传，宿主写入该 tab 临时目录。
3. 小文件可随 `session.send` 发送 base64，但必须有大小上限。
4. 宿主 `TabAttachmentStore` 记录 `attachmentId → path`，归属 tab。
5. 发送时把路径作为 file parts 传给 core。
6. 发送成功/失败后按策略清理临时文件；tab 关闭时强制清理。
7. 切换 tab 恢复该 tab 的附件草稿；不会串到其他 tab。
8. 附件大小、数量、类型限制在 UI 和宿主双重校验。

### 9.10 文件引用 / `@` 上下文

1. `@` 搜索请求带 `tabId`，宿主按该 tab 的 `cwd` 搜索。
2. UI 草稿保存 `fileTags`，切换 tab 恢复。
3. 发送时 `fileTags` 随 payload 走，宿主校验路径在该 tab workspace 内。
4. 上下文开关 `context.toggle { tabId, enabled }` 写入 `TabSettings`。
5. 编辑器当前文件/选区只在 active tab 刷新：
   - `context.refresh { tabId }`；
   - 宿主 `TabContextCollector` 生成快照；
   - 发 `context.updated { tabId }`。
6. 后台 tab 使用最后一次快照；切回时刷新。
7. 其他 tab 的上下文不会进入本次发送。

### 9.11 Token 消耗

1. core `[USAGE]` 带 sessionId。
2. 宿主映射到 tab，更新 `TabUsage`。
3. 发 `usage.updated { tabId, sessionId, used, max, percent, cost }`。
4. UI 按 tab 存；active status 只读 active tab。
5. 模型切换/新会话时只重置该 tab 的 usage。
6. 禁止 `UsagePushService` 使用 `context.getSession()`。

### 9.12 模型 / 模式 / 推理力度

1. UI 选择只更新该 tab。
2. 发 `settings.model / settings.mode / settings.reasoning { tabId, value }`。
3. 宿主写入 `TabSettings`；有 session 则应用到该 session，无 session 则等待 bind。
4. 发 `settings.changed` 确认；失败回滚。
5. `session.send` 必须携带当前 tab 的 settings 快照，避免异步命令竞态。
6. 模型列表缓存全局，但选择状态 per-tab。
7. 模式包括 default/build/plan 等；per-tab。
8. 推理力度 per-tab；新会话首次发送时随 payload 生效。

### 9.13 Bash 命令

1. UI 输入 `!command`。
2. 发 `command.run { tabId, kind:'bash', command }`。
3. 宿主要求 tab 已绑定或先创建 session。
4. core `opencode.shell`，sessionId 来自该 tab。
5. shell 输出按该 tab 的 turn 处理。
6. 命令历史/草稿 per-tab。

### 9.14 OpenCode 命令

1. UI 输入 `/command`。
2. `commands.list { tabId }` 按该 tab cwd 返回；缓存键为 cwd。
3. 发 `command.run { tabId, kind:'slash', command, args }`。
4. core `opencode.send` 或 `opencode.command`，sessionId 来自该 tab。
5. 输出只进该 tab。

### 9.15 切换 Tab

1. UI `setActiveTab(tabId)`。
2. 只更新 `activeTabId` 和视图选择器。
3. 发 `tab.activated { tabId }` 供宿主更新窗口标题/通知状态。
4. 不 hydrate、不 reconcile、不 switch_session、不搬消息。
5. 如果目标 tab 历史尚未加载，显示该 tab 自己的 loading，并发 `session.hydrate { tabId }`。

### 9.16 状态面板（任务 / 子代理 / 编辑）

1. 状态面板是 tab 的投影，不是窗口级全局状态。
2. 每个 tab 在 `TabStatusStore[tabId]` 中独立保存：
   - `todos`：`todo.updated` 快照；
   - `subagents`：由该 tab 消息派生，并用 `taskEvents` 修正终态；
   - `subagentHistories`：该 tab 的按需 transcript 缓存；
   - `fileChanges`：该 tab 的编辑列表与 pending 状态；
   - `panelState`：展开/收起、当前子页、展开的子代理行。
3. 宿主事件必须携带 `tabId` + `sessionId`；webview 只写目标 tab，后台 tab 事件不得写入 active tab。
4. 切换 tab 只切换视图选择器，不复制/合并列表；新绑定 session 只初始化自己的状态。
5. 关闭 tab 释放该 tab 的 transcript 缓存和展开状态。
6. 任务/子代理/编辑的 undo、discard、keep 操作必须带 `tabId`，返回结果只更新目标 tab。

### 9.17 关闭 Tab

1. UI 发 `tab.close { tabId }`。
2. 宿主中断、拒绝 prompts、释放 sink/actor/coalescer/附件。
3. UI 删除 store、runtime、draft、缓存，选择相邻 tab。
4. 最后一个 tab → 创建新的空 tab。
5. 关闭一个 tab 不得影响其他 tab 的流式输出或 pending prompts。

---

## 10. 权限 / 提问 / 计划

### 10.1 统一模型

删除文件 IPC 权限后，统一为：

```
SSE permission.asked
  -> core [PERMISSION_REQUEST] {sessionId, per_*, directory, ...}
  -> 宿主 PromptBroker
       PendingPrompt { tabId, sessionId, requestId, kind, directory, future, timeout }
  -> bridge permission.requested { tabId, ... }
  -> webview TabPromptStore[tabId]
  -> 用户答复
  -> bridge permission.reply { tabId, sessionId, requestId, decision }
  -> PromptBroker 校验并 replyPermission(sessionId, requestId, directory)
```

### 10.2 规则

1. 请求必须携带 sessionId；宿主用 `sessionIndex` 找 tab。
2. 没有 tab 的请求：如果 session 属于已关闭 tab，立即拒绝；如果还没有 bind，缓冲到该 session 对应的 `PendingSend`。
3. 请求到达时写入目标 tab 的 pending 队列，不管该 tab 是否 active。
4. active tab 有 pending 才弹窗；后台 tab 显示角标，不抢焦点。
5. 用户切换 tab 时，展示目标 tab 自己的队列。
6. 答复必须带 `tabId + sessionId + requestId`；宿主三重校验。
7. `permission.closed` / `question.closed` 只移除对应请求。
8. 超时只拒绝该请求，不调用全局 `clearPendingRequests`。
9. 关闭 tab 只拒绝该 tab 的 prompts。
10. 删除 `forceClose*Dialog` 的全局清空语义，改为按 requestId。

### 10.3 权限 UI

- 每个 tab 一个 `pendingPrompts` 队列。
- 弹窗组件接收 `tabId`、`requestId`。
- 用户选择后先本地标记，收到 `prompt.closed`/`prompt.rejected` 再回滚。
- 后台提示用 tab 角标 + 系统通知；不打开非活动 tab 的模态框。

---

## 11. 恢复与生命周期

### 11.1 webview reload

这是运行时恢复，不是 IDE 重启恢复。

1. 宿主检测 `frontend.ready`。
2. 发 `tab.resync { epoch, tabs: [{ tabId, sessionId, title, active }] }`。
3. UI 用**原 tabId** 重建 store，不能生成新 tabId。
4. 活动 tab 立即 `session.hydrate { tabId }`。
5. 非活动 tab 标记 `loaded=false`，首次激活再 hydrate。
6. 如果 reload 时该 tab 正在流式：
   - 宿主发 `turn.started` + `message.snapshot` + 当前 `streaming` 标记；
   - UI 恢复 runtime；后续 delta 继续应用；
   - 丢失的 delta 由 snapshot 补齐。
7. 旧 `epoch` 的事件丢弃。

### 11.2 IDE 重启

明确不恢复：

- 启动时只有一个新 tab。
- 删除 `TabStateService` 的 session 绑定。
- 不读取上次的 native tab 会话。
- 用户从历史列表重新打开旧会话。

### 11.3 daemon 重启

1. core 检测 SSE/serve 断开。
2. 所有 active turn settle 为 `disconnected`。
3. 宿主给每个 STREAMING tab 发 `turn.ended {reason:'daemon_restart'}`。
4. 保留 tab/session 历史和 UI 状态。
5. 允许用户重发；不自动重放未完成回合。

### 11.4 插件窗口关闭

- 关闭所有 tab。
- 中断所有 turns。
- 拒绝所有 prompts。
- 注销所有 sinks/adapters。
- 停止 core 进程/SSE（按项目生命周期管理）。
- 清空所有序列化缓存、附件临时文件。

### 11.5 资源释放清单

每个 tab 关闭时必须释放：

- `OpencodeSession` 和 `SessionState`
- `TabSessionActor`
- `TabStreamRouter`
- `SessionCallbackAdapter` / `StreamMessageCoalescer`
- daemon session sink
- pending prompts + safety timers
- attachment temp files
- message JSON cache
- webview store/runtime/draft
- React subscriptions
- OSR/surface pending 引用

用测试断言关闭后 `TabRegistry.size == 0`、sink map 为空、没有活动 actor。

---

## 12. 性能与 JCEF 防卡顿

### 12.1 性能预算

| 指标 | 目标 |
|---|---|
| 活动 tab delta 端到端延迟 | P95 < 150ms |
| 输入响应 | P99 < 50ms |
| 活动 tab 切回 | P95 < 100ms（已缓存） |
| 首次打开 tab（不含历史） | < 500ms |
| 主线程长任务 | 无 > 200ms；P99 < 50ms |
| 每 tab JS 调用频率 | < 30 次/秒 |
| 总事件队列 | 每 tab < 256；全局 < 1024 |
| 6 tabs / 3 并发流 | 不卡顿、无串会话 |
| 关闭 tab 后内存 | 可观测回落，无 actor/sink 泄漏 |

性能测试是准入门槛，不是“后续优化项”。

### 12.2 Host BridgeScheduler

替换窗口级 `WebviewEventQueue`：

```java
final class TabBridgeScheduler {
    Map<TabId, TabLane> lanes;
    void enqueue(TabEvent event);
    void drain();
}
```

规则：

- 每个 tab 独立 lane，避免一个 tab 的大快照阻塞其他 tab。
- active tab 权重 3，后台 tab 权重 1；轮询调度。
- delta 按 `(tabId, type)` 合并，最多保留最新字符串。
- snapshot 每 tab 最多保留 1 个；新 snapshot 替换旧 snapshot。
- 生命周期事件（start/end/turn_end/permission）不合并、优先。
- 每帧最多发送 32KB 或 64 个事件；超限拆分。
- 单事件 > 256KB 时优先改用 `message.tail` 或分页；强制发送时单独一帧。
- 队列满时丢 delta，标记 `snapshotRequired`，不丢终态。
- 所有 JSON 序列化在池线程；EDT/CEF 线程只执行 `executeJavaScript`。

### 12.3 流式合并

- delta：33ms 批量。
- snapshot：流式中 100ms；大 payload 自适应 250ms/1s。
- 后台 tab：最多 10 次/秒；active tab 30 次/秒。
- 超过 30 条消息：`message.tail`。
- 同前缀检测：`SessionState` 用不可变/chunked list，序列化只处理新增尾部。
- 缓存每个消息的 JSON 片段，按消息版本失效。

### 12.4 Webview 渲染

- `useSyncExternalStore` + 细粒度 selector。
- 只有 active tab 的视图订阅消息；后台 tab 不 render。
- `MessageItem` memo；消息对象引用稳定。
- Markdown 解析按消息缓存；流式只重解析当前 assistant。
- 工具卡片只在其 raw block 版本变化时重渲染。
- 大历史按“轮次窗口”加载，不一次性渲染 5000 条。
- 不使用 `content-visibility: auto` 作为主方案；当前 `message.less:52-57` 已说明流式布局风险。需要窗口化时做显式虚拟列表/轮次分页。
- 滚动使用 `useLayoutEffect` + 稳定锚点；避免每 delta 强制 `scrollIntoView`。
- 输入框不受消息更新影响；草稿 store 独立。

#### 12.4.1 消息显示路径

```
content.delta
  -> TabStore[runtime].content += delta（只有目标 tab）
  -> active tab 才调度 rAF
  -> patchAssistantForStreaming() 更新 streaming assistant
  -> MessageItem(memo) 只重渲染该 assistant
  -> rAF 合并，33ms 上限

message.snapshot / message.tail
  -> 若 active：按消息 id 对账，流式中合并到下一帧
  -> 若 background：只更新目标 tab store，不触发 render
  -> tool block / usage / revert 等结构变化只走 snapshot
```

因此界面不是“逐条 SSE 直接 append DOM”，而是：
**delta 负责流畅文本，snapshot 负责权威结构；store 负责隔离，active selector 负责显示。**

### 12.5 线程规则

| 线程 | 允许 | 禁止 |
|---|---|---|
| EDT | Swing 挂载、轻量 UI 状态 | Node 探测、读 10MB HTML、JSON 大序列化、网络、文件 IO |
| CEF UI/IO 线程 | `executeJavaScript` 调用 | 阻塞等待、剪贴板同步 IO、Node 探测 |
| TabSessionActor | 单 tab 内存状态 | 磁盘、网络、JCEF 调用 |
| 池线程 | 序列化、历史转换、HTML 准备 | 直接改 Swing/React 状态 |
| Daemon reader | 解析 NDJSON、投递 sink | 直接 Swing/JCEF |

### 12.6 JCEF / OSR

保留并完善 cc-gui 的治理：

- `SurfaceFrameFence` + `surfaceDamagePulse`：OSR 帧发布。
- tab 激活使用 cached presentation，不 reload。
- `WebviewWatchdog` 流式期间 180s，空闲期间正常超时。
- reload 后 `tab.resync`，不重建宿主 session。
- 初始化：HTML 按主题缓存；Node/环境探测在池线程；EDT 只创建 browser + 挂载。
- 主题切换双路重绘（Swing + JS）。
- 禁止在 tab 激活时触发整页 reload。

### 12.7 内存

- 每个 tab 的消息只保留一份宿主状态 + 一份 webview 镜像；不再有多个 snapshot 副本。
- `message.snapshot` 传递不可变引用；序列化后释放临时字符串。
- 历史分页；非活动 tab 可只保留最近 N 轮，加载更早时再请求。
- 关闭 tab 释放所有缓存；全局缓存有上限。
- 附件临时文件在发送完成或 tab 关闭后清理。
- `messageMergeCache` 等缓存按 tab 分区，tab 关闭时清理。

### 12.8 可观测性

新增指标：

- 每 tab：queue depth、delta 合并数、snapshot 大小、last seq、turn 时长。
- 全局：JCEF 调用频率、批大小、丢弃 delta 数、orphan 行数、reload 次数。
- 前端：主线程长任务、rAF 延迟、React commit 时间、active tab 渲染耗时。
- 诊断日志必须带 `tabId`/`sessionId`，不再只打“current session”。

### 12.9 与 `jetbrains-cc-gui` 的继承边界

`jetbrains-cc-gui` 是单会话实现，只能作为**机制参考**，不能作为多会话架构依赖：

**保留/移植的机制**

- `messageSync` 中的乐观消息、防 shrink、保留 streaming assistant、防重复 tool 尾部等纯逻辑。
- `messageMergeCache` 的引用稳定性策略。
- `WebviewWatchdog` 的流式感知与 reload 恢复思路。
- `SurfaceFrameFence` + `surfaceDamagePulse` 的 OSR 帧发布机制。
- 初始化离 EDT、HTML 按主题缓存、Node 探测去重、错误截断。
- `WebviewEventQueue` 的批量、合并、生命周期优先思路；但必须改造成 per-tab lane。

**不能照搬的部分**

- 单会话 `switch_session`。
- 单份 streaming refs。
- 单活动会话的 `permissionServiceKey`。
- 文件 IPC 权限。
- `getHostedSessionIds` 用 coalescer key 推断。

**明确区分**

- `messageSync` 解决的是消息正确性，不是 JCEF IPC 饱和。
- 防卡 UI 的主力是：per-tab 队列、delta 合并、快照尾部传输、离 EDT 序列化、rAF、OSR 帧栅栏、watchdog。
- 任何从 cc-gui 移植的文件都必须按多会话语义重新审查，不以“与上游一致”作为正确性依据。

---

## 13. 测试与验收

### 13.1 单元测试

宿主：

- `TabRegistry` create/bind/close 原子性。
- 重复 bind、跨 tab bind、关闭后事件拒绝。
- 新会话 `sessionId=null → ses_*`，无 UUID 进入 session map。
- `DaemonBridge` 按 session 路由；bootstrap 按 requestId；orphan 计数。
- actor 顺序：delta → snapshot → turn_end。
- 权限：tab/session/request 三重校验。
- 关闭释放 sink/actor/prompts/attachments。
- fork/revert 不修改源 tab。

前端：

- `TabStore` 只更新目标 tab。
- 后台 delta 不触发 active 订阅。
- `turn.ended` 按 tab/turn 幂等。
- 旧 epoch/revision 丢弃。
- 关闭 tab 清空 store/runtime/draft。
- 附件/文件引用/草稿切换恢复。
- 权限队列在后台不丢、不串。
- reload resync 保留 tabId。

### 13.2 集成测试

使用 fake daemon/可控 marker 流：

1. 两个 tab 同时流式，交错 delta/snapshot/turn_end。
2. 新会话在 `[SESSION_ID]` 前到达若干行，bind 后继续。
3. 历史加载同时另一个 tab 流式。
4. 后台权限请求，切过去后弹窗。
5. 关闭流式 tab，另一个不受影响。
6. 服务端 `message.removed` 只影响目标 tab。
7. 标题、usage、revert 事件只影响目标 tab。
8. daemon 断连，所有 active turn 正确结束。
9. webview reload，tabId 不变，active 恢复。
10. 1000 次 tab 启停，无 sink/actor 泄漏。

### 13.3 功能验收矩阵

| 功能 | 场景 | 通过标准 |
|---|---|---|
| 新建会话 | A/B 两 tab 新建 | 无伪 session、无幽灵 tab |
| 首次发送 | 未绑定 tab | 绑定真实 `ses_*`，只影响本 tab |
| 历史加载 | 空 tab/有流式 tab | 空 tab 复用；流式 tab 拒绝 replace 或新开 tab |
| 多会话并行 | 3 tab 同时流式 | 文本/工具/终态不串 |
| 修改标题 | 后台 tab 改标题 | 只有目标 tab 变化；自动标题不覆盖用户标题 |
| 撤销/重做 | A tab 撤销 | B tab 消息不变；A 的 removed 精确 |
| fork | 从 A fork | 新 tab 得到新 session；A 不变 |
| 附件 | A/B 各自上传 | 切换恢复；发送不串 |
| `@` 引用 | A/B 各自引用 | 路径按各自 cwd 校验 |
| token | 两 tab 同时流式 | usage 分别更新 |
| 上下文开关 | A 开/B 关 | 发送时各自生效 |
| 模型选择 | A/B 不同模型 | 首次发送和后续都正确 |
| 模式选择 | A build/B plan | 发送时各自生效 |
| 推理力度 | A high/B low | 不互相覆盖 |
| bash | A 执行 `!` | B 不受影响 |
| opencode 命令 | A/B 各自 `/` | 各自 cwd/会话 |
| 状态面板-任务 | A/B 并行 todo | 各自 todo 数量/状态不串 |
| 状态面板-子代理 | A/B 并行后台 Agent | 列表、终态、transcript 只进目标 tab |
| 状态面板-编辑 | A/B 同时改文件 | edits、pending、undo/discard 只影响目标 tab |
| 状态面板-视图 | A 展开任务、B 收起 | 切 tab 恢复各自展开/子页状态 |
| 切 tab | A 流式中切到 B | 不中断 A，B 不显示 A 内容 |
| 后台权限 | B 请求，A 在前台 | B 角标；切 B 可答；A 不弹错 |
| 关闭 tab | 关闭最后一 tab | 自动新空 tab；无泄漏 |
| webview reload | 流式中 reload | tabId 保留，active 正确，内容补齐 |
| IDE 重启 | 关闭再开项目 | 只开新会话，不恢复旧 tab |
| 性能 | 6 tab/3 流/10k 消息 | 达 §12.1 预算 |

### 13.4 性能基准

固定数据集：

- 6 个 tab；
- 3 个并发流，每个 100 delta/s；
- 每个 tab 2000 条消息；
- 单条消息含 5 个 tool block；
- 1000 次 tab 切换；
- 50 次 reload；
- 100 次权限请求。

记录：

- CPU 峰值/均值；
- 内存峰值/关闭后回落；
- 主线程长任务；
- JCEF 队列深度；
- 首字节/末字节延迟；
- tab 切换耗时。

基准不达标不得合并。

---

## 14. 实施阶段

虽然是“全量重构”，仍按可验证的阶段提交，每阶段必须编译、测试、可运行；不允许长期双轨。

### Phase 0：基线、协议、测试骨架

- 冻结本文的 ID、协议、状态所有权。
- 添加 fake daemon / marker 注入测试工具。
- 添加性能基准和 JCEF 诊断日志。
- 标记并隔离旧路径，准备删除。

### Phase 1：宿主 TabRegistry 与身份

- 新增 `TabId` / `SessionId` 类型。
- 新增 `TabRecord` / `TabRegistry` / `TabState`。
- 删除 `permissionServiceKey`、初始空 session、随机 `sessions` key。
- 删除 `createNewSession` 随机 UUID 路径。
- 所有命令先带 `tabId`。
- 新会话首次发送绑定真实 `ses_*`。

### Phase 2：协议 v2 与 Webview TabStore

- 新增 `window.__opencodeDispatch`。
- 新增 `HostDispatcher` + `TabStore` + pending event queue。
- 删除 `__pendingSessionId`、旧 `setSessionId`。
- UI 用 `tabId` 创建/激活 tab；删除 `switch_session`。

### Phase 3：流式、actor、BridgeScheduler

- 新增 `TabSessionActor`。
- 新增 `TabStreamRouter` 的 per-tab 生命周期。
- 替换 `WebviewEventQueue` 为 `TabBridgeScheduler`。
- 替换单份 streaming refs 为 `TabRuntimeStore`。
- 删除 `claimUnboundTab`、runtime snapshot 交换。
- 完成 delta/snapshot/turn_end 的 per-tab 处理。

### Phase 4：权限、提问、计划

- 删除文件 IPC 权限全部类。
- 新增 `PromptBroker`、`PendingPrompt`。
- 权限/问题/计划全部带 tabId/sessionId/requestId。
- 后台 tab 队列与角标。
- 删除全局 `clearPendingRequests`。

### Phase 5：功能迁移

按 §9 顺序迁移：

1. 历史加载。
2. 标题。
3. 撤销/重做。
4. fork。
5. 附件。
6. 文件引用/上下文。
7. token。
8. 模型/模式/推理力度。
9. bash/opencode 命令。

每项必须有独立集成测试，不允许“功能先上，隔离后补”。

### Phase 6：恢复与清理

- `tab.resync` / `epoch` / hydrate。
- 删除 `TabStateService` 的会话恢复。
- 关闭/泄漏测试。
- 删除所有旧字段、旧函数、旧注释、死代码。
- 静态检查旧模式归零。

### Phase 7：性能与 JCEF 收口

- 大历史分页/尾部快照。
- 序列化缓存。
- 渲染窗口化。
- OSR/watchdog/初始化检查。
- 性能基准达标。

### Phase 8：最终验收

- 全量功能矩阵。
- 6 tab/3 流/10k 消息基准。
- 50 次 reload。
- 1000 次 tab 启停。
- 无 orphan/泄漏/串会话。
- 更新文档和排障手册。

---

## 15. 文件级改造清单

### 15.1 新增宿主文件

建议包结构：

```
com.opencodebuddy.tab
  TabId.java
  SessionId.java
  TurnId.java
  TabRecord.java
  TabState.java
  TabRegistry.java
  TabSnapshot.java
  TabSessionActor.java
  TabSessionFactory.java
  TabCommand.java
  TabCommandRouter.java
  TabSettings.java
  TabAttachmentStore.java
  TabEvent.java
  TabEventBus.java
  BridgeScheduler.java
  PendingSend.java

com.opencodebuddy.prompt
  PromptBroker.java
  PendingPrompt.java
  PromptKind.java
  PromptDecision.java
```

### 15.2 新增 webview 文件

```
webview/src/store/tabTypes.ts
webview/src/store/tabStore.ts
webview/src/store/tabRuntimeStore.ts
webview/src/store/tabDraftStore.ts
webview/src/store/tabPromptStore.ts
webview/src/store/selectors.ts
webview/src/bridge/hostDispatcher.ts
webview/src/bridge/hostCommands.ts
webview/src/bridge/pendingEvents.ts
webview/src/components/TabView.tsx
```

### 15.3 重点修改

- `OpencodeBuddyChatWindow`：删除 `session`/`activeSessionId`/`sessionId`/`permissionServiceKey` 单槽；改为持有 `TabRegistry`。
- `ChatWindowDelegate`：初始化、reload resync、事件推送。
- `MessageDispatcher`：路由前解析 `tabId`。
- `SessionHandler`：只做 per-tab send/interrupt。
- `WindowEventHandler`：只保留窗口/激活/OSR 事件；删除 session 生命周期。
- `SessionLifecycleManager`：替换为 `TabSessionFactory`。
- `SessionCallbackAdapter`：替换为 `TabEventSink`，或重写为 per-tab。
- `StreamMessageCoalescer`：per-tab，tail-only。
- `PermissionHandler`：重写为 `PromptBroker` 的 UI 适配层。
- `WebviewInitializer`：保留离 EDT 初始化；配合新 dispatcher。
- `WebviewWatchdog`：保留；reload 走 resync。
- `useSessionTabs`：删除，拆成 `tabStore` + tab bar。
- `useSessionManagement`：删除，拆成 tab/session 命令。
- `useMessageSender`：带 tabId/settings/附件/引用发 `session.send`。
- `streamingCallbacks` / `messageCallbacks`：改为 dispatcher 的 handler，不再直接注册全局函数。
- `sessionCallbacks`：删除 setSessionId 旧逻辑，改 `session.bound`。
- `useDialogManagement`：改为 per-tab prompt store + active presenter。
- `MessagesContext`：不再承载多会话状态。

### 15.4 删除

- `permission/PermissionService.java`
- `permission/PermissionManager.java`
- `permission/PermissionFileProtocol.java`
- `permission/PermissionRequestWatcher.java`
- `permission/PermissionDecisionStore.java`
- `permission/PermissionDialogRouter.java`
- `permission/PermissionSessionRegistry.java`
- `settings/TabStateService.java`（或移除 session 部分）
- `ui/toolwindow/TabSessionRestorePolicy.java`
- `ui/toolwindow/SessionLifecycleManager` 的旧创建/切换路径
- `webview/src/utils/streamingRuntime.ts`（被 per-tab runtime 取代）
- `webview/src/hooks/useSessionTabs.ts`（拆分后删除）
- `webview/src/hooks/windowCallbacks/sessionTransition.ts`（全局锁删除）

---

## 16. 静态检查规则

在 CI 中增加：

```bash
# 1. 不允许把 tabId 当 sessionId
rg "sessionId.*tabId|tabId.*sessionId" src/main/java webview/src

# 2. 不允许活动会话兜底
rg "context\.getSession\(\)" src/main/java/com/opencodebuddy/handler
rg "currentSessionIdRef\.current" webview/src/hooks/windowCallbacks

# 3. 旧机制必须归零
rg "claimUnboundTab|switch_session|permissionServiceKey|__pendingSessionId|getHostedSessionIds" .
rg "setSessionId.*oldSessionId" .

# 4. session map 不允许 key 混用
rg "sessions\.put|sessionCoalescers\.keySet" src/main/java
```

允许的例外必须写注释说明，并且不能是会话归属路径。

---

## 17. 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| 新会话绑定前事件乱序 | 首条消息丢失 | `PendingSend` + requestId 严格限定 + `[SESSION_ID]` 首行 |
| 大历史快照卡 UI | 冻结 | tail 快照、分页、序列化缓存、帧预算 |
| 后台 tab 更新触发渲染 | CPU 飙升 | per-tab store + 细粒度订阅 + 后台不 notify active |
| 权限后台丢失 | 服务端 rejected | per-tab prompt 队列 + 角标 + 三重校验 |
| fork/revert 服务端语义复杂 | 状态错乱 | 只信服务端事件，按 tab 应用；失败不本地猜测 |
| 关闭竞态 | actor/sink 泄漏 | 幂等 close + 资源清单 + 泄漏测试 |
| reload 丢失活动 tab | 用户困惑 | resync 带 tabId/active；epoch 防旧事件 |
| 附件内存 | OOM | 分块/临时文件/大小限制/关闭清理 |
| 模型/模式竞态 | 发到错误会话 | 发送 payload 带 settings 快照 |
| 启动初始化卡 EDT | 首次打开冻结 | 全量后台准备 + HTML 缓存 + EDT 只挂载 |

---

## 18. 最终验收标准

完成后必须满足：

1. 全仓不存在 `switch_session`、`permissionServiceKey`、`claimUnboundTab`、`__pendingSessionId`、`getHostedSessionIds` 旧实现。
2. 任何会话级事件都带 `tabId`；任何宿主 session map 都不含非 `ses_*` key。
3. 新建会话不会产生伪 session；首次发送后 UI 与宿主绑定同一个 `ses_*`。
4. 历史加载、fork、revert、附件、文件引用、token、模型/模式/推理力度、bash/opencode 命令全部按 tab 隔离。
5. 6 tab / 3 并发流 / 10k 消息下达到 §12.1 性能预算。
6. webview reload 后 tabId、active tab、流式状态正确恢复；IDE 重启只开新会话。
7. 关闭 tab 后无 actor、sink、coalescer、prompt、附件、缓存泄漏。
8. 权限/提问在后台不丢、不串、不阻塞其他 tab。
9. 所有功能矩阵和集成测试通过。
10. 不出现“一个 tab 的输出进入另一个 tab”的任何已知路径。

---

## 附录 A：协议示例

### A.1 新建 tab

```json
{ "v": 2, "type": "tab.create", "tabId": "tab-7f3a", "requestId": "req-1", "payload": { "cwd": "/repo" } }
```

```json
{ "v": 2, "epoch": 1, "type": "tab.created", "tabId": "tab-7f3a", "payload": {} }
```

### A.2 首次发送

```json
{
  "v": 2,
  "type": "session.send",
  "tabId": "tab-7f3a",
  "requestId": "req-2",
  "sessionId": null,
  "payload": {
    "text": "hello",
    "settings": {
      "provider": "opencode",
      "model": "opencode/gpt-5",
      "permissionMode": "default",
      "reasoningEffort": "medium"
    },
    "context": { "enabled": true },
    "fileTags": [],
    "attachments": []
  }
}
```

### A.3 绑定

```json
{ "v": 2, "epoch": 1, "type": "session.bound", "tabId": "tab-7f3a", "sessionId": "ses_abc", "payload": {} }
```

### A.4 流式 delta

```json
{
  "v": 2,
  "epoch": 1,
  "type": "content.delta",
  "tabId": "tab-7f3a",
  "sessionId": "ses_abc",
  "turnId": 1042,
  "seq": 1047,
  "payload": { "delta": "Hello" }
}
```

### A.5 权限请求/回复

```json
{
  "v": 2,
  "epoch": 1,
  "type": "permission.requested",
  "tabId": "tab-7f3a",
  "sessionId": "ses_abc",
  "payload": {
    "requestId": "per_123",
    "toolName": "Bash",
    "inputs": { "command": "rm -rf build" },
    "directory": "/repo"
  }
}
```

```json
{
  "v": 2,
  "type": "permission.reply",
  "tabId": "tab-7f3a",
  "sessionId": "ses_abc",
  "requestId": "req-3",
  "payload": {
    "requestId": "per_123",
    "decision": "allow",
    "remember": false
  }
}
```

### A.6 reload resync

```json
{
  "v": 2,
  "epoch": 17,
  "type": "tab.resync",
  "payload": {
    "tabs": [
      { "tabId": "tab-7f3a", "sessionId": "ses_abc", "title": "Fix build", "active": true },
      { "tabId": "tab-9b21", "sessionId": "ses_def", "title": "Review", "active": false }
    ]
  }
}
```

---

## 附录 B：一句话职责

- **core**：只负责把 SSE 翻译成带 `{session, turn, seq}` 的事实。
- **host**：只负责按 `tabId` 维护会话状态和资源。
- **bridge**：只负责把带 tab 的事件按预算送到 webview。
- **webview**：只负责按 `tabId` 存状态、按 `activeTabId` 渲染。
- **permission**：只负责把“某个 tab 的某个请求”展示并回传，不猜当前会话。
- **switch**：不再是命令；切 tab 是纯 UI 选择。
- **ref**：只用于 DOM/滚动/输入等局部引用，不再承载会话运行态。
