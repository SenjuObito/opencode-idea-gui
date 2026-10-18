# Webview 闪屏 / UI 冻结治理：从 jetbrains-cc-gui 迁移并适配多会话

> 调研对象：`D:/source/repos/jetbrains-cc-gui`（单会话模型）
> 目标仓库：`D:/source/repos/opencode-idea-gui`（单 webview 内多会话 + 多 Content）
> 状态：技术方案，尚未改动任何代码
> 日期：2026-10-18

---

## 0. 结论摘要

### 0.1 最重要的单一发现

**当前项目的 OSR OnPaint 帧栅栏（`SurfaceFrameFence`，634 行）与前端脉冲模块（`surfaceDamagePulse.ts`，198 行）都与 cc-gui 逐字节一致——但前端从未在 `main.tsx` 注册这 5 个全局函数，Java 侧的所有调用都被 `typeof === 'function'` 静默跳过。**

| 位置 | 事实 |
|---|---|
| `src/main/java/com/opencodebuddy/ui/SurfaceFrameFence.java` | 634 行，与 cc-gui 仅差 `package` 一行 |
| `webview/src/utils/surfaceDamagePulse.ts` | 198 行，`diff` 为空 |
| `webview/src/utils/forceWebviewRepaint.ts` | `diff` 为空，**生产代码 0 引用** |
| `main.tsx` | 无 `window.__ccguiSurfaceDamagePhaseA/B/Replace/Finish/Cancel` 注册 |
| `main.tsx` | 无 `window.onTabActivated` 定义 |

后果：`OpencodeBuddyChatWindow.triggerSurfaceDamage()`（:1066-1070）发出的 A/B 相位脚本全部落空，fence 只能等 `OSR_FRAME_FENCE_TIMEOUT_MS = 1000`（:106）超时释放。**OSR 平台（Linux / IDEA 2025.2 Remote JCEF）上，每次历史渲染与 tab 激活都要空转 1 秒才走 fallback**——这正是「闪一下 / 白一下」的直接来源之一。

### 0.2 cc-gui 的解法分层

cc-gui 的闪屏/冻结治理不是单一补丁，而是四层叠加。四层各自解决不同的根因，**不能互相替代**：

| 层 | 目标 | 核心机制 | 关键提交 |
|---|---|---|---|
| **L1 像素层** | OSR 画面不发布 | OnPaint 双帧 fence + A/B sentinel 脉冲 | 见 `docs/fix/first-tab-history-render-fix-2026-08-06.md` |
| **L2 生命周期层** | tab 激活 / 主题 / reload 后画面不刷新 | cached presentation（**不 reload**）、Swing+JS 双路主题回调、watchdog 流式感知 | `2389c23a`、`698d0d95`、`d861baf7`、`06c32444` |
| **L3 线程层** | EDT / CEF IO 线程被阻塞 | 初始化全量后台化、Node 探测单例+in-flight 去重、剪贴板跨线程、错误截断 | `de536192`、`9c8644a1`、`3718f464`、`a71b0988` |
| **L4 渲染成本层** | 长会话主线程卡顿 | 对象引用稳定性、WeakMap/Map 分级缓存、O(n²)→线性、content-visibility、增量高亮 | `docs/fix/PERFORMANCE_GUIDE.md`、`docs/plans/2026-09-28-performance-remediation.md` |

### 0.3 多会话到底改变了什么：作用域判断准则

**先说结论：整体逻辑与 cc-gui 基本一致，绝大部分可以一对一照抄。** 因为「有且仅有一个会话在前台」是硬约束——渲染、surface 发布、DOM 更新都只作用于当前一个会话，多会话不改变这些链路。

判断某个机制是否需要多会话适配，只看一个问题：**它的作用域是「会话」还是「webview」？**

| 作用域 | 是否被多会话影响 | 原因 | 对应机制 |
|---|---|---|---|
| **会话级 / 当前视图级** | ❌ **完全不受影响，可直接照抄** | 前台只有一个会话，行为与单会话时逐字相同 | OnPaint fence、tab 激活 cached presentation、主题重绘、消息列表渲染、切会话的**数据**恢复 |
| **webview 级** | ✅ **必须适配** | 作用对象是整个 JS 上下文 / 整个 renderer，里面装着 N 个会话 | watchdog 的「忙不忙」判定、reload 的爆炸半径 |
| **会话级状态但存放在 webview 级容器里** | ✅ **必须适配（最易漏）** | 逻辑上属于某个会话，物理上却是单份 `useRef` / window 全局变量 | **流式运行态**（M0）：`streamingContentRef`、`streamingMessageIndexRef`、`streamingTurnIdRef`、`isStreamingRef`、`__streamEndProcessedTurnId`… |

按这条准则逐项复核后，**真正需要适配的是 3 处**：M0（流式运行态，§3.3）、M1（watchdog 语义，§4 P3）、M2（reload 恢复，§4 P3）。其余原本担心的点经代码核实均不成立，已在本版撤回或降级（见 §0.4）。

> 第三类是上一版遗漏的。前两类都很直观——「跨 webview 的」要注意、「单会话的」可以照抄。但**「逻辑上属于会话、实现上却是单份 ref」**这一类既不跨 webview 也不明显，所以最容易被当成「会话级，照抄即可」而漏掉。这也是「切到正在执行的另一个会话会串内容」的根因。

---

### 0.4 本版修正

**撤回了 4 处过度推演，新增了 1 处真实盲区。**

第一版方案在多会话破坏面上推演过头，以下四条**撤回或降级**，避免做无用改造（详细证据见 §4 附）：

| 原判 | 复核证据 | 结论 |
|---|---|---|
| **M7** fence 的 `contentRevision` 需加 `sessionId`，否则旧会话迟到的 commit 会推进 watermark | `SurfaceFrameFence.requestInternal`（:303-310）：`if (latest != null && contentRevision <= latest.contentRevision) return latest;`<br>`historyRenderCommitEpoch` 是**单调递增**的 React state（`useWindowCallbacks.ts:116`，只 `++`、切会话不重置）→ 旧会话迟到的 revision **必然 ≤ 新会话**，已被 watermark 自动挡掉 | **撤回**，`SurfaceFrameFence` 与 `history_dom_committed` 负载都不需改 |
| **M4** 切会话时 `setMessages(targetTab.messages)` 会整树重渲染 | `saveCurrentTabSnapshot`（`useSessionTabs.ts:461`）是 `currentActive.messages = snap.messages` —— **直接引用赋值，无深拷贝**；`MessageItem` 是 `memo`（:487），切回来时 message 对象是同一批引用 → memo 命中 | **撤回**，前提是**不要**在任何地方对 messages 做 `JSON.parse(JSON.stringify())` |
| **M5** 缓存需按 sessionId 分区 + LRU，否则切会话全量重算 / 内存爆 | 唯一强引用缓存是 `mergedAssistantMessageCache`，已有 `MESSAGE_MERGE_CACHE_LIMIT = 3000`（`messageMergeCache.ts`）兜底；其余是 `WeakMap`（按对象生命周期，天然无需管理） | **降级为可选优化**，不属于闪屏/冻结范围 |
| **M3** `setTabs` 全表 map 需按多会话数量优化 | 依赖数组里的 `messages`（`useSessionTabs.ts:279`）只在**活动会话**变化时触发，与后台会话数量无关；`updateBackgroundTab`（:330-362）已优化成 O(1) 单元素克隆 | **降级为独立性能项**，与多会话架构无关 |

> 保留但仍需做的 M6（初始化成本 ×N）和 M8（tab-switch 与 session-load 分流）见 §4，二者与 0.3 的准则不冲突：M6 是「多个 webview 各自的初始化」，M8 是「同一 webview 内两种切法的语义区分」。

**新增的一处（M0，见 §3.3 与 §4 P0.5）**：第一版完全漏了「用户切到正在执行的另一个会话」这个并发场景。根因不是跨 webview，而是**流式运行态（`streamingContentRef` / `streamingMessageIndexRef` / `streamingTurnIdRef` / `isStreamingRef` / `__streamEndProcessedTurnId`）在逻辑上属于会话、实现上却是单份 `useRef`**。切 tab 时既不保存也不恢复 →

- B 的 delta 接着 A 的 3000 字继续累加 → **B 显示 A 的文本**
- `streamingMessageIndexRef` 命中的是 A 的下标 → **B 的增量粘到错误消息上**
- 后台写入用 `last.content += delta` 原地 mutate → 快速连切时 **React 认为引用未变而丢更新**

这是**必现**问题（每次切到执行中的会话都可能触发），优先级高于所有性能项。

### 0.5 场景覆盖结论（穷举 7 组 39 个场景）

完整矩阵见 **§3.4**。汇总：

| 组 | 场景数 | ✅ 已覆盖 | ⚠️ 待补/待验证 | ❌ 未覆盖 |
|---|---|---|---|---|
| S1 会话切换（tab） | 9 | 7 | 2（关闭类） | 0 |
| S2 历史列表 / 加载 | 7 | 4 | 2 | 1（从流式会话切走） |
| S3 从历史继续对话 | 6 | 5 | 1 | 0 |
| S4 后台事件 | 6 | 5 | 1 | 0 |
| S5 生命周期 | 4 | 2 | 2 | 0 |
| S6 渲染 / Surface | 4 | 3 | 1 | 0（本轮补上） |
| S7 身份 / 路由 | 3 | 2 | 0 | 1（tab 绑定不一致） |

**结论：方案原本覆盖约 75%，本轮补齐后可达 ~95%；剩下的是需要产品决策的 1 项（N2 的孤儿 tab 语义）和 2 项待核实（S2.4 加载竞态、S5.4 detached window）。**

本轮新增的 6 个缺口（N1–N6）中：

- **N2 最严重**：会导致「从历史列表切走一个正在流式的会话，消息串到新会话上」，且它**不属于** M0（不是运行态问题，是 tab 绑定与 `currentSessionId` 不一致）
- **N1 最容易漏**：M0 的存档点只放在 `selectTab`，覆盖不到 `loadHistorySession` 走的 `beginSessionTransition`
- **N3/N5/N6 是配套完整性**：不做会让 M0 在特定路径上漏掉
- **N4 是范围修正**：M2 不能顺手改掉「IDE 重启不恢复 tab」这个刻意的现状

---

## 1. cc-gui 的解法的完整拆解

> 这一章全部是**可直接照抄**的部分——按 §0.3 的准则，它们的作用域都是「当前会话 / 当前 surface」，多会话不改变行为。

### 1.1 L1：OSR OnPaint 帧栅栏（闪屏的真正根因）

来源：`jetbrains-cc-gui/docs/fix/first-tab-history-render-fix-2026-08-06.md`

#### 根因链

JCEF OSR 下「内容已存在」和「像素已显示」是五件独立的事，任何一环都不能由前一环推断：

```
React DOM commit
  ↓  （rAF 只提供绘制机会，不证明 OnPaint 到达）
Chromium style / layout / compositor
  ↓
CefRenderHandler.OnPaint / onPaintWithSharedMem
  ↓  （wasResized 在 bounds 相同时会被 CEF 122 短路）
JetBrains 默认 OSR handler 更新 backing image
  ↓  （markCompletelyDirty / repaint / paintImmediately 只重画已有 backing image）
Swing JBCefOsrComponent.paintComponent()
```

文档明确列出了被证伪的早期方案：hide/show + 固定延迟、重新应用 bounds、120ms+120ms 补偿、double-rAF / zoom nudge。结论：**固定延时不能推断帧已到达**。

#### 最终机制

`SurfaceFrameFence` 状态机（`SurfaceFrameFence.java:41-52` 的 `Stage` / `DamagePhase` 枚举）：

```
PENDING
  ↓ 页面 showing + displayable + 尺寸有效
WAITING_PHASE_A_APPLIED    ← 前端在 #app 外插 2px sentinel（非零透明真实色）
  ↓ 前端回传精确 token 的 ACK（executeJavaScript 返回 ≠ ACK）
DRAINING_FIRST_FRAME       ← 第一个真实 OnPaint 只 drain，正常透传
WAITING_PHASE_B
WAITING_PHASE_B_APPLIED    ← 前端把 sentinel 恢复为 transparent 并 ACK
WAITING_FINAL_FRAME        ← 第二个 OnPaint 强制 full-frame
PAINT_QUEUED               ← EDT 复核身份 → markCompletelyDirty → paintImmediately
COMPLETED
```

关键不变量（文档 §9 维护约束，共 10 条，摘录最易违反的）：

1. 不要用固定延时推断 frame 已到达；
2. rAF / Timer / `wasResized()` / `paintImmediately()` **单独都不能**作为像素完成证明；
3. 不要替换 JetBrains OSR component 或自管共享内存；
4. 不要让 request serial 代替 attempt identity（同一 request 可被重新 arm）；
5. 严格 pulse 期间禁止普通 zoom repaint；
6. **不要在历史 surface 恢复中 reload 或重新 `loadHTML()`**。

序列身份由 7 元组构成（`SurfaceFrameFence.Request`，:58-115）：browser 身份、`CefBrowser` 身份、`pageGeneration`、`readyEpoch`、`contentRevision`、`serial`、`reason`；attempt 再加 `attemptId`。超时只释放 attempt、保留 pending，且不会 timer 自循环。

前端对应实现：`surfaceDamagePulse.ts` 的 `beginSurfaceDamagePulse` / `advanceSurfaceDamagePulse` / `replaceSurfaceDamagePulse` / `finishSurfaceDamagePulse` / `cancelSurfaceDamagePulse`，ACK 通过 `sendBridgeEvent('surface_damage_applied', ...)` 回传。

### 1.2 L2-1：tab 激活的 cached presentation（不 reload）

