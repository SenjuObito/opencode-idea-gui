# 额度用尽提示与错误通知状态栏（StatusNoticeBar）设计文档

本文档说明 **「模型额度已用尽」** 这类**终结性错误**在双端插件（OpenCode IDEA GUI / OpenCode VS Code Plugin）中的展示方案：从**消息流内的常驻诊断卡片**，改为**输入框上方的可关闭状态栏**，并携带**「升级订阅」动作按钮**。

> 落版本：2026-10-16。相关代码位于 `webview/src`（两端共用同一份源码）。

---

## 1. 背景：为什么原来的提示「一直在下面」

改造前，额度错误由两条链路分别呈现，各有问题：

| 组件 | 位置 | 问题 |
|---|---|---|
| `ErrorDiagnosticCard` | 出错消息**下方** | `opencodeQuotaExceeded` 的 `solutions` 是空数组，卡片里只有标题 + 两段长 prose，**没有任何可操作内容**，却永久占据消息流 |
| `RetryStatusBar` | 输入框上方 | 仅覆盖**回合运行中**的退避重试；回合结束后即消失，额度错误恰好产生在回合结束那一刻 |

### 1.1 关键事实：错误消息是**内存态**，不落盘

这是最初判断错误的点，必须记清楚，否则实现会防错方向：

- 错误消息是 `Message.Type.ERROR` 的**会话状态消息**（`OpenCodeMessageHandler.onError` → `state.addMessage(...)`），写在内存态的 `SessionState.messages` 里，**没有任何落盘代码**；
- 唯一会移除它的是 `SessionMessageOrchestrator.loadFromServer()` 里的 `state.clearMessages()`；
- 历史重建路径**结构上产不出 ERROR**：`MessageParser.parseServerMessage` 只 `return` USER / ASSISTANT，其余一律 `return null`；`OpenCodeMessageConverter.addProperty("type", ...)` 也只出现 `user` / `assistant`。

**结论**：不存在「重开历史会话又长出来」这条路径。卡片的存在期 = 那个 `OpencodeSession` 对象在内存里的生命期。真正的重复触发风险是**同一份内存态快照被反复喂回**：

1. `updateMessages` 全量快照重建（每次都是新对象身份，`MessageItem` 被反复重挂）；
2. **切标签页回切** —— tab 级缓存把含 ERROR 的整份旧快照恢复；
3. webview / JCEF 重载后收到重放快照。

---

## 2. 方案：一个插槽，两种互斥通知

`StatusNoticeBar`（原 `RetryStatusBar` 泛化）占据消息列表与状态面板之间的插槽，承载两种**时序上不重叠**的通知：

```
                    ┌──────────────────────────────────────────────┐
   回合运行中  →    │ ⟳   服务端重试中               [查看详情] [✕] │   琥珀 · 图标旋转
                    │     第 3 次重试 · 42 秒后重试                 │   回合结束自动消失
                    └──────────────────────────────────────────────┘

                    ┌──────────────────────────────────────────────┐
   回合失败后  →    │ ⚠   模型额度已用尽          [升级订阅]   [✕]  │   红 · 图标静止
                    │     请切换到其他模型，或等待额度重置后重试。  │   常驻，直到点 ✕
                    └──────────────────────────────────────────────┘
```

### 2.1 状态机与规则

```
A. 空闲（不渲染）
   │ 回合运行中                        ▲ 点 ✕ / 新回合开始
   ▼                                  │
B. 重试条（琥珀 · 旋转）  ──回合失败且命中 bar 型 pattern──▶  C. 额度条常驻（红 · 可关闭）
```

| # | 规则 | 说明 |
|---|---|---|
| R1 | **重试条优先** | 回合运行中且有 retry payload → 显示琥珀条。同一时刻只渲染一条 |
| R2 | **额度条常驻** | 回合不在运行时，若消息列表里**最后一条** `bar` 型错误未被关闭 → 显示红条，**无超时自动消失** |
| R3 | **点 ✕ 关闭** | 该通知的 key 写入模块级 `Set` → 本会话内不再出现（含快照重放、切标签页回切、组件重挂载） |
| R4 | **新回合开始自动退休** | `turnActive` 由 `false → true` 时，把当前通知的 key 标记为已关闭 |
| R5 | 会话隔离 | key 含 `sessionId` |
| R6 | 历史恢复期间不显示 | `sessionLoading === true` 时整轮跳过 |
| R7 | 只取**最后一条** | 从消息列表末尾往前找第一条 `bar` 型错误；若它已被关闭 → **整体不渲染**（不回退到更老的错误） |
| R8 | **动作按钮按需渲染** | 拿不到链接时不渲染「升级订阅」，不做假按钮 |

