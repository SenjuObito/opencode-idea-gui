# Daemon 架构改造：从每次请求启动进程到常驻守护进程

## 一、背景与动机

### 问题描述

在原有架构中，每次用户发送消息时，插件都会 **启动一个全新的 Node.js 进程**，经历以下完整链路：

```
用户输入 → 构建命令行参数 → spawn Node.js 进程 → 加载 ES Module → 初始化 Claude SDK
→ 建立 API 连接 → 发送请求 → 等待响应 → 流式输出 → 进程退出
```

其中 SDK 加载 + 初始化环节耗时约 **5-10 秒**，导致用户感知延迟高达 10+ 秒。

### 参考对象

采用 **持久化进程 + 长连接** 架构，后续请求延迟可降至毫秒级。核心思路：

- Claude SDK 只加载一次，进程常驻内存
- 通过 stdin/stdout 的 NDJSON 协议复用同一进程
- 会话状态持久化，支持跨请求复用

### 性能对比

| 指标 | 旧架构（Per-Process） | 新架构（Daemon） |
|------|----------------------|-----------------|
| 首次请求延迟 | 10-15s | 3-5s（含 SDK 预加载） |
| 后续请求延迟 | 10-15s（每次重启） | < 1s（进程已热） |
| SDK 初始化次数 | 每次请求 | 仅一次 |
| 进程开销 | 高（频繁创建/销毁） | 低（单进程常驻） |
| 会话状态 | 无（每次丢失） | 持久化（跨请求保留） |

---

## 二、架构改造总览

### 新增文件

| 文件 | 语言 | 职责 |
|------|------|------|
| `ai-bridge/daemon.js` | JavaScript | Node.js 守护进程入口，NDJSON 协议处理 |
| `ai-bridge/services/claude/persistent-query-service.js` | JavaScript | 持久化运行时管理，SDK query 对象复用 |
| `src/.../provider/common/DaemonBridge.java` | Java | Java 侧守护进程管理，进程启动/通信/心跳 |

### 修改文件

| 文件 | 变更描述 |
|------|---------|
| `ai-bridge/services/claude/message-service.js` | 新增 `registerActiveQueryResult()` 供 daemon 模式注册活跃查询 |
| `src/.../provider/claude/ClaudeSDKBridge.java` | 集成 DaemonBridge，优先走 daemon 模式，失败回退 per-process |
| `src/.../session/SessionLifecycleManager.java` | 新建会话时触发 daemon 预热 |
| `src/.../ui/WebviewInitializer.java` | 插件启动时后台预热 daemon |
| `src/.../ClaudeSession.java` | 首轮对话获取 sessionId 后补充预热匿名 runtime |

### 变更规模

- **8 个文件**，新增 **2231 行**，删除 **4 行**

---

## 三、新架构通信协议

### 整体流程

```
┌─────────────────────────────────────┐
│     IntelliJ Plugin (Java)          │
│                                     │
│  ClaudeSDKBridge                    │
│    └─ DaemonBridge                  │
│        ├─ stdin (写入 NDJSON 请求)  │
│        └─ stdout (读取 NDJSON 响应) │
└───────────┬─────────────────────────┘
            │ stdin/stdout (NDJSON)
            ▼
┌─────────────────────────────────────┐
│    daemon.js (Node.js 常驻进程)     │
│                                     │
│  ├─ SDK 预加载 (启动时一次性)       │
│  ├─ stdout 拦截 (请求 ID 标记)      │
│  ├─ process.exit 拦截 (防止退出)    │
│  └─ 请求队列 (串行化命令请求)       │
│        │                            │
│        ▼                            │
│  persistent-query-service.js        │
│    ├─ Runtime 池 (按 session 复用)  │
│    ├─ AsyncStream 输入流            │
│    └─ SDK query() 迭代器            │
└─────────────────────────────────────┘
```

### NDJSON 协议格式

**请求（Java → Node.js，写入 stdin）**：

```json
{"id":"1","method":"claude.send","params":{"message":"Hello","sessionId":"...","cwd":"..."}}
{"id":"2","method":"heartbeat"}
{"id":"3","method":"claude.preconnect","params":{"cwd":"..."}}
{"id":"4","method":"shutdown"}
```

**响应（Node.js → Java，读取 stdout）**：

```json
{"type":"daemon","event":"ready","pid":12345}
{"id":"1","line":"[STREAM_START]"}
{"id":"1","line":"[CONTENT_DELTA] \"Hello world\""}
{"id":"1","line":"[SESSION_ID] abc-123"}
{"id":"1","done":true,"success":true}
{"id":"2","type":"heartbeat","ts":1234567890}
```

---

## 四、核心组件详解

### 4.1 daemon.js — 守护进程入口

**核心职责**：