提交 `2389c23a fix: repaint empty webview tabs on activation`

根因：`ContentManager.selectionChanged` 在任何 Content 切换到前台时都会触发，但此时 ContentManager **还没有**完成 heavyweight JCEF child 的重新映射；空 tab 又不会有后续 DOM 更新来顺带重绘 native surface，于是永久白屏。

解法（`ClaudeChatWindow.onTabActivated()`）：

- `ApplicationManager.getApplication().invokeLater(repaint)` —— **必须延后一个 EDT turn**；
- 先校验 `isSelectedContent()`（防抖 + 防已切换走）；
- 分派三路：
  - **windowed**：`mainPanel.revalidate/repaint` → `browserComponent.revalidate/repaint` → 因为不是 OSR 所以取 `cefBrowser.getUIComponent()` 做 `setVisible(false/invalidate/setVisible(true)` + parent validate/repaint 强制 native 窗口重映射 → `notifyScreenInfoChanged()` → 最后调 `window.onTabActivated()` 做前端 ghosting 补偿；
  - **OSR 且有未发布内容**：不重绘，唤醒已有的 publication pending（走 L1）；
  - **OSR 且已发布**：只做 cached presentation——`markCompletelyDirty()` + `repaint()` + 必要时 `paintImmediately()`，**不创建新 serial**。

`ClaudeSDKToolWindow.selectionChanged` 也补了守卫：`if (contentManager.getSelectedContent() != event.getContent()) return;`。

### 1.3 L2-2：主题切换的双路重绘

提交 `698d0d95 fix(theme): repaint all open sessions on IDE theme change`（issue #1586）

根因：主题切换时只通过 `window.onIdeThemeChanged` 更新了 webview 的 CSS，但 **Swing 侧的 `mainPanel` / browser component 背景色仍是旧主题**，于是 JCEF 边缘与未绘制区域露出旧色块，看起来像闪屏。

解法：

- `ThemeConfigService.registerThemeChangeListener(callback, true)` —— 第二个参数 `true` 表示支持多回调（单会话时只需要一个，多 tab 时必须每个窗口都收到）；
- 返回 `RegisteredCallback` handle，窗口 dispose 时 `unregisterThemeChangeListener(handle)`，避免向已销毁 webview 推送（原 bug 表现：`Cannot call JS function window.onIdeThemeChanged: disposed=true`）；
- 每个窗口注册 **两条**回调：
  1. `SettingsHandler` 里的 JS 回调 → `window.onIdeThemeChanged`；
  2. `ClaudeChatWindow` 里的 Swing 回调 → `mainPanel.setBackground(bg)` + `browserComp.setBackground(bg)` + `mainPanel.repaint()`。

### 1.4 L2-3：watchdog 不再误判流式为卡死

提交 `d861baf7 fix(streaming): prevent watchdog reload during active streaming`

根因：流式期间 JCEF IPC 饱和，心跳延迟属预期；旧 watchdog 判为 stall → `reload()` → **React 状态全灭**，后端还在往空白页推送。

解法：

- `WebviewWatchdog` 新增 `StreamActiveCheck` 接口，流式期间把心跳超时从默认值延长到 **3 分钟**；
- reload 后重放 `onStreamStart`，恢复前端 `isStreamingRef`；
- session transition guard 加 15s 安全超时，防止回调被永久抑制；
- 调整初始化顺序，让 `streamCoalescer` 在 watchdog 之前可用。

配套 `06c32444 fix(claude): soft-reload same-session resume`：重复打开**当前已激活**的会话时，不再 interrupt + recreate（那等于硬 reload），改为走 `session_updated` 的 soft reload 路径，流式期间延迟到流式结束。

### 1.5 L3：EDT / CEF IO 线程阻塞

#### `de536192 fix: keep webview startup work off the EDT`（最关键）

原始症状：**webview 初始化把 EDT 冻结最长 17 秒**，首次打开和每次 watchdog recreate 都发生。

被移出 EDT 的工作：Node 路径验证、环境探测、约 10MB HTML 读取、主题注入（`String.replaceFirst` 全串扫描）。

改动要点：

1. `createUIComponents()` 入口先 `assert EDT`，否则转投；
2. 新增 `initializationGeneration`（`AtomicInteger`）+ `initializationInProgress`（`AtomicBoolean`）做去重与过期丢弃；
3. 全部阻塞输入移入 `prepareUIComponents()`（pooled thread）：verify node path → `checkEnvironment()` → `loadChatHtmlWithInitialTabState()`；
4. **EDT 只保留 `JBCefBrowser` 创建 + Swing 挂载**；
5. 把正则 `replaceFirst` 改为对真实 document root tag 的线性扫描——避免误改 JS bundle 里内嵌的 `<body>` 字符串（9.7MB 单行 bundle 里这种字符串真实存在）；
6. **按主题缓存 themed HTML，多 tab 共享一份**，不再每个 tab 重读 10MB；
7. 把 per-tab 的 provider/model、DSH preset、page-context 三次注入**合并为一次 document rewrite**；
8. `checkEnvironment` 探测加 **5s 超时并强制 kill**（挂死的 node 不能 wedge 初始化）。

#### `9c8644a1`：Node 探测在 CEF IO 线程阻塞 10-15s

macOS 上打开设置面板（Codex 模式）时，`handleGetNodePath()` / `handleSetNodePath()` 在 CEF IO 线程跑阻塞探测。`/bin/zsh` 加载交互式 login shell 会执行 `.zshrc`（nvm/fnm/Homebrew init），每个 shell 最多 5s。

解法：`NodeDetector` 改单例（两个 bridge 共享缓存）+ 缓存读写加锁 + **in-flight 去重**（并发调用共享同一次探测）+ `CompletableFuture.runAsync(AppExecutorUtil)` + `invokeLater` 回投结果 + 前端把 `check_node_environment` 延后到 `nodePathReady` 事件。

#### `3718f464`：Ctrl+C 冻结 WebView

`ClipboardHandler` 在 CEF browser 线程经 `JBCefJSQuery` 同步做剪贴板 I/O。Windows 上 AWT 剪贴板走 OLE，被 Chromium 原生 copy 占用时会阻塞数秒，**整个 WebView 冻结**。

解法：`invokeLater(..., ModalityState.any())`（`any()` 保证模态对话框打开时也能用）+ `lastReadTime` 改 `volatile`。

#### `a71b0988`：长错误消息把 webview 冻死

超长 API 错误正文直接进 DOM。解法：Node 桥接层 + Java 层双端截断 1000 字符，保留 `total N chars` 提示。

### 1.6 L4：渲染成本（长会话卡顿）

`docs/fix/PERFORMANCE_GUIDE.md` 的核心原则按重要性排序：

1. **对象引用稳定性**是地基 —— `JSON.parse()` 每次产出全新对象树，`React.memo` / `useMemo` / `WeakMap` 全部失效。做法：更新 state 前做「智能合并」，特征相同则返回旧引用。
2. **WeakMap 缓存昂贵计算**（Markdown 解析、block normalize），key 是对象引用 → 引用稳定时 O(N) 变 O(1)。
3. **派生数据也要引用稳定** —— 尤其 `mergeConsecutiveAssistantMessages`：`combined = [...combined, ...next]` 是隐藏 O(n²)。
4. **过滤判定用 WeakMap**（`shouldShowMessage`），但 `t` / `localizeMessage` 变化必须清空。
5. **UI 反馈与数据处理解耦** —— `clearInput()` 立即执行，重活 `setTimeout(..., 10)` 下一帧。
6. **乐观更新 + 防抖合并** —— 先本地插一条 `isOptimistic` 消息，后端快照未包含时强制拼接，防「消息闪一下消失」。
7. **`content-visibility: auto`** + `contain-intrinsic-size` 作为低成本 safe win，再考虑 windowing。
8. Map 缓存必须限 size + 切 session 清空 + i18n 变化清空。

`docs/plans/2026-09-28-performance-remediation.md` 的 T1-T10 是上述原则的工程化落地（工具结果索引化、文件账本脱离纯文字更新、增量高亮 150ms、Java 侧先合并增量再物化全串、快照只复制变化消息、历史分页索引复用、输入框版本标记、Read 组虚拟化）。

---

## 2. 当前项目现状对照

### 2.1 总览

| # | 能力 | cc-gui | 当前项目 | 判定 |
|---|---|---|---|---|
| 1 | `SurfaceFrameFence`（Java） | 有 | **逐字节一致** | 已移植 |
| 2 | `surfaceDamagePulse.ts`（前端） | 有 | **逐字节一致** | 已移植 |
| 3 | `forceWebviewRepaint.ts` | 有 + 6 处调用 | **0 处生产调用** | 悬空 |
| 4 | `window.__ccguiSurfaceDamage*` 注册 | `main.tsx:691-695` | **缺失** | 断链 |
| 5 | `window.onTabActivated` 定义 | `main.tsx:699-701` | **缺失** | 断链 |
| 6 | tab 激活 surface 分派 | 有 | `OpencodeBuddyChatWindow:621-663` 有（三路分派） | 已移植 |
| 7 | 主题切换 Swing 重绘 | 有 | 待核实 `ThemeConfigService` 是否支持多回调 | 待核实 |
| 8 | watchdog 流式感知 | 有（3min） | 有，但设成 `Long.MAX_VALUE`；只看活动会话 | 过度修正 + 多会话盲区 |
| 9 | reload 后状态重放 | 单会话全量 | 只重放活动会话 | 多会话缺失 |
| 10 | 初始化 EDT offload | 全量后台化 | 仅「无保存路径」分支后台化 | 部分 |
| 11 | HTML 按主题缓存 | 有 | 无（每窗口重读 9.7MB） | 缺失 |
| 12 | 注入合并为一次 rewrite | 有 | 5 次全串复制 | 缺失 |
| 13 | Node 探测单例 + 去重 | 有 | 有（`NodeDetector:55-77`） | 已移植 |
| 14 | 剪贴板跨线程 | 有 | 常规读写已转 EDT；`getClipboardFilePath` 仍在 CEF 线程 | 部分 |
| 15 | 错误截断 | 有 | 有，但只匹配 4 个英文前缀 | 部分 |
| 16 | 会话切换重绘补偿 | `forceWebviewRepaint('session-transition')` | **缺失** | 缺失 |
| 17 | 流式运行态按会话隔离 | 不需要（单会话） | **单份 ref，切 tab 不保存不恢复** | 缺失（M0） |

> 第 17 项是 cc-gui 根本没有对应能力的地方——它一个 webview 只有一个会话，运行态天然唯一。这一项无法「移植」，只能按多会话模型重新设计（§3.3 / §4 P0.5）。

### 2.2 关键缺口的具体证据

#### 缺口 A：OSR fence 全链路失效（最优先）

```java
// OpencodeBuddyChatWindow.java:1066-1070
private void triggerSurfaceDamage(SurfaceFrameFence.Attempt attempt, String phase) {
    String functionName = "phaseA".equals(phase)
            ? "window.__ccguiSurfaceDamagePhaseA"      // ← 前端不存在
            : "window.__ccguiSurfaceDamagePhaseB";
```

```java
// OpencodeBuddyChatWindow.java:1147-1150
String script = "(function(){try{if(typeof " + functionName
        + "==='function'){" + functionName + "(" + argumentScript
        + ");}}catch(e){...}})();";                     // ← typeof 保护，静默跳过
```

`webview/src`、`webview/dist`、`src/main/resources/html/opencode-chat.html` 三处搜索 `__ccguiSurfaceDamage` = **0 命中**。

同理 `window.onTabActivated`（`OpencodeBuddyChatWindow.java:645`）在 windowed 分支调用，全前端 0 定义，`WebviewEventQueue.buildBatchScript`（:105-120）对它同样静默跳过。

#### 缺口 B：初始化仍在 EDT 上做的重活

`WebviewInitializer.createUIComponents()`（:120）整体在 EDT 上跑（入口 `OpencodeBuddyChatWindow.java:472-478` 的 `ToolWindowManager.invokeLater`）。其中：

| 重活 | 位置 | 耗时量级 |
|---|---|---|
| 已保存 node path 时同步 `node --version` | `:154-165` → `NodeDetector:633-648` | 最长 **5s** |
| `checkEnvironment` → `findNodeExecutable()` | `:1169-1185` + `NodeDetector:133` | 缓存未命中最长 **30s** |
| 读 9,710,668 字节 HTML | `HtmlLoader:27-43` | 读盘 + UTF-16 解码（单份 ~19MB） |
| 主题注入 2 次 `replaceFirst` 全串扫描 | `HtmlLoader:72-88` | 2 × 9.7MB 正则扫描 |
| `injectInitialTabState` / `injectInitialDshPresets` / `injectPageContextBootstrap` | `HtmlLoader:112-185` | 3 × 9.7MB substring 拼接 |
| `loadHTML(9.7MB)` | `WebviewInitializer:470` | EDT 提交 |

合计约 **5 次 ~9.7MB 全串复制 + 一次 9.7MB loadHTML**，全部在 EDT。且 `HtmlLoader` 是每窗口新建实例（`OpencodeBuddyChatWindow:231`），**多 tab 时成本 ×N**。

对比 cc-gui `de536192` 的注释：

> This reads and transforms the approximately 10 MB bundled page. Keep it off the EDT even when the Node.js path was already cached.

#### 缺口 C：watchdog 的多会话盲区 + 过度修正

```java
// OpencodeBuddyChatWindow.java:283-291
this.webviewWatchdog = new WebviewWatchdog(
        ...
        () -> streamCoalescer.isStreamActive(),   // ← 只有活动会话
        () -> frontendReady
);
```