**为什么必须有 R4**：若字面执行「不关闭就一直显示」，会出现「额度用尽 → 换模型 → 下一条消息成功 → 红条还挂着」这种**过时且错误**的提示。R4 用「用户已采取行动」作为退休信号。时序上安全——错误消息总在回合**结束时**产生，所以 `false→true` 沿永远发生在错误消息出现**之前**。

### 2.2 去重键

```ts
const dismissedNoticeKeys = new Set<string>();   // 模块级，跨重挂载存活

export const noticeKeyFor = (sessionId, message, text) =>
  `${sessionId ?? ''}::${Number(message.timestamp) || 0}::${hashText(text.slice(0, 200))}`;
```

- `timestamp` 由 host 建消息时固定（`new Message(...)`），**全量快照重建不会变** → 同一条错误天然幂等；
- key 带 `timestamp` 而非只带文本哈希 → 一分钟内两条相同文案仍会各展示一次；
- **不需要**「新鲜度闸门」：额度条是常驻的，「旧」恰恰是它该显示的状态，关闭与否才是唯一开关。

---

## 3. 数据驱动的展示方式：`presentation`

判断「该用卡片还是状态栏」的依据写在**数据**里，而不是靠「有没有 solutions」去猜——否则以后新增 pattern 会静默改变展示方式。

`webview/src/data/errorPatterns.json`：

```json
{
  "code": "opencodeQuotaExceeded",
  "presentation": "bar",
  "actionLink": "https://opencode.ai/go",
  "match": { "regex": "free usage exceeded|usage limit reached|..." },
  "solutions": []
}
```

`errorMatcher.ts` 在**编译期**归一化：缺省即 `card`。`MessageItem` 对 `bar` 型不渲染 `ErrorDiagnosticCard`：

```tsx
{errorDiagnosticPattern && errorDiagnosticPattern.presentation !== 'bar' && (
  <ErrorDiagnosticCard t={t} pattern={errorDiagnosticPattern} />
)}
```

原始错误文本（`OpenCode 请求等待超时`。**继续保留在会话记录里** → 导出 Markdown、Fork、历史回看不丢信息。

---

## 4. 最容易踩的坑：「升级订阅」的链接从哪来

链接来自 opencode server 运行时的 `session.status(action)` → `action.link`：

- `free_tier_limit` → `https://opencode.ai/go`（订阅页）
- `account_rate_limit` → workspace 配额页

`@opencode-ai/sdk` 只是 HTTP 客户端，**代码里查不到链接常量**，只能靠运行时下发。

### 4.1 时序陷阱

```
用户发送 ─► 回合运行 ─► 服务端退回重试 ─► 本地超时 ─► 回合失败
   │            │              │                            │
   │            │       [STATUS] payload                    │
   │            │       = { message, attempt, next,         │
   │            │           action:{ reason, title,         │
   │            │                    label, link } }        │
   │            │              │                            │
   │            │       ① 渲染琥珀条                         │
   │            │       ② 把 action 存进 pendingRetryAction ─┐
   ▼            ▼                                          ▼▼
 琥珀条：服务端重试中 · 第3次 · 42s后重试      红条：模型额度已用尽 [升级订阅] [✕]
                                                    ▲
                                ③ 红条出现时按 notice key 钉住链接
```

**链接在回合运行中下发，而红条在回合结束后才出现。** 现有代码在回合结束时会把 retry payload 清空（`if (!turnActive) setRetryStatus(null)`），所以直接复用它必然拿到 `null`。

### 4.2 两段式缓冲

```ts
// ① 回合运行中收到的"候选动作"，不随回合结束清空
let pendingRetryAction: (NoticeAction & { sessionID?: string }) | null = null;

// ② 每条通知各自钉住一份链接，彻底消除串味
const noticeActionLinks = new Map<string, NoticeAction>();
```

- `setRetryStatus(payload)` 时若 `action.link` 存在 → 记进 `pendingRetryAction`（连同 `sessionID`）；
- **不复用** `latestRetryStatus`（它会在回合结束被清空）；
- R4 的「新回合开始」同时把 `pendingRetryAction` 置空 → 只消费**本回合**产生的动作，不会把上一回合的链接错配给这一回合的错误；
- 红条出现时，若 `pendingRetryAction.sessionID === currentSessionId` 且该 notice key 还没钉过 → `noticeActionLinks.set(key, ...)`（幂等，没钉成功下次再试）。

