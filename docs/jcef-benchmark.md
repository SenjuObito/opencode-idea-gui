# JCEF 多 tab 性能基准

目标：6 个 tab、3 路并发流、10k 条消息下达到
`full-refactor-architecture-plan.md` §12.1 的预算，并确认关闭 tab 后没有
actor / sink / webview 队列泄漏。

## 当前状态

| 部分 | 状态 | 说明 |
|---|---|---|
| 宿主调度（enqueue、合并、批次、队列上限） | 已自动化 | `BridgeSchedulerTest#sixTabsThreeStreamsTenThousandMessagesStayBounded` |
| 真实 JCEF 渲染、切 tab、reload、内存回落 | **待手工执行** | 需要 IDE 运行时，当前 CI 环境无法启动 JCEF |

自动化部分是性能上限的确定性证明：渲染器收到多少条脚本由宿主决定，
JCEF 只负责执行它们。

```powershell
.\gradlew.bat test -x buildWebview --tests "com.opencodebuddy.ui.toolwindow.BridgeSchedulerTest"
```

最近一次结果（Windows 11、JDK 21、i7 级别机器，仅作基线）：

```
[BridgeBenchmark] tabs=6 streams=3 streamChunks=10000 deltaScripts=1
discreteMessages=10000 scripts=314 bytes=3873552 elapsedMs=123
```

含义：

- 6 个 tab、3 路流共 10k 个 delta，被按 lane 合并成 **1 条脚本**；
- 另外 10k 条不可合并的消息，只产生 **314 条脚本**（每批最多 64 个事件），
  没有任何一条被丢弃；
- 宿主侧 20k 次入队 + 构建脚本耗时 123ms，没有长任务。

这不是 JCEF 帧率数据，不能替代下面的手工验收。

## 手工验收步骤

1. `.\gradlew.bat runIde`，在沙箱 IDE 中打开插件工具窗口。
2. 建立 6 个 tab；其中 3 个分别发起一个会产生长回复的请求，让 3 条流同时输出。
   另外 3 个 tab 保持空闲，用于观察后台 lane 是否被饿死。
3. 打开 JCEF DevTools（工具窗口右键 → Inspect，或 `Ctrl+Shift+Alt+I`），
   在 Performance 面板开始录制，持续 60 秒，期间：
   - 每 5 秒在 6 个 tab 之间切换一次（共约 12 次）；
   - 在 3 条流之间来回切换，确认切回已缓存的 tab 立即显示；
   - 在活动 tab 输入文字，确认输入不卡。
4. 停止录制，记录：
   - FPS / 掉帧数；
   - 主线程 > 50ms、> 200ms 的长任务；
   - JS 堆峰值，以及关闭 3 个 tab 后的回落；
   - 每个 tab 的 `onContentDelta` 调用次数（应 < 30 次/秒/tab）。
5. 在宿主日志中确认：
   - 没有 `Dropping webview event because lane ... is full`；
   - 没有持续触发 `snapshotRequired` 重同步；
   - 关闭 tab 后对应 lane 不再出现。
6. 执行 reload（开发者工具刷新或插件重载）3 次，确认：
   - tab 的稳定 `tabId` 和 active tab 不变；
   - 流式内容在 reload 后补齐，不串 tab；
   - 旧 epoch 的迟到事件被丢弃。
7. 需要 10k 消息维度时，使用上面的自动化结果；真实 JCEF 只补渲染侧数据。

## 记录表

| 指标 | 预算（§12.1） | 本次结果 |
|---|---|---|
| 活动 tab delta 端到端 P95 | < 150ms | 待填 |
| 输入响应 P99 | < 50ms | 待填 |
| 活动 tab 切回 P95（已缓存） | < 100ms | 待填 |
| 首次打开 tab（不含历史） | < 500ms | 待填 |
| 主线程长任务 | 无 > 200ms；P99 < 50ms | 待填 |
| 每 tab JS 调用频率 | < 30 次/秒 | 待填 |
| 总事件队列 | 每 tab < 256；全局 < 1024 | 待填 |
| 6 tabs / 3 并发流 | 不卡顿、无串会话 | 待填 |
| 关闭 tab 后内存 | 可观测回落，无泄漏 | 待填 |

基准不达标不得合并（§13.4）。