`streamCoalescer` 在切 tab 时被替换成目标会话的（`:1666-1669`）。于是：

- 活动会话流式 → 命中流式分支；
- **后台会话流式、活动会话空闲 → watchdog 看到 `false`**，按 45s 阈值判定。而后台流式同样在压 renderer 主线程，存在「后台流式把主线程压住 → 被判卡死 → reload 整个 webview」的路径——这一 reload 会把**所有**会话的 React 状态一起清掉。

同时 `WebviewWatchdog.java:78-83` 把流式超时设为 `Long.MAX_VALUE`：

```java
private static final long STREAMING_HEARTBEAT_TIMEOUT_MS = Long.MAX_VALUE;
```

这是对 cc-gui 3 分钟阈值的过度修正——等于**彻底关闭**流式期间的自动恢复，与 cc-gui「延长但有上限」的意图相反。

#### 缺口 D：reload 后多会话状态全丢

`ChatWindowDelegate.replayCurrentSessionStateToFrontend()`（:577-600）只重放宿主当前 `session`（活动会话）。前端 `useSessionTabs` 的初始状态是单个空白 `tab-1`（:130-149），且无任何持久化（`localStorage` 零命中）。结果：一次 watchdog reload → 后台会话的 tab、消息、未读状态全部消失。

#### 缺口 E：会话切换的重绘补偿缺失

cc-gui `sessionTransition.ts` 结尾有：

```ts
// Clear JCEF native-rendering ghosting left by the outgoing session's overlays
// and input-box content after the transition unmounts/reflows them.
forceWebviewRepaint('session-transition');
```

当前项目同名文件（109 行 vs cc-gui 115 行）**没有这一行**，也缺 `__flushDeferredTransitionUpdateMessages()` 的 flush。→ 切会话后残留旧会话 overlay 的 ghosting。

---

## 3. 多会话模型差异（逐项定性）

### 3.1 模型对照

| 维度 | cc-gui | opencode-idea-gui |
|---|---|---|
| tab 载体 | 每 Content 一个 `ClaudeChatWindow`（独立 browser + 独立会话） | 每 Content 一个 `OpencodeBuddyChatWindow`，**但新建 tab 转给前端**（`OpencodeBuddyToolWindow:485-490`），实际是单 browser 内多 session tab |
| 会话数 / browser | 1 | N（`useSessionTabs`） |
| 前台会话数 | 1 | **1**（`activeTabId` 单值 / `activeSessionId` 单值，`OpencodeBuddyChatWindow:93`） |
| 切会话 | soft reload transcript | **纯视图切换**（`useSessionTabs:497-520`，保留后台生成，不打断） |
| 后台并发流式 | 无 | 有（`streamingCallbacks.ts:272-283`、`:478-490` 写 `updateBackgroundTab`） |
| 消息状态归属 | 单一 `messages` state | 每 tab 一份 `SessionTabData.messages`（**存的是引用，非副本**） |

`activeSessionId` 是 `volatile String` 单值、`streamCoalescer` 在切 tab 时被替换成目标会话的（`OpencodeBuddyChatWindow:1668`）——这说明**宿主侧的「当前会话」是确定且唯一的**，与「有且仅有一个会话在前台」完全一致。

### 3.2 逐项定性：照抄 5 项，适配 3 项

| 机制 | 作用域 | 判定 |
|---|---|---|
| L1 OnPaint fence | 当前 surface | 照抄 |
| L2-1 tab 激活 cached presentation | 当前 browser | 照抄（cc-gui 本身就是多 Content） |
| L2-2 主题重绘 | 每个窗口 | 照抄（cc-gui 已用多回调 API） |
| L3 初始化 EDT offload | 每个 browser | 照抄（cc-gui 也靠静态缓存解决多 tab） |
| L4 渲染成本 | 当前消息列表 | 照抄 |
| **watchdog 的 `streamActiveCheck`** | **webview** | **适配（M1）** |
| **reload 后状态恢复** | **webview** | **适配（M2）** |
| **流式运行态（累加缓冲 / 索引 / turnId）** | **webview（单份 ref）** | **适配（M0，本版新增）** |

**M1 的本质是语义 bug，不是架构差异**：这个 check 想问的是「本 webview 是否正在接收流式数据」，而 IPC 负载来自**所有**会话（都走同一个 `WebviewEventQueue` → 同一个 CEF browser）。当前实现却绑到了活动会话的单值上。逻辑本身（延长超时）与 cc-gui 完全一致，只是判定源要从「一个 coalescer」换成「所有 coalescer 的 or」。

**M2 是唯一真正的结构性差异**：reload 销毁的不是「当前会话」，而是整个 JS 上下文，里面装着 N 个会话。cc-gui 的爆炸半径是 1，当前项目是 N。但因为 M1 修好之后 reload 本身会变得罕见（`RECOVERY_COOLDOWN_MS = 60s`），它的优先级低于 P0/P1。

**M0（流式运行态）不在这张表里**，因为它不属于任何一层 cc-gui 机制——它是一类独立的问题：**逻辑上属于会话、实现上却是单份 `useRef`** 的状态。cc-gui 一个 webview 一个会话，这类状态天然唯一，所以它根本没有对应能力可供移植。详见 §3.3。

---

### 3.3 并发场景：用户切到「正在执行中」的另一个会话

> 这是第一版方案遗漏的场景，也是最容易被复现的一类 bug。它与「后台会话流式」（A 在后台跑、B 在前台看）是**两个不同的问题**。

#### 场景定义

| 时刻 | 状态 |
|---|---|
| T0 | 会话 A 正在流式（`isStreamingRef=true`，`streamingContentRef` 已累计 3000 字） |
| T1 | 会话 B 也在流式，但 B 在后台 → `onStreamStart` 走后台分支，**只标记 `tabs[B].streamingActive`，然后 return** |
| T2 | 用户点 B 的 tab，`selectTab(B)` |
| T3 | B 的后续 delta 到达，`isActiveSession(B)` 变为 true → 走**活动路径** |

#### 根因：流式运行态是「单份 ref」，不是「每会话一份」

`useStreamingMessages.ts` 里的这些都是 `useRef`，一个 webview 只有一份：

| ref | 用途 | 切 tab 时 |
|---|---|---|
| `streamingContentRef` | 累计已收到的 content delta | **不保存/不恢复** |
| `streamingThinkingRef` | 累计 thinking delta | 不保存/不恢复 |
| `streamingMessageIndexRef` | 当前正在流的 assistant 在数组中的下标 | 不保存/不恢复 |
| `streamingTurnIdRef` | 本回合 turnId | 不保存/不恢复 |
| `isStreamingRef` | 是否在流式 | 不保存/不恢复 |
| `useBackendStreamingRenderRef` | 渲染模式开关 | 不保存/不恢复 |
| `autoExpandedThinkingKeysRef` | 已自动展开的 thinking 块 key | 不保存/不恢复 |
| `trailingStructuralTextBoundaryRef` | 尾部结构性文本边界 | 不保存/不恢复 |
| `window.__streamEndProcessedTurnId` | TURN_END 幂等去重 | 不保存/不恢复 |

而 `selectTab`（`useSessionTabs.ts:501-577`）恢复了几十个字段（messages / loading / streamingActive / taskEvents / dialog / draft / usage / cursors…），**唯独没有恢复这一组流式运行态**。

#### 三个可复现的后果

**后果 1：串内容（最严重）**

```ts
// 活动路径（streamingCallbacks.ts:510-513）
ensureStreamingActive();
streamingContentRef.current += delta;      // ← 在 A 的 3000 字基础上继续加

// 渲染时（useStreamingMessages.ts:302-306）
const deltaContent = streamingContentRef.current || '';
const backendContent = assistant.content || '';
const bestContent = deltaContent.length >= backendContent.length ? deltaContent : backendContent;
//                   ↑ A 的 3000 字 > B 的 500 字 → B 的界面显示 A 的内容
```

**后果 2：串位置**

```ts
// getOrCreateStreamingAssistantIndex（useStreamingMessages.ts:287-291）
const currentIdx = streamingMessageIndexRef.current;      // A 的索引，比如 8
if (currentIdx >= 0 && currentIdx < list.length && list[currentIdx]?.type === 'assistant') {
    return currentIdx;                                     // B 的第 8 条恰好也是 assistant → 直接命中
}
```

B 的 delta 被写到 B 数组的第 8 条上——如果 B 正在流的是第 3 条，就会出现「文字粘在一条已完成的消息上」。

**后果 3：loading 卡死**

`isStreamingRef` 在 A 结束后变 false；B 在后台流式时没被设成 true。切到 B 后，`ensureStreamingActive()` 会补上 true，所以**这一条其实能自愈**（`streamingCallbacks.ts:457-462`）。但如果 A 在流式中切到空闲的 C，`isStreamingRef` 仍是 true，C 的 `onStreamEnd` 会误认为有一次流式在跑，`__streamEndProcessedTurnId` 的复用可能挡掉真正的终态。

#### 方案：M0 —— 流式运行态的「寄存器交换」

不做 Map 化大改（那要动 `useStreamingMessages` 数百行），而是**在切 tab 时做一次上下文切换**：保存当前会话的运行态快照 → 装载目标会话的运行态快照。

```ts
// 新增（建议放在 useSessionTabs 内部，或独立 streamingRuntime.ts）
interface StreamingRuntimeSnapshot {
  isStreaming: boolean;
  content: string;
  thinking: string;
  useBackendStreamingRender: boolean;
  turnId: number;
  lastContentUpdate: number;
  lastThinkingUpdate: number;
  trailingStructuralTextBoundary: string | null;
  autoExpandedThinkingKeys: string[];
  streamEndProcessedTurnId?: number;
}
```

**必须在 `saveCurrentTabSnapshot()` 里保存**（`useSessionTabs.ts:457`）：

```ts
const snap = runtimeStateRef.current;
currentActive.streamingRuntime = captureStreamingRuntime(snap);
```

**必须在 `selectTab()` 里恢复**（紧跟 `setMessages(targetTab.messages)` 之后，其余 setter 之前）：

```ts
restoreStreamingRuntime(
  runtimeStateRef.current,
  targetTab.streamingRuntime ?? defaultStreamingRuntime(),
);
```

**关键设计决策（几条容易做错的）：**

| 决策 | 取值 | 理由 |
|---|---|---|
| `messageIndex` 是否恢复 | ❌ **不恢复，一律设为 `-1`** | tab 在后台期间可能被 `updateBackgroundTab` `push` 过新消息，旧下标已失效；`getOrCreateStreamingAssistantIndex` 会用 `findLastAssistantIndex` 重新定位，反而更安全 |
| `content` / `thinking` 恢复 | ✅ 恢复，但**允许被 backendContent 覆盖** | 后台期间后台分支直接 mutate `tabs[].messages`，运行态缓冲不感知；`patchAssistantForStreaming` 的 `max(delta, backend)` 天然兜底 |
| `turnId` 是否恢复 | ✅ 恢复，且与 `streamEndProcessedTurnId` **成对恢复** | 两者必须一致，否则 TURN_END 幂等判断会错 |
| `turnIdCounterRef` 是否恢复 | ❌ **绝不恢复** | 必须全局单调递增，保证跨会话 turnId 永不碰撞（`sessionTransition.ts:70-73` 已经明确注释了这个约束） |
| 目标 tab 无快照（从未 `onStreamStart`） | 装默认值（`isStreaming=false, content='', index=-1`） | 后台流式的会话本来就没有运行态，靠 `tabs[].messages` 的 backendContent 兜底 |
| 快照存在哪里 | 独立 `Map<tabId, Snapshot>`，**不要塞进 `SessionTabData`** | `SessionTabData` 每次 `updateBackgroundTab` 都会被 `{...clone}`，塞进去会让克隆成本变高 |

#### 附带必须修的一处：后台写入的原地 mutate

```ts
// streamingCallbacks.ts:484-500
const last = t.messages[t.messages.length - 1];
last.content = (last.content || '') + delta;   // ← 原地改对象，违反不可变性
t.messages.push({ ... });                       // ← 原地改数组
```

`updateBackgroundTab` 克隆了 tab 对象，但 `messages` 数组与内部 message 对象仍是**共享引用**。危害：

- 切过去时 `setMessages(arrB)` 因为数组引用从 `arrA` 变成 `arrB` 仍能触发重渲染，所以**不是致命 bug**；
- 但快速连续切 tab（A→B→A）时，A 的后台 delta 可能在 `setMessages(arrA)` 之后继续 mutate `arrA` → React 认为引用未变 → **丢更新**。

**修法**：改成写时复制（只在 `updateBackgroundTab` 的 updater 里做一次 `slice()` + 替换最后一个元素），成本是一次 O(n) 浅拷贝 + 一个对象，相比每帧全量 map 便宜得多。这与 cc-gui `PERFORMANCE_GUIDE.md` §1「对象引用稳定性」是同一原则的两面：**引用要稳定，但不可变性不能破**。

#### 附带必须修的一处：切 tab 后的 surface 发布

切 tab 是一次**大批量 DOM 替换**（A 的消息树换成 B 的消息树），语义上等价于一次历史渲染。但 `history_dom_committed` 只在历史渲染路径发，切 tab 不发。

- OSR 下：大面积节点替换后的第一帧可能不完整（正是 L1 fence 存在的原因）
- 而 `onTabActivated` 的 OSR 分支走的是 **cached presentation**（重画已有的 backing image），画的是**切换前**的像素，不是 B 的内容

→ 所以「切到正在执行的 B」在像素层需要的是**一次真正的 publication**，不是 cached presentation。做法：`selectTab` 完成后（React 提交后）发一次 `history_dom_committed`，让 fence 走完整的 A/B 相位。