### 4.3 解析顺序与文案

1. **服务端 `action.link`**（权威值）
2. **pattern 的 `actionLink` 兜底**（保证按钮不会凭空消失，改起来只是改一行 JSON）
3. 都没有 → **不渲染按钮**（R8）

按钮文案固定走 i18n（`errorDiagnostic.<code>.upgrade` → 「升级订阅」/「Upgrade」），**不直接吃服务端的 `action.label`**——服务端给的是英文 `subscribe`，塞进中文 UI 很难看；服务端的 `title` / `label` 放进 `title` 属性做 tooltip。

点击 → `sendToJava('open_browser', link)`（webview 拦截了 `window.open` / 外链 `<a>`，必须经 host）。

---

## 5. 视觉规格

| 项 | `--warning`（重试中） | `--error`（额度用尽） |
|---|---|---|
| 图标 | `codicon-sync`，`--color-warning`，**旋转** | `codicon-warning`，`--color-error`，**静止** |
| 左边框 | `3px` `--color-warning` | `3px` `--color-error` |
| 其余三边 | `1px` `--color-warning` | `1px` `--color-notice-error-border`（中性偏红） |
| 背景 | `--bg-tertiary` | `--color-notice-error-bg`（极淡红） |
| 标题 | `--text-secondary` / 600 | `--text-primary` / 600 |
| 说明 | `--text-primary` | `--text-secondary` |
| meta 行 | 有（第 N 次 / N 秒后重试） | 无 |
| 动作按钮 | 「查看详情」（服务端 label） | 「升级订阅」（i18n 固定文案） |
| 常驻 | 否 | **是** |

新增 tokens（`styles/less/variables.less`，暗/亮各一套）：

```less
/* dark */
--color-notice-error-bg: rgba(244, 67, 54, 0.07);
--color-notice-error-border: rgba(244, 67, 54, 0.35);
/* light */
--color-notice-error-bg: rgba(232, 17, 35, 0.05);
--color-notice-error-border: #f0a6ac;
```

**为什么不用整条红框**：`1px solid var(--color-error)` 的通栏红框在暗色主题下非常刺眼，而这条通知会**常驻**。左粗边 + 极淡底色足以表达「失败」，长时间盯着不难受。

**为什么动作按钮用蓝色**：红条里再放一个红按钮会糊成一团；蓝色（`--accent-primary`）既跳出来又不与错误色争抢，且与现有「查看详情」按钮同款。

**a11y**：重试条 `role="status"`；额度条 `role="alert" aria-live="polite"`；关闭按钮 `aria-label` 沿用 `retryStatus.dismiss`。`prefers-reduced-motion` 下关闭旋转动画。

---

## 6. 顺带清理：两个永远匹配不到的死 pattern

`errorPatterns.json` 是一份**纯数据**，代码删掉 provider 不会让它报错，所以旧条目会一直躺着。

| pattern | 证据 | 处置 |
|---|---|---|
| `codexThreadResume`（regex `thread/resume`） | 插件已 opencode-only（`aiFeatureConfig.ts` 明写 claude / codex / grok / kimi / pi 已移除）；`src/main/java` 下无 `*codex*`；bridge 依赖只剩 `@opencode-ai/sdk`；`thread/resume` 在整条链路（含 `node_modules`）**零命中** | **删除** |
| `sdkNativeBinaryMissing`（`Native CLI binary for ... not found` + `keywordsAll: ["claude-agent-sdk"]`） | `@anthropic-ai/claude-agent-sdk` 已不在依赖里 | **删除** |
| `spawnEbusy`（`spawn EBUSY`） | Node `child_process.spawn` 的通用系统错误，拉起 opencode 时仍可能触发 | 保留 |

同步删除 10 个语言包（en / zh / zh-TW / ja / ko / es / fr / hi / pt-BR / ru）中对应的 `errorDiagnostic.codexThreadResume` 与 `errorDiagnostic.sdkNativeBinaryMissing`（后者含 `solutions.switchRegistry` 子树）。

> 注意：`opencodeQuotaExceeded` 的文案**只存在于 `en.json` / `zh.json`**，其余 8 个语言包通过 i18next 的 `fallbackLng: 'en'` 回退到英文——这是既有行为，本次沿用。

