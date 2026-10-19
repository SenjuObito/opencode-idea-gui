# 限流／额度信号（session.status:retry）的透传与呈现

## 问题现象

对话额度用尽后，插件发送消息会一直停在 loading，且**看不到任何具体原因**；
大约 3 分钟后才冒出一条泛化文案：

```
OpenCode 请求等待超时 (180s)，可能由于模型服务繁忙或额度耗尽
```

而服务端其实早就给出了确切原因（`Free usage exceeded, subscribe to Go` +
订阅链接），只是这条信号从未到达 UI。

## 信号链路（改动后）

```
opencode server
  session.status { type:"retry", attempt, message, action:{reason,title,message,label,link} }
        │  SSE
        ▼
opencode-buddy-core/services/opencode/opencode-daemon-service.js
  case 'session.status'  → emitJsonStringMarker('[STATUS]', {…结构化载荷…})
        │  stdout NDJSON
        ▼
宿主 MarkerParser
  [STATUS] → 仅当载荷是裸 JSON 对象时 → onMessage('retry_status', json)
        ▼
MessageHandler → CallbackHandler.notifyRetryStatus → SessionCallback.onRetryStatus
        ▼
SessionCallbackAdapter → callJavaScript('updateRetryStatus', json)
        ▼
webview components/RetryStatusBar.tsx → 常驻提示条（原因 + 次数 + 倒计时 + action 链接）
```

`action.link` 经 `sendToJava('open_browser', url)` 交给宿主用系统浏览器打开
（webview 会拦截 `window.open` 与外链 `<a>`）。

## 三个断点与对应修复

| # | 位置 | 断点 | 修复 |
|---|---|---|---|
| 1 | `OpenCodeMarkerParser`（Java）/ `MarkerParser.ts`（TS） | 有 20+ 个 marker 分支，**唯独没有 `[STATUS]`**，`session.status:retry` 走完所有 `startsWith` 被静默丢弃 | 补 `[STATUS]` 分支；只放行对象形态，丢弃 `preconnect ready …` 这类同 tag 噪音 |
| 2 | `opencode-daemon-service.js` 180s 兜底 | 计时器在 `await sdk.promptAsync()` **之后**才创建，promptAsync 自身挂住时完全无兜底；文案写死，丢弃服务端已给出的原因 | 计时器提前到发起请求之前；文案改为透传服务端原话 |
| 3 | 同上的 `!turn` 分支 | 超时删掉 turn 后，服务端迟到的 `session.error` 落进 `!turn` 分支，被**误投成 `[SESSION_COMPACT_RESULT]`**（宿主只打一行 log），真实原因二次丢失 | 新增 `[TURN_ERROR]`；宿主 `OpenCodeMessageHandler` 把它作为对超时文案的**修正**渲染（改写末尾 ERROR 行，不新增气泡） |

## 关键实现细节

### 只有 `ses_*` 能当 abort 目标

`OpencodeSession.interrupt()` 原本把 **channelId（随机 UUID）** 传给 abort，
daemon 又把它原样交给 `sdk.abort()`，opencode 直接拒绝：

```
Expected a string starting with "ses"
```

于是 Stop 只清了本地 loading，服务端那个 turn 还在重试 —— 「按了停止还是一直转」
的手感来源。现在传 `state.getSessionId()`；daemon 侧 `abortCurrentTurn()` 也加了
收口：非 `ses_` 目标降级为「中止所有活跃 turn」，任何调用方再传错都不会静默失败。

> 注：`opencode-vscode-plugin` 的 `interrupt()` 本来就传对了 session id，无需改动；
> 它受益的是 daemon 侧新加的那层防御。

### `[STATUS]` 的载荷形态

`emitJsonStringMarker(tag, text)` 的实现是 `` `${tag} ${JSON.stringify(text)}` ``，
所以**传对象**得到的是裸 JSON 对象行（`[STATUS] {...}`），而不是双重编码。宿主用
幂等的 `decodeJsonStringPayload` 解一层，再用「首尾是否 `{` `}`」判断形态。

### 重试提示条的显隐规则

- 只在 `loading || streamingActive` 时显示：重试提示只在回合进行中有意义；
- 回合结束即从模块级缓冲清空，避免下一个回合复活一条过期的「重试中」；
- `payload.sessionID` 与当前会话不一致时不显示（daemon 的 SSE 订阅按目录共享）。

## 测试

| 仓库 | 文件 | 覆盖 |
|---|---|---|
| opencode-idea-gui | `src/test/java/com/opencodebuddy/session/OpenCodeRetryStatusRoutingTest.java`（8 例） | `[STATUS]` 结构化路由、preconnect 噪音丢弃、`[TURN_ERROR]` 路由、retry 回调透传、迟到错误改写而非新增、`replaceLastErrorMessage` 边界 |
| opencode-idea-gui | `webview/src/components/RetryStatusBar.test.tsx`（9 例） | 渲染原因／action、链接走宿主 `open_browser`、回合结束后隐藏、跨会话不串台、dismiss；bridge 入参两种形态与异常载荷 |
| opencode-vscode-plugin | `src/test/markerParserRetryStatus.test.ts`（3 例） | 同 MarkerParser 三项 |
| opencode-vscode-plugin | `webview/src/components/RetryStatusBar.test.tsx`（9 例） | 同前端九项 |

## 同步注意

- `ai-bridge` 的改动只改 `opencode-buddy-core`，两个插件各自打包时会取同级目录
  （`build.gradle` 的 sibling 优先、`tools/prepare-core.mjs`），不要各自维护副本。
  改完记得重打 `dist/ai-bridge.zip`。
- 前端 `errorPatterns.json` 新增 `opencodeQuotaExceeded`，让额度错误在转录里渲染
  成诊断卡片（`errorDiagnostic.opencodeQuotaExceeded.*`）。