这与 cc-gui 不同：cc-gui 切 tab 是换 browser，每个 browser 有自己的 surface，不存在「同一个 surface 换内容」的问题。**这是多会话模型真正引入的、cc-gui 没有的场景。**

---

### 3.4 场景矩阵：穷举 + 逐项覆盖核对

> 本节是「方案是否完整」的自检表。**覆盖 = 本方案有对应改动且验收可测**；标 ❌ 的是本方案未覆盖、需要单独决策的项。

#### S1 会话切换（tab 层）

| ID | 场景 | 现状行为 | 覆盖 | 依据 |
|---|---|---|---|---|
| S1.1 | A 空闲 → B 空闲 | 纯视图切换，`setMessages` 引用替换 | ✅ | P0-3 + §4 附（引用稳定） |
| S1.2 | A 流式 → B 空闲 | A 的剩余 delta 走后台分支写 `tabs[A]` | ✅ | 现有后台分支 + M1 |
| S1.3 | A 空闲 → B 流式 | B 的 delta 走活动路径 | ✅ | M0（P0.5） |
| S1.4 | A 流式 → B 流式 | 运行态互串 | ✅ | M0（P0.5） |
| S1.5 | 快速 A→B→A→B | 后台 mutate 导致丢更新 | ✅ | M0 改动 4 |
| S1.6 | 切到未绑定 session 的新 tab | `claimUnboundTab` 首次事件绑定 | ✅ | 现有机制 |
| S1.7 | 切 tab 后 OSR 画面 | cached presentation 画的是旧像素 | ✅ | M0 改动 5 |
| S1.8 | 关闭正在流式的 tab | `interrupt_session`（仅 `loading` 为真时） | ⚠️ **需补** | M0 快照未清理 → 见 N5 |
| S1.9 | 关闭当前 tab 后自动切到新 tab | 新建空白 tab，`setMessages([])` | ⚠️ **需补** | 未恢复运行态 → 同 N5 |

#### S2 历史列表 / 加载历史

| ID | 场景 | 现状行为 | 覆盖 | 依据 |
|---|---|---|---|---|
| S2.1 | 当前 tab 空闲，从历史打开 B | `beginSessionTransition(B)` + `load_session` | ✅ | P0-3 |
| S2.2 | **当前 tab 正在流式 A，从历史打开 B** | `beginSessionTransition` 清空运行态但**不 interrupt A**；A 继续在后台跑 | ❌ **未覆盖** | 见 N1、N2 |
| S2.3 | 重复打开当前会话 | soft reload + 去抖 + 5s 超时 | ✅ | 现有机制 |
| S2.4 | 加载中再点另一个会话 | `__sessionTransitioning` 守卫 | ⚠️ 需验证 | 未见显式代际校验 |
| S2.5 | 历史向上分页 | `prependMessages` + 游标 | ✅ | 不涉运行态 |
| S2.6 | 历史加载完立即切 tab | `__softSessionReloadInFlight` 未随 tab 清除 | ⚠️ **需补** | 见 N6 |
| S2.7 | 历史加载后 OSR 首帧 | `history_dom_committed` → fence | ✅ | P0-1 |

#### S3 从历史会话继续对话

| ID | 场景 | 现状行为 | 覆盖 | 依据 |
|---|---|---|---|---|
| S3.1 | 历史恢复后发送第一条 | 运行态为默认值，`onStreamStart` 建立 | ✅ | M0 默认快照 |
| S3.2 | 历史恢复后首轮流式 | `streamingContentRef` 从空累计 | ✅ | M0 |
| S3.3 | 历史游标（`messageBaseIndex` / `opencodeListStart` / `prependedHistoryMessageCount`） | `selectTab` 已恢复 | ✅ | 现有机制 |
| S3.4 | 历史会话 revert / undo | `revertBoundaryId` 按 tab 恢复 | ✅ | 不涉运行态 |
| S3.5 | 历史会话 fork | 新建 session，走 `loadHistorySession` 同路径 | ⚠️ | 继承 S2.2 风险 |
| S3.6 | 历史会话 compact | `isCompacting` / `compactingStartTime` 按 tab 恢复 | ✅ | 现有机制 |

#### S4 后台事件（非活动会话）

| ID | 场景 | 现状行为 | 覆盖 | 依据 |
|---|---|---|---|---|
| S4.1 | 后台请求权限 | 写入 `t.pendingPermissions`，切过去 `dialogStore.restore` | ✅ | `permissionCallbacks.ts:65-71` + `selectTab:551-556` |
| S4.2 | 后台请求 question | 同 S4.1（`pendingQuestions`） | ✅ | 同上 |
| S4.3 | 后台 TURN_END | 按 `(sessionId, turnId)` 幂等 | ✅ | 身份模型已修 |
| S4.4 | 后台报错 | `updateBackgroundTab` 写错误卡片 | ✅ | 现有机制 |
| S4.5 | 后台 usage / 标题 | 按 session 路由 | ✅ | 身份模型已修 |
| S4.6 | 后台会话被删除（外部） | `closeTabBySessionId` | ⚠️ 需验证 | 未见外部删除事件的完整链路 |

#### S5 生命周期

| ID | 场景 | 现状行为 | 覆盖 | 依据 |
|---|---|---|---|---|
| S5.1 | watchdog reload / recreate | 只重放活动会话，其余 tab 退回空白 | ✅ | M2 |
| S5.2 | **IDE 重启** | **刻意不恢复**（`TabSessionRestorePolicy` 注释：工具窗口打开必定是全新会话） | ⚠️ **不要动** | 见 N4 |
| S5.3 | 项目关闭 / 工具窗口关闭 | `dispose` | ✅ | 不涉 |
| S5.4 | detached window | 独立 frame + 独立 browser | ⚠️ 需验证 | 未纳入本次范围 |

#### S6 渲染 / Surface

| ID | 场景 | 现状行为 | 覆盖 | 依据 |
|---|---|---|---|---|
| S6.1 | 切 tab 后内容替换 | 同一 surface 换内容，无 publication | ✅ | M0 改动 5（仅 `selectTab`） |
| S6.2 | **在同一 tab 内从历史切会话后内容替换** | 同 S6.1，但走 `beginSessionTransition` | ❌ **未覆盖** | 见 N3 |
| S6.3 | 后台高频 delta 是否抖动活动视图 | 后台不写活动 `messages` | ✅ | 现有机制 |
| S6.4 | 主题切换多窗口 | Swing + JS 双路 | ⚠️ 待核实 | `ThemeConfigService` 是否支持多回调（§2.1 #7） |

#### S7 身份 / 路由

| ID | 场景 | 现状行为 | 覆盖 | 依据 |
|---|---|---|---|---|
| S7.1 | 同一 session 绑定两个 tab | `claimUnboundTab` 检查 `some()` → 不允许 | ✅ | 现有机制 |
| S7.2 | 空 sessionId 事件 | 丢弃 + 计数 | ✅ | 身份模型已修 |
| S7.3 | **tab.sessionId 与 currentSessionId 不一致** | `loadHistorySession` 只改后者，不改前者 | ❌ **未覆盖** | 见 N2 |

#### 本轮新发现的 6 个缺口

| ID | 缺口 | 严重度 | 归属 |
|---|---|---|---|
| **N1** | `loadHistorySession` 走 `beginSessionTransition`，会**清空**运行态而非保存。M0 的 save 点只放在 `saveCurrentTabSnapshot` 里，覆盖不到这条路径 | 高 | P0.5 必须修 |
| **N2** | 从历史切走一个正在流式的会话后，`tab.sessionId` 仍是旧值 → `isActiveSession(A)` 仍返回 true → A 的 delta 被当成本地会话，**串到 B 的视图** | **严重** | P0.5 必须修 |
| **N3** | M0 改动 5（切后 publication）只挂在 `selectTab`，`loadHistorySession` 路径不发 | 中 | P0.5 一并补 |
| **N4** | M2 说「tab 骨架持久化」，但现状**刻意不在 IDE 重启时恢复**，不能改动这条 | 中 | M2 收窄范围 |
| **N5** | `closeTab` / 关闭后自动新建 tab 未清理/初始化运行态快照 | 中 | P0.5 一并补 |
| **N6** | `__softSessionReloadInFlight` 是 window 全局单值，切 tab 不清除 → 目标 tab 的同会话 reload 被误判「已在途」而跳过 | 中 | P0.5 一并补 |

---

## 4. 迁移方案

### P0：接通断链（改动最小、收益最直接，不触碰任何架构）

#### P0-1 注册 fence 的 5 个全局函数

- 文件：`webview/src/main.tsx`
- 位置：现有 `window.updateLinkifyCapabilities` 注册旁（:653-657），在 `ReactDOM.createRoot` 之前
- 内容：照 `jetbrains-cc-gui/webview/src/main.tsx:691-695` 一对一搬运

```
window.__ccguiSurfaceDamagePhaseA = beginSurfaceDamagePulse;
window.__ccguiSurfaceDamagePhaseB = advanceSurfaceDamagePulse;
window.__ccguiSurfaceDamageReplace = replaceSurfaceDamagePulse;
window.__ccguiSurfaceDamageFinish = finishSurfaceDamagePulse;
window.__ccguiSurfaceDamageCancel = cancelSurfaceDamagePulse;
```

- 需要同时确认 `surfaceDamagePulse.ts` 的 5 个导出名与 Java 期望一致（当前文件已一致）
- `webview/src/global.d.ts` 补 `__ccguiSurfaceDamage*` 5 个可选函数声明 + `onTabActivated`

**验收**：OSR tab 激活不再出现 1s 空转；日志里 `surface_damage_applied` 开始被 `WindowEventHandler:83` 收到；`SurfaceFrameFence` 的 `attempt` 正常走完 A→B→final 而非 timeout。

#### P0-2 定义 `window.onTabActivated`

- 文件：`webview/src/main.tsx`
- 内容：`window.onTabActivated = () => { forceWebviewRepaint('tab-activated'); }`

**验收**：windowed JCEF（macOS/Windows 默认）tab 激活时 ghosting 消失。

#### P0-3 补会话切换重绘补偿

- 文件：`webview/src/hooks/windowCallbacks/sessionTransition.ts`
- 在 `beginSessionTransition` 尾部、`__cancelPendingUpdateMessages()` 之后加 `forceWebviewRepaint('session-transition')`
- 同时补 `releaseSessionTransition` 的 `__flushDeferredTransitionUpdateMessages()`（对齐 cc-gui，防止 transition 期间到达的快照丢失）

**注意（多会话适配）**：必须区分两种切换语义，见 M8。

---

### P0.5：M0 —— 流式运行态隔离（并发正确性，建议与 P0 同批）

> 不修这一条，用户每次「切到正在执行的会话」都可能看到串内容 / 串位置。它是**必现**问题，不是概率问题，因此优先级高于所有性能项。

#### 改动 1：`saveCurrentTabSnapshot` 保存运行态

文件 `webview/src/hooks/useSessionTabs.ts:457`。在现有几十个字段赋值之后追加：

```ts
currentActive.streamingRuntime = captureStreamingRuntime(runtimeStateRef.current);
```

`captureStreamingRuntime` 返回 §3.3 定义的 `StreamingRuntimeSnapshot`（10 个字段，**不含** `messageIndex`、**不含** `turnIdCounterRef`）。

#### 改动 1b：`beginSessionTransition` 也必须保存（N1，覆盖 S2.2）

**这是最容易漏的一处。** 「从历史列表打开另一个会话」不走 `selectTab`，而是走 `useSessionManagement.loadHistorySession` → `beginSessionTransition(B)`（`sessionTransition.ts`）。而 `beginSessionTransition` 内部经 `__resetTransientUiState` **直接清空**全部流式 refs——不清空就等于不保存，旧会话的运行态直接丢失。

`buildResetTransientUiState`（`sessionTransition.ts:52-100`）里 `opts.isStreamingRef.current = false` 之前插入：

```ts
// 清空前先把当前会话的运行态存档，切回来才能续上
opts.captureStreamingRuntime?.();
```

并在 `useSessionTabs` 暴露一个 `archiveActiveRuntimeForSession(sessionId)`，按 `currentSessionIdRef` 定位要存到哪个 tab。

> 这样 M0 与 M8 就统一了：**`session-load` 不再是「清空」，而是「存档旧会话 + 装载新会话（默认值）」**；真正要清空的只有「会话被删除 / tab 被关闭」这类终态路径。

#### 改动 2：`selectTab` 恢复运行态

同一文件 `:520` 附近，紧跟 `setMessages(targetTab.messages || [])` 之后：

```ts
restoreStreamingRuntime(
  runtimeStateRef.current,
  targetTab.streamingRuntime ?? DEFAULT_STREAMING_RUNTIME,
);
```

`restoreStreamingRuntime` 必须做到：
- `isStreamingRef.current = snapshot.isStreaming`
- `streamingContentRef.current = snapshot.content`
- `streamingThinkingRef.current = snapshot.thinking`
- `useBackendStreamingRenderRef.current = snapshot.useBackendStreamingRender`
- `streamingTurnIdRef.current = snapshot.turnId`
- `streamingMessageIndexRef.current = -1`（**强制**，见 §3.3 决策表）
- `autoExpandedThinkingKeysRef` 用快照重建（clear + addAll）
- `window.__streamEndProcessedTurnId = snapshot.streamEndProcessedTurnId`
- `lastContentUpdateRef` / `lastThinkingUpdateRef` / `trailingStructuralTextBoundaryRef` 一并恢复
- **不碰** `turnIdCounterRef`

#### 改动 2b：修正 `tab.sessionId` 与 `currentSessionId` 不一致（N2，严重）