---

## 7. 文件清单

| 文件 | 改动 |
|---|---|
| `webview/src/data/errorPatterns.json` | quota 加 `presentation: "bar"` + `actionLink`；删两个死 pattern |
| `webview/src/utils/errorMatcher.ts` | `DiagnosticPattern` 加 `presentation`（编译期归一化，缺省 `card`）/ `actionLink` |
| `webview/src/utils/errorMatcher.test.ts` | 断言 presentation 透传 / 缺省 / actionLink |
| `webview/src/components/StatusNoticeBar.tsx` | 由 `RetryStatusBar.tsx` 泛化（改名）+ 额度条 + 动作按钮 + 链接缓冲 |
| `webview/src/components/StatusNoticeBar.less` | `--error` 变体、`.status-notice-*`、静止图标、按钮组 |
| `webview/src/components/StatusNoticeBar.test.tsx` | 重试条回归 9 例 + 额度条 11 例 + 动作按钮 5 例 |
| `webview/src/components/MessageItem/MessageItem.tsx` | `bar` 型不渲染 `ErrorDiagnosticCard` |
| `webview/src/components/ChatScreen.tsx` | import 改名 + 传 `messages` / `getMessageText` |
| `webview/src/main.tsx` | import 改名（`window.updateRetryStatus` 契约名**不变**） |
| `webview/src/styles/less/variables.less` | 新增 `--color-notice-error-*`（暗 / 亮） |
| `webview/src/i18n/locales/*.json`（10 个） | quota 改 `notice` / `upgrade`；删两个死 pattern 文案 |

**零改动**：Java 宿主侧、`opencode-buddy-core`（bridge）、`Toast.tsx`、`UIStateContext.tsx`、`ErrorDiagnosticCard.tsx`（`spawnEbusy` 仍在用）。

**跨仓库同步**：两端共用同一份 `webview/src`。差异仅在推送通道——VS Code 侧 `window.updateRetryStatus` 是**单参签名**（单窗口构建），IDEA 侧是 `(sessionId, json)`。同步时只搬上述共用文件，**不要**覆盖各自的 host / 关键宿主耦合文件（两端 `ChatScreen.tsx` / `main.tsx` / `MessageItem.tsx` 都有各自的既有分歧，只做定点补丁）。

> 同步注意行尾：IDEA 仓库有 `.gitattributes`（`* text=auto eol=lf`）→ 工作区多为 LF；VS Code 仓库工作区为 CRLF。跨仓库搬运文件时需按目标仓库的行尾重写，否则 `git diff` 会整文件翻红。

---

## 8. 测试

`webview/src/components/StatusNoticeBar.test.tsx`（vitest + @testing-library/react）：

**A. 重试条回归**：回合中显示原因 + action 标题；点 action 走 `open_browser`；回合结束隐藏；别的会话 payload 不泄漏；点 ✕ 清空 buffer；`onRetryStatusFromHost` 的 4 种入参形态。

**B. 额度条**：出现并含 title / notice 文案；同一快照多次重放仍常驻；点 ✕ 消失；卸载后带同一份消息重挂载（模拟切标签页回切）仍不显示；`timestamp` 不同的新错误重新出现；R4 新回合退休且回合结束后不复活；R1 重试条优先；`card` 型 pattern（`spawn EBUSY`）不进状态栏；provider-not-configured 不进状态栏；会话隔离；`sessionLoading` 期间不显示。

**C. 升级订阅按钮**：用服务端链接；回合结束清空 payload 后链接仍被钉住；无服务端链接时走 pattern 兜底；跨回合不串味（新回合起点清空候选 → 走兜底）；跨会话不串味。

**全量校验命令**：

```bash
export PATH="/c/Users/10049/.workbuddy/binaries/node/versions/22.22.2-3:$PATH"
cd webview && ./node_modules/.bin/tsc --noEmit && ./node_modules/.bin/vitest run
```

---

## 9. 运行时产物

IDEA 插件实际加载的 webview 是 `src/main/resources/html/opencode-chat.html`（已被 `.gitignore` 忽略），由 `webview` 构建产出：

```bash
cd webview && npm run build     # tsc && vite build + postbuild copy-dist（只同步本仓库的 resources/html）
```

改了 `webview/src` 后必须重新构建，否则插件里看到的还是旧 UI。