1. **SDK 预加载**：启动时调用 `loadClaudeSdk()` 一次性加载 SDK
2. **stdout/stderr 拦截**：覆写 `process.stdout.write` 和 `console.log`，为每一行输出添加请求 ID 信封
3. **process.exit 拦截**：捕获 SDK 内部的 `process.exit()` 调用，将其转换为 throw 而非真正退出
4. **请求队列**：命令请求串行化执行（因为共享 `activeRequestId`），心跳/状态查询可并发
5. **环境变量隔离**：每个请求可携带 `env` 参数，请求完成后恢复原始环境

**关键设计**：

```
stdout 拦截逻辑：
  如果有 activeRequestId → 输出包装为 {"id":"X","line":"原始行"}
  如果无 activeRequestId 且是 JSON → 直接透传（daemon 生命周期事件）
  否则 → 包装为 {"type":"daemon","event":"log","message":"..."}
```

### 4.2 persistent-query-service.js — 持久化运行时

**核心职责**：

1. **Runtime 池管理**：按 sessionId 或 runtimeSignature 复用 SDK query 对象
2. **SDK query 复用**：使用 `AsyncStream` 作为输入流，允许多轮对话复用同一个 query 迭代器
3. **动态参数调整**：运行时切换 model、permissionMode、maxThinkingTokens
4. **匿名 runtime 回收**：空闲超过 10 分钟的匿名 runtime 自动清理

**Runtime 生命周期**：

```
创建 Runtime
  ├─ 初始化 AsyncStream (输入流)
  ├─ 调用 sdk.query({ prompt: inputStream, options })
  └─ 返回 query 迭代器
      │
      ▼
执行单轮对话 (executeTurn)
  ├─ inputStream.enqueue(userMessage)
  ├─ 循环 runtime.query.next()
  │   ├─ stream_event → 流式增量输出
  │   ├─ assistant → 文本/thinking 内容
  │   ├─ system (session_id) → 注册 session
  │   └─ result → 轮次结束
  └─ 输出 [MESSAGE_END] + 结果 JSON
      │
      ▼
Runtime 保持活跃，等待下一轮
  ├─ 相同 session → 直接复用
  ├─ 不同 signature → 销毁重建
  └─ 空闲超时 → 自动回收
```

**Runtime 路由逻辑**：

```
请求到来 →
  有 sessionId? → runtimesBySessionId.get(sessionId)
  无 sessionId? → anonymousRuntimesBySignature.get(signature)
  找到 runtime 且 signature 匹配? → 复用
  否则 → 创建新 runtime
```

### 4.3 DaemonBridge.java — Java 侧进程管理

**核心职责**：

1. **进程启动**：启动 `node daemon.js`，等待 `ready` 事件
2. **NDJSON 通信**：通过 stdin 写入请求，从 stdout 读取响应
3. **请求路由**：按 request ID 将响应分派到对应的 `RequestHandler`
4. **心跳检测**：每 15 秒发送心跳，超过 45 秒无响应视为死亡
5. **自动重启**：进程崩溃后自动重试，最多 3 次（30 秒窗口内）

**线程模型**：

```
主线程 (sendCommand)
  └─ 写入 stdin (同步锁)

DaemonBridge-Reader 线程
  └─ 持续读取 stdout
      ├─ daemon 事件 → handleDaemonEvent()
      ├─ heartbeat 响应 → 更新时间戳
      └─ 请求响应 → pendingRequests.get(id).callback

DaemonBridge-Heartbeat 线程
  └─ 每 15s 发送心跳
      └─ 超过 45s 无响应 → handleDaemonDeath()

DaemonBridge-Stderr 线程
  └─ 读取 stderr 用于调试日志
```

### 4.4 ClaudeSDKBridge.java — 集成层

**改造策略：优先 Daemon，回退 Per-Process**

```java
public CompletableFuture<SDKResult> sendMessage(...) {
    // 1. 尝试 daemon 模式
    DaemonBridge db = getDaemonBridge();
    if (db != null) {
        return sendMessageViaDaemon(db, ...);
    }

    // 2. 回退：per-process 模式（原有逻辑）
    LOG.info("Using per-process mode (daemon not available)");
    return CompletableFuture.supplyAsync(() -> { ... });
}
```

**Daemon 延迟初始化**：

```java
private DaemonBridge getDaemonBridge() {
    // 双重检查锁
    if (daemonBridge != null && daemonBridge.isAlive()) return daemonBridge;
    if (System.currentTimeMillis() < daemonRetryAfter) return null;  // 冷却期
    synchronized (daemonLock) {
        // ... 启动 daemon
    }
}
```

---

## 五、预热策略

为了让用户首次提问也能享受低延迟，实现了三阶段预热：

### 阶段 1：插件启动时