**这是本轮发现的最严重问题**，出现在「从历史列表切走一个正在流式的会话」时。

时序：

```
当前 tab:  { id: 'tab-1', sessionId: 'A' }      currentSessionIdRef = 'A'      A 正在流式
用户在历史列表点 B
  → loadHistorySession('B')
      → beginSessionTransition('B')   // currentSessionIdRef 变 'B'，清空流式 refs
      → sendBridgeEvent('load_session', { sessionId: 'B' })
  → 前端 tab-1.sessionId 仍是 'A'     // ← 只有 sessionCallbacks 收到 sessionId 变更事件才更新
  → Java 侧 switchSession('B')
  → A 的 turn 并未 interrupt，继续推送 delta
```

此时 `isActiveSession('A')`（`useSessionTabs.ts:312-319`）的判定是：

```ts
if (currentSessionIdRef.current === sessionId) return true;        // 'B' === 'A' → false
const currentActive = tabsRef.current.find(t => t.id === activeTabIdRef.current);
if (currentActive.sessionId === sessionId || currentActive.id === sessionId) return true;
//   'A' === 'A'  →  true  ← 致命
```

→ **A 的 delta 被判定为「活动会话」**，走活动路径 `streamingContentRef += delta`，最终渲染到**正在显示 B 的视图上**。这就是「从历史列表切走一个流式会话，回来后发现消息串了」的根因。

**修法（两处，缺一不可）：**

1. **`loadHistorySession` 切会话前，先把当前 tab 的绑定改掉或解绑**。二选一，取决于产品语义：
   - **方案 A（推荐）**：切会话时把当前 tab 的 `sessionId` 立即置为新会话 → `tab-1.sessionId = 'B'`，A 自然失去归属，它的 delta 走 `updateBackgroundTab` → 找不到 tab → 丢弃 + 计数（**已有兜底，不会串**）
   - **方案 B**：为 A 保留一个孤儿 tab（`sessionId='A'`，`isOrphan=true`），A 的后台输出仍有地方落，用户可切回去看。成本更高，但体验更好。
2. **`isActiveSession` 增加一致性断言**：如果 `currentSessionIdRef.current` 与 `currentActive.sessionId` 不一致，**以 `currentSessionIdRef` 为准**（它才是「当前视图正在显示什么」的权威），并在 `cardDebugLog` 里记录一次，便于发现其他不一致来源。

```ts
const isActiveSession = (sessionId) => {
  if (!sessionId) return false;
  // 当前视图的权威来源是 currentSessionIdRef；tab.sessionId 可能滞后于它
  if (currentSessionIdRef?.current) return currentSessionIdRef.current === sessionId;
  const currentActive = tabsRef.current.find(t => t.id === activeTabIdRef.current);
  return !!currentActive && (currentActive.sessionId === sessionId || currentActive.id === sessionId);
};
```

> 注意：改成「以 `currentSessionIdRef` 为准」后，**未绑定 session 的新 tab**（`currentSessionIdRef = null`）必须仍能匹配自己（`currentActive.id === sessionId` 分支），否则新 tab 的首条事件会被判为后台。所以是「有值时才以它为准」，而不是无条件。

#### 改动 3：`onStreamEnd` / `closeTab` 清理该会话的运行态（N5）

- `streamingCallbacks.ts:824` 附近，`isStreamingRef.current = false` 时把该会话的 snapshot 从 `Map` 里删掉，并同步 `tabs[tabId].streamingRuntime = undefined`，避免切回来时装载到一份「已结束但仍标记 streaming」的过期快照。
- `useSessionTabs.ts:941` 的 `closeTab`：关闭前 `runtimeMap.delete(tabId)`；`prev.length <= 1` 自动新建空白 tab 的分支（:954-981）里，必须把运行态**重置为默认值**（它已经清了十几个 React state，但没清 refs）。

#### 改动 4：后台写入改写时复制

`streamingCallbacks.ts:484-500`（`onContentDelta` 后台分支）与 `:516` 起的 `onThinkingDelta` 同类分支：

```ts
// 现在（原地 mutate）
const last = t.messages[t.messages.length - 1];
last.content = (last.content || '') + delta;

// 改成（写时复制）
const idx = t.messages.length - 1;
const last = t.messages[idx];
const next = t.messages.slice();
if (last && last.type === 'assistant') {
  next[idx] = { ...last, content: (last.content || '') + delta };
} else {
  next.push({ type: 'assistant', content: delta, isStreaming: true, timestamp: ... });
}
t.messages = next;
```

#### 改动 5：内容替换后触发一次 surface 发布（覆盖 S6.1 + S6.2，N3）

**两条路径都要挂**，不能只挂 `selectTab`：

| 路径 | 触发点 | 说明 |
|---|---|---|
| 切 tab（A↔B） | `selectTab` 之后 | 同一 surface 换内容 |
| 同 tab 内切会话（历史列表） | `historyLoadComplete` 之后 | 已有 `history_dom_committed` 机制，但需确认它在 `beginSessionTransition` 守卫下仍会发 |

实现：

```ts
// React 提交后（useLayoutEffect 或 rAF），发一次
sendBridgeEvent('history_dom_committed', String(++commitEpochRef.current));
```

必须满足三个前提：
1. `historyRenderCommitEpoch` 继续**单调递增**（§5 红线 4）
2. 只在**内容确实变了**时发（比较目标 tab 与当前 messages 的引用/长度），避免 A→A 空切换浪费一次 fence
3. **`__sessionTransitioning` 守卫期间不要抑制它**——切换过程中的内容替换同样需要发布，否则切完是旧像素、等下一个 delta 才刷新

> windowed 平台该事件被 Java 侧忽略（`tryPrepareSurfaceRefresh` 在非 OSR 分支直接返回），无额外成本。

#### 改动 6：`__softSessionReloadInFlight` 不能跨 tab 存活（N6）

`useSessionManagement.ts:263-270` 的去抖守卫是 **window 全局单值**：

```ts
if (window.__softSessionReloadInFlight === sessionId) { /* 跳过 */ }
```

场景：A 的 soft reload 在途（`__softSessionReloadInFlight = 'A'`）→ 用户切到 B → B 的 tab 里点「重新打开 A」（B 也是 A 的另一处入口？）或 A 的 reload 完成后守卫未清 → 后续 A 的 reload 被误判为「已在途」而跳过，界面停在旧内容。

修法：
- 把去抖守卫按 sessionId 存 Map（`Map<sessionId, token>`），而不是单值
- `selectTab` / `closeTab` 时不清理（它是 per-session 的，与 tab 无关）
- `historyLoadComplete` 到达时按 sessionId 释放对应条目
- 保留现有 5s 超时兜底，但也要按 sessionId 独立计时

> 这条严格说属于「身份单值化」的同类问题（§3.3 第三类），只是它不影响闪屏，影响的是「重新加载被跳过」。优先级与 M0 同级但可独立修。

#### 验收

| 场景 | 期望 |
|---|---|
| A 流式（3000 字）→ 切到正在流式的 B（500 字） | B 只显示 B 的内容，长度从 500 继续增长；**不出现 A 的文本** |
| B 流式中途切走再切回 | B 的内容连续，无跳变、无重复段落 |
| A 空闲 → 切到正在流式的 B | B 的后续 delta 正常渲染（`ensureStreamingActive` 自愈生效） |
| 快速 A→B→A→B 连切 | 两次进入 B 的内容一致，无丢更新（改动 4 保证） |
| **A 流式中，从历史列表打开 B** | B 的视图**不出现** A 的增量（N2）；A 的后台输出有归属（方案 A 丢弃+计数 / 方案 B 落孤儿 tab） |
| **A 流式中，从历史列表打开 B 再切回 A** | A 的运行态从存档恢复，`turnId` 与切走时一致（N1） |
| 切 tab 后立刻观察 OSR 画面 | 显示 B 的内容，不是 A 的残帧（改动 5） |
| 从历史打开会话后立刻观察 OSR 画面 | 显示新会话内容，不是上一个会话的残帧（改动 5 第二条路径） |
| 关闭正在流式的 tab 再新开 | 新 tab 的运行态是默认值，不继承已关闭会话的 `streamingContentRef`（N5） |
| 同会话 soft reload 在途时切 tab 再切回 | reload 不被误判为「已在途」而跳过（N6） |

---

### P1：把初始化搬出 EDT（冻结的主因，多 tab 下收益 ×N）

#### P1-1 `prepareUIComponents()` 后台化

照 `de536192` 的结构改造 `WebviewInitializer`：

```
createUIComponents()                        // EDT
  ├─ assert EDT，否则转投
  ├─ 去重：initializationInProgress CAS
  ├─ generation++ : initializationGeneration
  ├─ showLoadingPanel()
  └─ executeOnPooledThread(prepareUIComponents(generation, ...))
        ├─ verifyAndCacheNodePath(saved)     // ← 现在这里还在 EDT（:154-165）
        ├─ detectNodeWithDetails()           // 已后台
        ├─ checkEnvironment()                // ← 现在还在 EDT（:1169-1185）
        └─ loadChatHtmlWithInitialTabState() // ← 现在还在 EDT（:371）
  ↓ 回投 EDT
completePreparedInitialization(generation, result)
  ├─ isCurrentInitialization(generation) 守卫，过期丢弃
  └─ JBCefBrowserFactory.create + loadHTML + Swing 挂载   // 只有这部分留在 EDT
```

**多会话适配（M6）**：`HtmlLoader` 改为**静态共享缓存**。

- 缓存 key：`theme + locale + fontScale + 影响注入的开关` 的组合
- 缓存 value：`themedHtml`（已做完主题注入的 9.7MB 字符串）
- 失效：主题/语言/字号变更时清空
- 这样 N 个 tab 只做 **1 次** 9.7MB 读取 + 主题注入，其余 N-1 次直接命中

**更彻底的做法（建议同批做）**：把 `injectInitialTabState` / `injectInitialDshPresets` / `injectPageContextBootstrap` 从「改 9.7MB 字符串」改成「`loadHTML` 之后 `executeJavaScript` 注入」。当前 `WebviewInitializer:842-856` 的 `buildFrontendConfigurationScript` + `PageConfigurationCache` 已经是这个模式，直接复用即可。收益：每窗口少 3 次 9.7MB 全串复制。

#### P1-2 主题注入改线性扫描

`HtmlLoader:72-88` 的两次 `replaceFirst` 改为：定位真实的 `<html` / `<body` 起始标签（第一个出现在文档开头区域、非字符串字面量内的），只做一次线性 pass 生成新串。规避 9.7MB 单行 bundle 中内嵌 `<body>` 字符串被误改（这是 `de536192` 明确指出的真实 bug）。

#### P1-3 `checkEnvironment` 加 5s 超时 + 强杀

`NodeDetector` 的 probe 进程加 `waitFor(5, SECONDS)`，超时 `destroyForcibly()`。防止挂死的 node 把初始化永久 wedge（当前 `findNodeExecutable` 有 30s 的 `detectionFuture.get`，叠加后最坏 35s）。

#### P1-4 剪贴板彻底异步

- `WebviewInitializer:319-333` 的 `currentBridges.clipboardPathQuery` handler 仍在 CEF 回调线程同步读剪贴板（注释 :326-332 自己承认 X11/远程剪贴板会卡）
- 改为：handler 立即返回一个 pending 响应，读操作投到 pooled thread，完成后用 `executeJavaScript` 回投
- `ClipboardHandler:91-106` 已经转到 EDT，但 EDT 上做阻塞 I/O 仍会卡 UI；进一步投到 pooled thread（除 `setContents` 必须 EDT）

---

### P2：多会话 watchdog 与恢复（M1、M2）

#### M1 watchdog 流式判定聚合化

**问题（语义 bug，而非架构差异）**：`() -> streamCoalescer.isStreamActive()` 只看活动会话。但这个 check 想问的其实是「**本 webview 是否正在接收流式数据**」——因为 IPC 饱和来自所有会话的 delta 推送，它们都走同一个 `WebviewEventQueue` → 同一个 CEF browser → 同一个 renderer。当前实现把全局问题绑到了单值上。

**最小改法（与 cc-gui 逻辑完全一致，只是判定源换了）**：

```java
// OpencodeBuddyChatWindow 新增
boolean anySessionStreamActive() {
    for (StreamMessageCoalescer c : sessionCoalescers.values()) {
        if (c.isStreamActive()) return true;
    }
    return false;
}

// :283-291 的 watchdog 注入
-        () -> streamCoalescer.isStreamActive(),
+        () -> anySessionStreamActive(),
```

再加上 `WebviewWatchdog:83` 的常量调整（`Long.MAX_VALUE` → 180s，对齐 cc-gui 的 3 分钟）。**这两处改完，watchdog 的行为就与 cc-gui 完全等价了。**

**可选精细化（不必与最小改法一起做）**：如果希望「用户正看着的会话」容忍度更高，可把 `StreamActiveCheck` 细化为三态：

| 状态 | 阈值 | 理由 |
|---|---|---|
| `IDLE` | 45s | 现状 |
| `BACKGROUND_STREAMING` | 180s | 后台流压 renderer，但用户不在看，可接受更长 |
| `ACTIVE_STREAMING` | `Long.MAX_VALUE` | 长工具执行不应被本地超时打断（保留当前项目的选择） |

**验收**（最小改法即可覆盖）：
- 活动会话空闲 + 后台会话流式 60s，watchdog 不 reload
- 所有会话空闲 + 心跳停止 45s，watchdog 正常 reload
- 后台会话流式结束后 45s 内恢复正常阈值

#### M2 reload 后的分级状态恢复

**问题**：`replayCurrentSessionStateToFrontend()`（`ChatWindowDelegate:577-600`）只重放活动会话；前端 `useSessionTabs` 回到单个空白 `tab-1`，其余 N-1 个会话的前端状态全丢。

**先划清边界（N4）：只处理「同进程内 reload」，不碰「IDE 重启」。**

`TabSessionRestorePolicy` 的类注释明确写了：

> 工具窗口打开时必定是全新会话，所以这里不再有「按落盘状态在开屏时恢复历史」的分支

即 **IDE 重启后不恢复 tab 是当前刻意设计**，不是缺陷。本方案不应改动它（也不要改成 `sessionStorage` 持久化——那会事实上改变产品行为）。

两者的差别决定了实现方式完全不同：

| 场景 | JS 上下文 | Java 侧会话对象 | 恢复数据来源 |
|---|---|---|---|
| **watchdog reload** | 销毁重建 | **仍在**（`sessionAdapters` / `sessionCoalescers`） | **Java 内存**，不需要持久化 |
| IDE 重启 | 全新 | 全部重建 | 现状：不恢复（保持） |

→ 所以 M2 不需要 `sessionStorage`：**Java 侧本来就有全部会话的内存状态，直接推回去即可**。这也顺带避开了配额与隐私问题。

**方案（三级）**：

| 级别 | 恢复内容 | 时机 |
|---|---|---|
| L0 骨架 | 所有 tab 的 `{id, sessionId, title, loading, streamingActive}` | reload 后**立即**，一次批量推 |
| L1 活动会话 | `messages` 全量 + `usage` + `permissionMode` + 流式重放 | L0 之后紧跟（现有逻辑） |
| L2 后台会话 | 不主动拉；tab 上显示 `loading` / `streamingActive` 标记，用户点开时走 `loadHistorySession` | 用户交互时 |

实现点：

1. Java 侧新增「推全部 tab 骨架」：数据源是 `sessionAdapters` / `sessionCoalescers` 的 key 集合 + `OpencodeSession` 的 title/loading，**不读落盘**（`TabStateService` 仍只用于 IDE 重启，不参与这条路径）
2. 复用已有的 `pushCurrentTabStateToFrontend()`（`ChatWindowDelegate:479-489`，`runtimeRecovery` 分支调用），扩展为骨架批量推
3. reload 后必须复位 per-session 的 `pageGeneration`，否则旧 generation 的回调会被 `guardPageScript` 丢弃
4. **运行态快照（M0）在 reload 后同样失效**：前端 `Map` 是内存结构，随 JS 上下文一起没了。此时各会话的 `tabs[].messages` 也一起没了（L2 不再主动拉），所以恢复后统一装载 `DEFAULT_STREAMING_RUNTIME` 即可；正在流式的会话由 Java 重放 `onStreamStart` 重建（`:577-600` 已有 `streamActive` 判断）
5. 保持现存的**有界性**：`RECOVERY_COOLDOWN_MS = 60s` + `MAX_STARTUP_RECOVERY_ATTEMPTS = 2`（`WebviewWatchdog:28-33`）不动——多会话下绝不能引入 reload 风暴

**验收**：模拟 watchdog reload → 3 个 tab 的标题/sessionId/loading/streamingActive 全部恢复；活动 tab 消息完整且能继续流；后台 tab 点开后可拉到历史；**IDE 重启后行为与现状一致（仍不恢复）**。

### P3：多会话的切换语义（M8）

#### M8 区分「切 tab」与「切会话」

**问题**：`beginSessionTransition` 会清空 `isStreamingRef` / `streamingContentRef` / `turnId` 等 transient 状态。当前项目里有两类切换：

- **切 tab**（`selectTab`）：纯视图切换，**必须保留后台生成**（`useSessionTabs:501` 注释明确「never interrupts」）
- **切会话**（`loadHistorySession`）：真正替换会话，需要 transition guard

如果 P0-3 直接把 `forceWebviewRepaint('session-transition')` 塞进无条件的 transition 路径，切 tab 时会顺带清掉流式 refs → 后台生成被打断，正是「多会话兼容」最容易踩的坑。

**方案**：`beginSessionTransition(opts)` 加 `mode: 'tab-switch' | 'session-load'`：

| 动作 | `tab-switch` | `session-load` |
|---|---|---|
| `forceWebviewRepaint('session-transition')` | ✅ | ✅ |
| 清 `isStreamingRef` / `streamingContentRef` | ❌ **禁止**（会打断后台流） | ✅ |
| 清 `autoExpandedThinkingKeysRef` | ✅（UI 状态属于当前视图） | ✅ |
| `__cancelPendingUpdateMessages()` | ⚠️ 仅当目标 tab 无流式 | ✅ |
| `turnIdCounterRef` 单调递增 | 保持（跨会话唯一，React key 不冲突） | 保持 |

**验收**：A 在流式时切到 B 再切回 A，A 的流式内容连续、无丢帧、无「消息闪一下消失」。

---

### 附：已排除项与遗留性能项

以下四项在初版方案中被列为多会话改造点，复核后**不属于本次范围**。列在这里是为了避免后续重复踩坑，不影响 P0-P3 的实施。

### 已排除：fence revision 不需要 sessionId

`SurfaceFrameFence.requestInternal`（`SurfaceFrameFence.java:303-310`）：

```java
Request latest = latestOwnedRequest(browserIdentity, cefBrowser, pageGeneration, readyEpoch);
if (latest != null && contentRevision <= latest.contentRevision) {
    return latest;      // ← 不生成新 serial
}
```

`historyRenderCommitEpoch` 是单调递增的 React state（`useWindowCallbacks.ts:116`，只 `++`，切会话不重置）。因此切会话瞬间迟到的旧会话 commit，其 `contentRevision` **必然 ≤ 新会话已发布的值**，会被这一行自动挡掉。

→ `SurfaceFrameFence` 634 行不用改，`history_dom_committed` 负载保持 `<epoch>` 字符串即可。

### 已排除：切会话不会导致整树重渲染

- `saveCurrentTabSnapshot`（`useSessionTabs.ts:461`）是 `currentActive.messages = snap.messages` —— **直接引用赋值**，没有深拷贝
- `MessageItem` 是 `memo`（`MessageItem.tsx:487`）
- `MessageList:471` 用 `messageKey` 作 `Fragment key`

所以切回来时 message 对象是同一批引用，memo 能命中。**唯一要守住的纪律：任何地方都不要对 messages 做 `JSON.parse(JSON.stringify())`**（例如落盘、导出、deep-equal 比较前的归一化），一旦深拷贝，这条结论立即失效。

### 已排除：缓存无需按 sessionId 分区

- `normalizeBlocksCache` / `shouldShowMessageCache` 是 `WeakMap`，按对象生命周期回收，天然无需管理
- `mergedAssistantMessageCache` 已有 `MESSAGE_MERGE_CACHE_LIMIT = 3000`（`messageMergeCache.ts`）兜底

切会话不清空只会降低缓存命中率的理论上限，不产生正确性问题，也不产生无界内存增长。**按 sessionId 分区 + LRU 属于可选优化**，与闪屏/冻结无关。

### 遗留性能项（与多会话无关，可独立排期）

`useSessionTabs.ts:248-284` 的 effect 把 `messages` 放进依赖数组，且每次都 `setTabs(prev => prev.map(...))`：

```ts
setTabs((prev) => prev.map((tab) => {
  if (tab.id === activeTabId) { return { ...tab, messages, loading, streamingActive, /* 20+ 字段 */ }; }
  return tab;
}));
```

**这是单会话时代就存在的问题**：活动会话每来一个 delta，就整表 map 一次并把整个 `messages` 数组塞进新 tab 对象。它与后台会话数量无关，也与 flash/freeze 无直接关系。

若要优化（非本次范围）：
1. 活动 tab 的 `messages` 改走 `messagesRef`，只在「切 tab / 落盘 / 标题变化」时写回 `tabs`
2. 确实需要实时同步的字段（`title` / `loading` / `streamingActive`）单独用一个轻量 state
3. `setTabs` 走 `tabIndexByIdRef` 定位，替换 `prev.map`

---

## 5. 不变量与禁止清单

迁移过程中必须长期守住（可总结为「四条硬红线」）：

1. **不得用固定延时 / rAF / `wasResized()` / `paintImmediately()` 推断像素已完成**。只有「真实非 popup `OnPaint` 到达 + 前端精确 token ACK」才算。
2. **不得在历史 surface 恢复路径上 reload 或重新 `loadHTML()`**。tab 激活只能唤醒已有 pending 或做 cached presentation。
3. **`executeJavaScript()` 返回 ≠ 脚本已执行**。fence 的相位推进必须等前端 ACK。
4. **不得破坏 `historyRenderCommitEpoch` 的单调性**。它是 fence watermark 去重的唯一依据（`contentRevision <= latest` 即复用）；一旦在切会话时重置，旧会话迟到的 commit 就会推进 watermark，跨会话误判「已发布」。
5. **不得对 `messages` 做深拷贝**。切 tab 的零成本重渲染完全依赖 `saveCurrentTabSnapshot` 的引用传递 + `MessageItem` 的 `memo`；任何 `JSON.parse(JSON.stringify())` 都会让整列重渲染。
6. **不得新增第二份流式运行态**。`streamingContentRef` / `streamingThinkingRef` / `streamingMessageIndexRef` / `streamingTurnIdRef` / `isStreamingRef` / `useBackendStreamingRenderRef` / `autoExpandedThinkingKeysRef` / `trailingStructuralTextBoundaryRef` / `__streamEndProcessedTurnId` 必须始终**恰好一份**，切 tab 时整体交换。任何「再加一个 XXXRef 记录上一个会话」的做法都是错的——会变成两份真相。
7. **`turnIdCounterRef` 永不回退**。它必须全局单调递增以保证跨会话 turnId 唯一；运行态交换时**必须跳过它**。
8. **`tab.sessionId` 与 `currentSessionIdRef` 不允许长期不一致**。「当前视图显示的是哪个会话」的权威来源是 `currentSessionIdRef`；`tab.sessionId` 是它的持久化投影。任何「只改其中一边」的路径（如 `loadHistorySession`）都必须补齐另一边，否则 `isActiveSession` 会把已切走的会话仍判为活动（N2）。

额外禁止：

- 不要把 `StreamActiveCheck` 退化成单会话判断（会引入后台流式误判 reload）
- 不要在 `tab-switch` 时清空 streaming refs
- 不要给 `mergeConsecutiveAssistantMessages` 之类的 Map 缓存去掉 size 上限
- 不要为了性能删除功能、丢弃工具结果、隐藏历史

---

## 6. 测试矩阵

### 6.1 前端（`webview/`，vitest）

| 用例 | 断言 |
|---|---|
| fence 注册 | `main.tsx` 加载后 `typeof window.__ccguiSurfaceDamagePhaseA === 'function'` |
| `onTabActivated` | 调用后触发一次 `forceWebviewRepaint`（mock 计数） |
| 切会话不打断后台流 | A 流式中 `selectTab('B')`，A 的 `isStreamingRef` 保持 true |
| commit epoch 单调 | 连续 3 次切会话 + 历史渲染，`historyRenderCommitEpoch` 严格递增、从不回 0 |
| 切会话引用稳定 | A→B→A 后 `messages[0]` 严格等于切走前的引用（守住「不深拷贝」纪律） |
| 后台 delta 不触发 fence | 后台会话 30 次 delta，`history_dom_committed` 发送次数 = 0 |
| **M0：运行态不串内容** | A 累计 3000 字 → `selectTab(B)` → B 的 delta → B 最后一条 assistant 的 `content` **不含** A 的任何片段 |
| **M0：运行态不串位置** | A 的 `streamingMessageIndexRef=8` → 切到 B（B 有 10 条消息，第 8 条是已完成的 assistant）→ B 的 delta 落在**最后一条** assistant，不是第 8 条 |
| **M0：index 强制重置** | `restoreStreamingRuntime` 后 `streamingMessageIndexRef === -1` |
| **M0：turnId 成对恢复** | 切回 A 后 `streamingTurnIdRef` 与 `__streamEndProcessedTurnId` 与切走时一致 |
| **M0：turnIdCounter 不恢复** | A→B→A 连切后 `turnIdCounterRef` 严格递增，不回退 |
| **M0：后台写入不可变** | 后台 delta 后 `tabs[B].messages` **不是**同一数组引用，且旧引用内容未被改动 |
| **M0：连切不丢更新** | A→B→A→B 四次切换，两次进入 B 的 content 完全一致 |
| **N1：切换会话前存档** | A 流式中 `loadHistorySession(B)` → `beginSessionTransition` 执行前 A 的 snapshot 已入 Map |
| **N2：tab 绑定与 currentSessionId 一致** | A 流式中从历史打开 B → `isActiveSession('A')` 返回 **false**，A 的 delta 不进活动视图 |
| **N5：关闭 tab 清快照** | 关闭流式中的 tab → 该 tabId 的 runtime 条目被移除；关闭唯一 tab 后新 tab 装载默认运行态 |
| **N6：soft-reload 去抖隔离** | A 的 reload 在途时切到 B，B 触发自己的 reload 不被跳过；A 完成后守卫释放 |

### 6.2 Java（`./gradlew test --offline -x instrumentTestCode -x instrumentCode`）

| 用例 | 断言 |
|---|---|
| 初始化不在 EDT | `prepareUIComponents` 里 fail 断言 `!SwingUtilities.isEventDispatchThread()` |
| generation 过期丢弃 | 初始化中途 `recreateWebview()`，旧生成结果不落盘 |
| HTML 缓存共享 | 2 个窗口初始化，`loadChatHtml` 实调用次数 = 1（用计数替身） |
| watchdog 三态阈值 | `IDLE`→45s / `BACKGROUND_STREAMING`→180s / `ACTIVE_STREAMING`→不触发 |
| watchdog 聚合判定 | 活动会话空闲 + 后台会话流式，`streamActiveCheck` 返回 true |
| reload 恢复骨架 | reload 后 `pushCurrentTabStateToFrontend` 推送的 tab 数 = 3 |