`WebviewInitializer.java`:
```java
// Webview 初始化完成后，后台预热 daemon
claudeSDKBridge.prewarmDaemonAsync(host.getProject().getBasePath());
```

> 启动 daemon 进程 + 预加载 SDK + 创建匿名 runtime

### 阶段 2：新建会话时

`SessionLifecycleManager.java`:
```java
// 新会话创建后，补充预热
host.getClaudeSDKBridge().prewarmDaemonAsync(workingDirectory);
```

> 确保 daemon 就绪 + 按工作目录预创建 runtime

### 阶段 3：首轮对话完成后

`ClaudeSession.java`:
```java
.whenComplete((unused, throwable) -> {
    if (previousSessionId 为空 && 当前 sessionId 非空) {
        // 首轮对话获取到 sessionId，匿名 runtime 已被"命名"
        // 补充创建新的匿名 runtime，供下一次新对话使用
        claudeSDKBridge.prewarmDaemonAsync(state.getCwd());
    }
});
```

> 首轮对话消耗了预热的匿名 runtime 后，立即补充一个新的

### 预热时序图

```
插件启动
  │
  ├─ 阶段1: prewarmDaemonAsync() ──→ daemon启动 + SDK加载 + 匿名runtime创建
  │                                        ↓
  │                                  [daemon就绪，匿名runtime等待]
  │
  ├─ 用户打开新会话
  │   └─ 阶段2: prewarmDaemonAsync() ──→ 确认daemon存活，如需补充runtime
  │
  ├─ 用户发送第一条消息
  │   └─ acquireRuntime() 命中匿名runtime → 延迟 < 1s
  │       ↓
  │   对话完成，匿名runtime绑定到sessionId
  │       ↓
  │   └─ 阶段3: prewarmDaemonAsync() ──→ 补充新的匿名runtime
  │
  └─ 用户发送第二条消息
      └─ acquireRuntime() 命中session runtime → 延迟 < 1s
```

---

## 六、容错设计

### 6.1 Daemon 故障自愈

```
daemon 进程崩溃
  ↓
Reader 线程检测到 stdout 关闭 → handleDaemonDeath()
  ↓
1. 所有 pending 请求标记失败
2. 通知 lifecycleListener
3. 检查重启窗口：
   - 运行 > 30s 后崩溃 → 重置计数器 → 允许重启
   - 30s 内多次崩溃 → 递增计数器
   - 超过 3 次 → 放弃重启，回退 per-process 模式
```

### 6.2 process.exit 拦截

SDK 内部可能调用 `process.exit()`，daemon 通过拦截防止进程意外终止：

```javascript
process.exit = function (code) {
    if (isDaemonMode) {
        // 发送完成信号给当前请求
        if (activeRequestId) {
            writeRawLine({ id: activeRequestId, done: true, success: code === 0 });
        }
        // 抛异常回溯调用栈，而非真正退出
        throw new Error(`process.exit(${code}) intercepted`);
    }
    _originalExit(code);
};
```

### 6.3 环境变量隔离

每个请求可携带独立的 `env` 参数，执行完毕后恢复：

```javascript
// 请求开始：保存并覆盖
const savedEnv = {};
for (const [key, value] of Object.entries(params.env)) {
    savedEnv[key] = process.env[key];  // 保存原值
    process.env[key] = String(value);   // 覆盖
}

// 请求结束：恢复
for (const [key, originalValue] of Object.entries(savedEnv)) {
    if (originalValue === undefined) delete process.env[key];
    else process.env[key] = originalValue;
}
```

### 6.4 Runtime 生命周期管理

```
Runtime 状态机：
  创建(create) → 活跃(active) → 空闲(idle) → 回收(disposed)
                      ↑              │
                      └──── 新请求 ──┘

异常处理：
  query.next() 抛出 → runtimeTerminated = true → disposeRuntime() → 创建新 runtime 重试
  stream 意外结束 → 同上
  signature 不匹配 → dispose 旧 runtime → 创建新 runtime
```

---

## 七、附录

### Per-Process 模式的优化

除了 daemon 架构改造，还对原有 per-process 模式做了小优化：

- **移除 500ms 睡眠**：原来启动进程后会 `Thread.sleep(500)` 检查早期退出，现在改为即时检查 `process.isAlive()`
- **异常处理简化**：早期退出检查的异常从 `InterruptedException` 改为通用 `Exception`

### Permission Hook

persistent-query-service.js 实现了完整的权限控制：

- `bypassPermissions`：全部自动批准
- `acceptEdits`：仅自动批准文件编辑类工具（Write/Edit/MultiEdit 等）
- `plan` 模式：限制为只读工具 + ExitPlanMode 审批流
- `default`：每个工具调用走 `canUseTool()` 检查