### 6.3 实机（JCEF）

- IDEA 2025.2 Remote JCEF（OSR）：多 tab 首屏、tab 反复切换、休眠唤醒
- macOS / Windows windowed：tab 激活 ghosting、主题切换边缘色块
- 3 个会话并发流式 + 反复切 tab
- 长会话（>2000 条）滚动 + 输入
- 强制触发 watchdog reload，验证 3 个 tab 骨架恢复

> 注意：合成测试（jsdom）不能替代 JCEF 实测结论，报告里必须分开写。

---

## 7. 落地顺序与依赖

```
P0（1 天内，全部是「照抄 + 接线」，不动架构）
  P0-1 fence 注册  ──┐
  P0-2 onTabActivated ├─→ 三者独立，可并行
  P0-3 sessionTransition repaint ─┘
        ↓ 立即验证 OSR 1s 空转消失
P0.5（2-3 天，M0 + N1/N2/N3/N5/N6，正确性，必现值）
  改动 1+2 运行态 save/restore  ── 主体，同一处改动
  改动 1b beginSessionTransition 也存档（N1）── 与改动 1 同批
  改动 2b tab.sessionId 一致性（N2）── **最严重，需先定产品语义**
  改动 3 onStreamEnd / closeTab 清理（N5）
  改动 4 后台写入写时复制
  改动 5 内容替换后 publication（N3，两条路径）
  改动 6 soft-reload 去抖改 per-session（N6）── 可独立
        ↓ 验证：切到执行中的会话不串内容 / 从历史切走流式会话不串
P1（2-3 天，照抄 cc-gui de536192）
  P1-1 prepareUIComponents 后台化（含 generation 守卫）
        ├─→ P1-2 主题注入线性扫描（同文件，顺序做）
        └─→ 静态 HTML 缓存（多 tab 共享）
  P1-3 checkEnvironment 5s 超时   ── 独立
  P1-4 剪贴板彻底异步            ── 独立
        ↓ 验证：冷启动 EDT 无长任务；2 tab 初始化总耗时 ≈ 1 tab
P2（1-2 天，webview 级状态适配）
  M1 watchdog 聚合判定 + 三态阈值  ── 一行改动 + 常量，独立
  M2 reload 分级恢复              ── 依赖 M1（先不误判，再谈恢复）
P3（1 天）
  M8 tab-switch / session-load 分流 ── 依赖 P0-3（否则会打断后台流）
```

依赖要点：

- **改动 5 必须在 P0-1 之后**：fence 没接通时发 `history_dom_committed` 只会白等 1s 超时，反而更慢。
- **改动 3 不能早于改动 1+2**：没有快照机制时清理无处可清。
- **M8 与改动 2 共用 `selectTab` 路径**，但语义相反（M8 禁止清 streaming refs，改动 2 要求切换 refs）。二者要一起设计：切 tab 走「保存-装载」而不是「清空-初始化」，正好与 M8 一致；真正要清空的只有 `session-load` 路径。

> **优先级判断**：P0 + P0.5 是「用户能直接感知的正确性修复」（闪屏 + 串内容），P1 是「启动卡顿」，P2 是「低频高损失」。如果要砍，先砍 P2/P3 —— 它们只在 watchdog 真触发 reload 或用户手动 load-history 时才可感知。

每个阶段独立可回滚；P0 与 P1 无交叉，可两人并行。

---

## 8. 风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| fence 接通后暴露既有 bug | 前端脉冲一直没跑，可能有未发现的相位/时序问题 | P0 先只在 OSR 平台灰度；保留 1s timeout 兜底不动 |
| 后台化引入竞态 | `prepareUIComponents` 与 `recreateWebview` 并发 | generation 守卫（照 cc-gui）+ 单测覆盖「过期结果丢弃」 |
| HTML 静态缓存冻结了 per-tab 状态 | `injectInitialTabState` 是 per-window 的 | 缓存只存「主题 + 静态注入」部分；per-tab 注入改为 `executeJavaScript`（P1-1 的彻底做法） |
| watchdog 阈值放宽 → 卡死不恢复 | 后台流式 180s 仍可能挡掉真实卡死 | 只在「确有 session 在流式」时放宽；流式结束立即回落 45s |
| `sessionStorage` 骨架泄漏隐私 | 存的是 sessionId / 标题 | 只存 L0 元数据，不存消息正文；`isShared` 标记一并存 |
| **M0 改 diff 面大** | 要动 `useSessionTabs` + `useStreamingMessages` + `streamingCallbacks` 三个 hook | 优先把 `capture/restore` 抽成独立模块（只读写传入的 ref 对象），三个 hook 各改 3-5 行；用单测锁住 §6.1 的 7 个 M0 用例 |
| **M0 恢复顺序敏感** | `restoreStreamingRuntime` 必须早于下一次 delta，晚于 `setMessages` | 放在 `selectTab` 同步尾巴里执行（`selectTab` 全程无 `await`，JS 单线程保证不会有事件插入） |
| **M0 切 tab 后发 publication 可能抖动** | 大面积 DOM 替换 + A/B 脉冲叠加 | 只在「目标 tab 内容与当前不同」时发；windowed 平台忽略；沿用 1s timeout 兜底 |
| **M2 未做时 reload 仍丢后台 tab** | 已知遗留，非回归 | 记录在案；M2 之前的兜底是用户从历史列表重新打开 |
| **N2 需要产品决策** | 「从历史切走一个正在流式的会话」时，A 的后台输出是丢弃还是保留孤儿 tab | 方案 A（丢弃+计数，改动小）建议先做；方案 B（孤儿 tab）体验更好但要做 tab 生命周期管理，可后续迭代 |
| **N2 修复可能暴露其他不一致** | 把 `isActiveSession` 改成以 `currentSessionIdRef` 为权威后，原本被「误判为活动」掩盖的事件会转走后台分支 | 先在 `cardDebugLog` 加不一致告警，观察一版再收紧；保留 `currentActive.id === sessionId` 分支以兼容未绑定新 tab |
| **S2.4 / S5.4 未验证** | 「加载中再点另一个会话」与 detached window 未做代码核实 | 实施 P0.5 前先各花 30 分钟核实，若确实缺失再补 |

---

## 9. 附：cc-gui 相关提交索引

| 提交 | 主题 | 对应本文 |
|---|---|---|
| `de536192` | keep webview startup work off the EDT | P1-1、P1-2、P1-3 |
| `ba91d994` | offload remaining Node.js detection/verification off the EDT | P1-1 |
| `1d5bc14e` | offload Node.js auto-detection off the EDT | P1-1 |
| `9c8644a1` | prevent UI freeze opening settings on macOS | P1-1（NodeDetector 单例） |
| `3718f464` | prevent WebView freeze on Ctrl+C | P1-4 |
| `a71b0988` | truncate long error messages | P3（错误截断放宽） |
| `2389c23a` | repaint empty webview tabs on activation | P0-2、L2-1 |
| `698d0d95` | repaint all open sessions on theme change | L2-2 |
| `d861baf7` | prevent watchdog reload during streaming | M1 |
| `06c32444` | soft-reload same-session resume | 参考（当前项目会话切换模型不同） |
| `2ed51b68` | defer background-turn reload to stream end | M1（思路） |
| `372d77ec` | harden stream reliability and reduce UI jank | L4 |
| `8989cea1` / `9b6f5942` | memoize list items / smart object reuse | §4 附（已排除） |
| `30323a30` | optimize message dedup performance | L4 |
| `c01f3b81` | eliminate @ file search dropdown flicker | 参考（另一类 flicker） |

文档索引：
- `jetbrains-cc-gui/docs/fix/first-tab-history-render-fix-2026-08-06.md`（L1 权威说明）
- `jetbrains-cc-gui/docs/fix/PERFORMANCE_GUIDE.md`（L4 原则）
- `jetbrains-cc-gui/docs/plans/2026-09-28-performance-remediation.md`（L4 工程化）
- `jetbrains-cc-gui/docs/plans/2026-09-28-performance-remediation-results.md`（L4 结果）

---

## 10. 实施记录

> 状态：**代码已完成并通过编译 / 类型检查 / 单元测试；未做 JCEF 实机验证**。

### 10.1 已完成改动

#### 前端

| 文件 | 改动 |
|---|---|
| `webview/src/main.tsx` | 注册 `window.__ccguiSurfaceDamagePhaseA/B/Replace/Finish/Cancel` 与 `window.onTabActivated`（P0-1/P0-2）。fence 由此从「静默空转到 1s 超时」变为真实工作 |
| `webview/src/global.d.ts` | 补上述 6 个函数 + `__requestSurfaceRepublish` + `restoreSessionTabs` + store 的 `archiveActiveStreamingRuntime` / `clearStreamingRuntimeFor` 声明 |
| `webview/src/utils/streamingRuntime.ts` | **新增**：`StreamingRuntimeSnapshot` + `captureStreamingRuntime` / `restoreStreamingRuntime` / `clearStreamingRuntime`。`messageIndex` 强制 -1、`turnIdCounter` 不参与交换 |
| `webview/src/utils/streamingRuntime.test.ts` | **新增**：8 个用例锁住「不恢复 index / 不碰 counter / 集合替换而非合并 / 清除 guard」 |
| `webview/src/hooks/useStreamingMessages.ts` | 暴露 `trailingStructuralTextBoundaryRef`（快照需要） |
| `webview/src/hooks/useSessionTabs.ts` | ① `runtimeSnapshotsRef` + 4 个 helper；② `saveCurrentTabSnapshot` 存档；③ `selectTab` 恢复 + `contentChanged` 判定 + 释放过期 transition guard + 触发 publication；④ `closeTab` / 关闭唯一 tab 清理运行态；⑤ **`isActiveSession` 改为以 `currentSessionIdRef` 为权威**（N2）；⑥ `getActiveSessionId` 同；⑦ `restoreSessionTabs` effect（M2） |
| `webview/src/hooks/useSessionManagement.ts` | 切到**不同**会话前 rebind `tab.sessionId`，让旧会话失去归属（N2 的写入侧） |
| `webview/src/hooks/windowCallbacks/sessionTransition.ts` | `onBeforeResetTransientState` 钩子（存档先于清空，N1）+ 末尾 `forceWebviewRepaint('session-transition')`（P0-3） |
| `webview/src/hooks/windowCallbacks/registerCallbacks.ts` | 接线 `onBeforeResetTransientState`；注册 `window.__requestSurfaceRepublish` |
| `webview/src/hooks/windowCallbacks/registerCallbacks/streamingCallbacks.ts` | 后台 delta 改**写时复制**；`onStreamEnd` 活动/后台两条路径都清理对应会话的运行态快照 |
| `webview/src/App.tsx` | 解构 `trailingStructuralTextBoundaryRef`；`streamingRuntimeRefs` 用 `useMemo` 固定；传入 `useSessionTabs` |

#### Java

| 文件 | 改动 |
|---|---|
| `ui/toolwindow/OpencodeBuddyChatWindow.java` | `anySessionStreamActive()` 遍历 `sessionCoalescers`；watchdog 注入改用它（M1）；`getHostedSessionIds()`（M2） |
| `ui/WebviewWatchdog.java` | `STREAMING_HEARTBEAT_TIMEOUT_MS`：`Long.MAX_VALUE` → `180_000L`（M1） |
| `ui/WebviewInitializer.java` | `createUIComponents` 改为 EDT 仅做登记 + `prepareInProgress` 去重 + generation 递增；新增 `prepareInitializationOnPooledThread` / `prepareInitializationOffEdt` / `applyPreparedInitialization` / `takePreparedHtml`；`continueCreateUIComponentsAfterNodeSetup` 删掉 EDT 上的 `checkEnvironment` 与版本门，HTML 改取预制品（P1-1） |
| `util/HtmlLoader.java` | 静态按主题缓存 themed HTML（多 tab 共享）；主题注入由两次 `replaceFirst` 全串正则改为 `addStyleToRootTag` 有界线性扫描（P1-2） |
| `bridge/NodeDetector.java` | `waitFor(5s)` 提到 `readLine()` **之前**，并 `destroyForcibly`（P1-3）。原顺序下读阻塞会让 5s 预算永远走不到 |
| `ui/ChatWindowDelegate.java` | `DelegateHost` 加 `getHostedSessionIds()`；新增 `pushHostedSessionTabs()`；`runtimeRecovery` 分支先推骨架再重放活动会话（M2） |
| `test/.../util/HtmlLoaderTest.java` | 新增 4 个用例：root tag 内联样式、不误改 JS 里的 `<body>` 字符串、`<htmlx>` 前缀不误判、自闭合跳过 |

### 10.2 与方案的偏差（重要）

| 项 | 原方案 | 实际 | 原因 |
|---|---|---|---|
| **N6** `__softSessionReloadInFlight` | 认为是单值污染，需改 Map | **撤回，未改** | 复核发现它虽为单值但**按 sessionId 比较**，本身已隔离；同会话去抖正是需求 |
| **P1-4** 剪贴板彻底异步 | 改为 pooled thread + 回投 | **未做** | `clipboardPathQuery` 是 `JBCefJSQuery`，同步返回；改异步必须同时改前端 `getClipboardFilePath` 的 Promise 协议，收益（低频操作）低于风险 |
| **M2** tab 骨架恢复 | 前端 `sessionStorage` 持久化 | **改为 Java 内存推送** | `TabSessionRestorePolicy` 明确「工具窗口打开必定是全新会话」，`sessionStorage` 会事实上改变 IDE 重启行为；Java 内存覆盖 reload + recreate 且不持久化 |
| **M8** tab-switch / session-load 分流 | 新增 `mode` 参数 | **以「存档 + 装载」实现** | 切 tab 走 save/restore，切会话走「存档旧 + 装默认」，语义上自然分流，无需再加分支参数 |
| **新增 N7** | 未识别 | 已修 | 全局 `__sessionTransitioning` 会在用户切走后继续挡新 tab 的 delta；`selectTab` 发现目标不是 transition 目标时释放 guard |

### 10.3 验证结果

| 项 | 命令 | 结果 |
|---|---|---|
| 前端类型 | `cd webview && ./node_modules/.bin/tsc --noEmit` | ✅ 通过 |
| 前端单测（全量） | `./node_modules/.bin/vitest run` | ✅ **160 文件 / 1412 用例通过** |
| 运行态快照单测 | `vitest run src/utils/streamingRuntime.test.ts` | ✅ 8/8 |
| Java 编译 | `./gradlew compileJava compileTestJava --offline` | ✅ BUILD SUCCESSFUL |
| Watchdog 阈值 | `./gradlew test --tests "*WebviewWatchdogTest*"` | ✅ 12/12 |
| HTML 主题注入 | `./gradlew test --tests "*HtmlLoaderTest*"` | ✅ 5/5 |
| 多会话身份回归 | `MultiSessionStreamIsolation* / PermissionDialogIdentity* / OpencodePermissionRegistryMultiSession* / OpenCodeListMessagesRouting* / HistoryLoadPipelineIntegration*` | 见 10.4 |

> 跑 Java 单测必须加 `-x instrumentTestCode -x instrumentCode`，并且**先 `compileTestJava` 再把 `build/classes/java/test` 同步到 `build/instrumented/instrumentTestCode`**。否则 test classpath 会用到陈旧影子类，表现为「新写的用例不出现 / 老断言莫名失败」。

### 10.4 未验证项（必须在 JCEF 实机确认）

1. **OSR 首帧**：fence 接通后 A/B 相位能否在真实 OnPaint 上推进；`surface_damage_applied` 是否到达 `WindowEventHandler`。
2. **切 tab 不串内容**：A 流式中切到流式中的 B，B 的文本不含 A 片段。
3. **从历史切走流式会话**：A 的后台 delta 应走 orphan 丢弃路径（有日志计数），不进入 B 的视图。
4. **切 tab 后的画面**：OSR 应显示新 tab 内容而非上一 tab 的残帧。
5. **初始化耗时**：冷启动时 EDT 上不应再出现秒级长任务；2 个 tab 的初始化总耗时应接近 1 个（HTML 缓存命中）。
6. **watchdog reload 后**：3 个 tab 的骨架恢复、活动会话可继续流式、后台 tab 点开能拉历史。
7. **IDE 重启**：行为应与改动前一致（仍不恢复 tab）。

### 10.5 回滚边界

- P0 三项（`main.tsx` / `global.d.ts` / `sessionTransition.ts`）互相独立，可单独回滚。
- M0 集中在 `streamingRuntime.ts` + `useSessionTabs` 的 4 个 helper，回滚时删掉 helper 调用即可，refs 本身不受影响。
- `isActiveSession` 语义变更是 N2 的核心，回滚前必须先回滚 `useSessionManagement` 的 rebind，否则会同时失去两层保护。
- Java 侧 `WebviewInitializer` 的 EDT offload 是最大改动；若怀疑引入初始化问题，可把 `createUIComponents` 恢复为同步版本，其余改动（缓存、线性注入、超时顺序）可保留。

---

## 11. 补充核查：模型 / 推理力度 / 文件上下文 / 附件 / 停止按钮的 tab 隔离

> 2026-10-18 追加。结论：**模型、推理力度、文件上下文、停止按钮已隔离；附件与 file tags 未隔离，已修复。**

### 11.1 逐项结论

| 项 | 存储位置 | 切 tab 行为（修复前） | 判定 |
|---|---|---|---|
| **模型** `selectedModel` | `SessionTabData.selectedModel` + `selectTab` 恢复 | 保存/恢复完整；`selectTab` 会 `resolveSessionModel` 后下发 `set_model` | ✅ 已隔离 |
| **推理力度** `reasoningEffort` | `SessionTabData.reasoningEffort` + `selectTab` 恢复 | 同上，下发 `set_reasoning_effort` | ✅ 已隔离 |
| **文件上下文** `contextInfo` / `activeFile` / `selectedLines` | `SessionTabData.contextInfo` | `activeFile` / `selectedLines` **派生自 `contextInfo`**（`ChatScreen.tsx:344-345`），随 `contextInfo` 一起恢复 | ✅ 已隔离 |
| **上传附件** `attachments` | 输入框内部（uncontrolled） | **`saveCurrentTabSnapshot` 写死 `attachments: []`** → 切走即丢 | ❌ **已修复** |
| **file tags**（`@path` 标签块） | 输入框 DOM | **`fileTags: []` 写死**；`setValue` 只恢复纯文本，标签降级为文字 | ❌ **已修复** |
| **停止按钮** | `useMessageSender` → `interrupt_session` | `targetSessionId = currentSessionIdRef.current`，切 tab 时该 ref 已更新 | ✅ 已隔离 |

### 11.2 修复内容

**附件与 file tags 的读写通道**

输入框是 uncontrolled 的，附件和标签都在组件内部，tab 切换器无法直接访问。因此扩展了 `ChatInputBoxHandle`：

```ts
getAttachments: () => Attachment[];
setAttachments: (attachments: Attachment[]) => void;
restoreFileTags: (tags: FileTagInfo[]) => void;
```

`restoreFileTags` 的必要性：`setValue` 用 `innerText` 写纯文本，`.file-tag` 元素丢失。恢复时必须先把 `displayPath → absolutePath` 写回 `pathMappingRef`，再 `renderFileTags()` 重新生成标签块——否则后续 `getFileTags()` 会返回空，发送时 @ 文件引用消失。

**改动点**

| 文件 | 改动 |
|---|---|
| `ChatInputBox/types.ts` | `ChatInputBoxHandle` 加 3 个方法 |
| `ChatInputBox/hooks/useFileTags.ts` | 新增 `restoreFileTags`（重注册 path mapping + 重渲染） |
| `ChatInputBox/hooks/useChatInputImperativeHandle.ts` | 接线 3 个方法 |
| `ChatInputBox/hooks/useChatInputSelectionController.ts` | 透传 3 个参数 |
| `ChatInputBox/ChatInputBox.tsx` | 传入 `attachments` / `setInternalAttachments` / `restoreFileTags` |
| `hooks/useSessionTabs.ts` | `saveCurrentTabSnapshot` 读取真实附件与标签；`selectTab` 按「文本 → 附件 → 标签」顺序恢复 |

**`useResetAttachmentsOnSessionChange` 的语义修正**

该 hook 原本在 `currentSessionId` 变化时清空附件。切 tab 也会改变 `currentSessionId`，于是「刚恢复的附件立刻被清掉」。改为按 tab 维度判断：

- **tab 变了** → 不清空（由 `selectTab` 的 draft 恢复接管）
- **同一 tab 内换了会话**（从历史列表打开另一个会话）→ 仍然清空（那份草稿属于上一个会话）

### 11.3 新增测试

| 用例 | 文件 |
|---|---|
| 切 tab 不清空附件；同 tab 换会话仍清空 | `ChatInputBox/hooks/useResetAttachmentsOnSessionChange.test.ts`（+2，共 6） |
| 附件与 file tags 跨 tab 往返完整恢复 | `hooks/useSessionTabsMultiSession.test.ts`（+1，共 15） |

### 11.4 仍需实机确认

1. 带附件切 tab 再切回，缩略图与文件信息完整（`restoreFileTags` 依赖 `renderFileTags` 的文本解析，需验证 `setValue` 写入的 `@path` 能被正确识别）。
2. 切 tab 时附件不会闪一下随即消失（`useResetAttachmentsOnSessionChange` 的 tab 判断依赖 `window.__sessionTabsStore.getActiveTabId()`，需确认 effect 执行时该值已更新）。
3. 多模型会话切换后，Java 侧 `set_model` 落在正确会话上。

---

## 12. 补充核查：build / plan 模式（`permissionMode`）的 tab 隔离

> 结论：**前端状态是隔离的，但宿主侧不隔离——切 tab 会改写上一个会话的配置。已修复。**

### 12.1 前端部分：本来就是隔离的

| 环节 | 实现 | 判定 |
|---|---|---|
| 存储 | `SessionTabData.permissionMode` | ✅ |
| 切走保存 | `saveCurrentTabSnapshot` 写 `currentActive.permissionMode` | ✅ |
| 切回恢复 | `selectTab` → `setPermissionMode(targetTab.permissionMode)` | ✅ |
| 流式期间同步 | 同步 effect 把 `permissionMode` 写回活动 tab | ✅ |
| 用户主动切换 | `handleModeSelect` 已带 `{sessionId, mode}` | ✅ |

`PermissionMode` 是运行时从 opencode primary agents 派生的字符串（build / plan 等），没有硬编码枚举，所以「多一个模式」不会漏改。

### 12.2 宿主侧：四个真实缺陷

问题出在**桥调用顺序**上。`set_mode` / `set_model` / `set_reasoning_effort` 的**传统线格式不带 session 身份**，宿主一律作用于「窗口当前会话」：

```java
// PermissionModeHandler.handleSetMode
if (targetSession == null) {
    targetSession = context.getSession();   // ← 没有 sessionId 就打到当前会话
}
```

```java
// ModelProviderHandler.handleSetModel
String model = content;                    // ← 只解析 model，sessionId 根本没读
context.setCurrentModel(model);
context.getSession().setModel(model);       // ← 当前会话
```

| # | 缺陷 | 后果 |
|---|---|---|
| **M1** | `selectTab` 先发 `set_mode` / `set_model`，**最后**才发 `switch_session` | 切 A→B 时把 **A** 的 build/plan 模式和模型改成 B 的 |
| **M2** | `openSessionInTab`（从历史列表打开）**完全没有** `switch_session` | 模式和模型全部落在旧会话上，新打开的会话拿到的是旧配置 |
| **M3** | `closeTab` 关闭活动 tab 后切到相邻 tab，**没有** `switch_session` | 下一轮对话发到刚刚关闭的会话上 |
| **M4** | `set_model` 的 `modelChanged` 分支无条件清空上下文用量 | 在两个模型不同的 tab 间来回切，`usage` 环每次归零 |

M4 的机制：`selectTab` 之后前端虽然 `setUsagePercentage(targetTab.usagePercentage)` 恢复了显示，但宿主 `usagePushService.clearUsageDisplay()` 是**异步推送**，后到就覆盖掉。

### 12.3 修复

**前端（`useSessionTabs.ts`）**

新增两个 helper：

```ts
// 把宿主指向某个会话，必须在任何 per-session 桥调用之前
syncHostSessionTo(sessionId)

// 带会话作用域的 mode 下发（新格式，宿主已支持）
syncPermissionModeTo(sessionId, mode)
```

接线到三个切换点，均为「先切会话，后发配置」：

| 位置 | 改动 |
|---|---|
| `selectTab` | `switch_session` 从末尾提前到 `setActiveTabId` 之后；`set_mode` 改为 `syncPermissionModeTo`；删除重复的 `set_model` / `set_reasoning_effort` 显式发送（App 层 setter 已经发过） |
| `openSessionInTab` | 补 `syncHostSessionTo(sessionId)` |
| `closeTab` 的 nextActive 分支 | 补 `syncHostSessionTo(nextActive.sessionId)` + `syncPermissionModeTo` |

**模型恢复加 `silent` 标记（解决 M4）**

切 tab 恢复模型不是「用户换了模型」，不应触发用量清理：

```ts
setSelectedModel?.(resolvedModel, { silent: true });
// → sendBridgeEvent('set_model', JSON.stringify({ model, silent: true }))
```

宿主侧：

```java
boolean silent = false;   // 从 payload 解析
context.setCurrentModel(model);
if (context.getSession() != null) {
    context.getSession().setModel(model);
    if (modelChanged && !silent) { cleanup... }
}
if (modelChanged && !silent) { usagePushService.clearUsageDisplay(); }
if (!silent) { OpencodeNotifier.setModel(...); }
```

`context.setCurrentModel` 仍然无条件执行——它是窗口级「最后一次设置的模型」，后续 `getCurrentModel()` 依赖它保持一致。

### 12.4 新增测试

| 用例 | 断言 |
|---|---|
| `switches the host session before replaying build/plan mode` | `switch_session` 的调用下标 < `set_mode`；且 `set_mode` payload 含 `"sessionId":"ses_a"` |
| `moves the host session when closing the active tab` | 关闭 tab-2 后发出 `switch_session` 指向 tab-1 的 session，且 `activeTabId` 回到 tab-1 |

两条在修复前都会失败：前者下标顺序颠倒且 payload 无 sessionId，后者根本不存在 `switch_session` 调用。

### 12.5 仍需实机确认

1. **A(build) / B(plan) 交替切 tab**：切回 A 后 A 仍是 build，B 仍是 plan（宿主两个会话对象各自保留）。
2. **关闭活动 tab**：紧接着发一条消息，应落在相邻 tab 的会话上，而不是已关闭的那个。
3. **上下文用量环**：A / B 模型不同，来回切 tab，用量不应归零。
4. **从历史列表打开新会话**：打开后立即切换 build/plan，只影响新会话。
