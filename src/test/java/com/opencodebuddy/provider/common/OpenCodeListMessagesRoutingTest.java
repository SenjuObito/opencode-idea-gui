package com.opencodebuddy.provider.common;

import com.opencodebuddy.provider.opencode.OpenCodeSDKBridge;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 宿主侧 {@code opencode.listMessages} 的响应路由回归测试。
 *
 * <p>2026-10-17 的线上故障：daemon 正常播报 N 条 message entry，宿主却一条都没
 * 收集到，最终 {@code historyLoadComplete(sessionId, 0)} + {@code updateMessages(sessionId, [])}
 * —— 用户看到空白标签页，且**没有任何报错**。</p>
 *
 * <p>成因是 core 给共享查询的响应行盖了 session 戳（{@code requestContextRecord}
 * 对所有请求都取 {@code params.sessionId}），宿主 {@code deliverDataLine} 见
 * session 命中已注册的 sink 就只给 sink 并 return，收集器因此颗粒无收。
 * core 已修（只有回合命令才从 params 取身份）。</p>
 *
 * <p>这里守住两件事：</p>
 * <ol>
 *   <li>即使该会话已经注册了 sink，共享查询的响应行仍必须到达**请求自己的收集器**；</li>
 *   <li>万一响应行再次在路上丢失，宿主必须**明确失败**，而不是把「加载失败」伪装成
 *       「空会话」（播报了 N 条却收到 0 条 ⇒ 抛错）。</li>
 * </ol>
 */
public class OpenCodeListMessagesRoutingTest {

    private static final String SESSION_ID = "ses_route_test";
    private static final long AWAIT_SECONDS = 10;

    /**
     * 能往 stdout 写任意 NDJSON 行、并记录宿主写进来的请求 JSON 的假进程。
     */
    private static final class ScriptedProcess extends Process {
        private final PipedInputStream stdout = new PipedInputStream();
        private final PipedOutputStream stdoutWriter;
        private final ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        private final AtomicBoolean alive = new AtomicBoolean(true);

        private ScriptedProcess() throws IOException {
            this.stdoutWriter = new PipedOutputStream(stdout);
        }

        private void emit(String jsonLine) throws IOException {
            stdoutWriter.write((jsonLine + "\n").getBytes(StandardCharsets.UTF_8));
            stdoutWriter.flush();
        }

        private void emitReady() throws IOException {
            // ready 是裸的 daemon 事件行（{type:"daemon",...}），不走 {id,line} 信封。
            emit("{\"type\":\"daemon\",\"event\":\"ready\",\"sdkPreloaded\":true}");
        }

        private void closeOutput() throws IOException {
            stdoutWriter.close();
        }

        /** 宿主启动时会读 pid；Process.pid() 的默认实现直接抛异常，必须覆写。 */
        @Override
        public long pid() {
            return 424242L;
        }

        /** 宿主为当前请求生成的 id（从它写进 stdin 的请求里读出来）。 */
        private String lastRequestId() {
            String text = stdin.toString(StandardCharsets.UTF_8).trim();
            if (text.isEmpty()) {
                return null;
            }
            try {
                // 整段就是一个请求 JSON；不能用 lastIndexOf('{')，那会切到 params 内层。
                return JsonParser.parseString(text).getAsJsonObject().get("id").getAsString();
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public OutputStream getOutputStream() {
            return stdin;
        }

        @Override
        public InputStream getInputStream() {
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            return new java.io.ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
            alive.set(false);
        }

        @Override
        public Process destroyForcibly() {
            destroy();
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }
    }

    /** 记录被路由到会话 sink 的行 —— 查询响应一行都不该出现在这里。 */
    private static final class RecordingSink implements DaemonBridge.SessionLineSink {
        private final List<String> lines = new CopyOnWriteArrayList<>();

        @Override
        public void onLine(String line) {
            lines.add(line);
        }

        @Override
        public void onStderr(String text) {
        }
    }

    @Test
    public void sharedQueryResponseReachesItsOwnCollectorEvenWithASessionSinkRegistered()
            throws Exception {
        ScriptedProcess process = new ScriptedProcess();
        process.emitReady();
        DaemonBridge bridge = new DaemonBridge(
                null, null, null, DaemonBridge.TimeSource.system(),
                () -> process, DaemonBridge.DaemonLifecycleHooks.NO_OP);
        assertTrue(bridge.start());
        try {
            // 复现现场的装配顺序：会话先注册 sink（loadHistorySession 正是这么做的），
            // 然后才发起查询。
            RecordingSink sink = new RecordingSink();
            bridge.registerSessionSink(SESSION_ID, sink);

            OpenCodeSDKBridge sdk = new OpenCodeSDKBridge(bridge, new Gson());
            CompletableFuture<JsonElement> future = sdk.listMessages(SESSION_ID, "D:\\source\\demo");

            String requestId = awaitRequestId(process);
            assertNotNull("host never sent the listMessages request", requestId);

            // daemon 的真实协议：先播报条数，再逐条发 entry，最后 done。
            // 关键：这些行**不带 session 字段**（core 修复后的行为）。
            process.emit(envelope(requestId, "{\"success\":true,\"provider\":\"opencode\","
                    + "\"sessionId\":\"" + SESSION_ID + "\",\"messagesStart\":true,\"count\":2}"));
            // 真实协议：每条数据行是 {"messageEntry":{info,parts}}，不是裸 entry。
            process.emit(envelope(requestId,
                    "{\"messageEntry\":" + entry("msg-1", "user", "帮我看看这个 bug") + "}"));
            process.emit(envelope(requestId,
                    "{\"messageEntry\":" + entry("msg-2", "assistant", "问题在 SessionSendService 里。") + "}"));
            process.emit(done(requestId));

            JsonElement result = future.get(AWAIT_SECONDS, TimeUnit.SECONDS);
            assertTrue("listMessages must return an array", result.isJsonArray());
            assertEquals(2, result.getAsJsonArray().size());

            String firstRole = result.getAsJsonArray().get(0).getAsJsonObject()
                    .getAsJsonObject("info").get("role").getAsString();
            assertEquals("user", firstRole);

            // 响应行是请求自己的载荷，不该被当成会话数据塞进 sink
            for (String line : sink.lines) {
                assertFalse("a query response line leaked into the session sink: " + line,
                        line.contains("messageEntry"));
            }
        } finally {
            closeQuietly(process);
            bridge.stop();
        }
    }

    @Test
    public void announcedButUndeliveredEntriesFailLoudlyInsteadOfRenderingABlankPage()
            throws Exception {
        ScriptedProcess process = new ScriptedProcess();
        process.emitReady();
        DaemonBridge bridge = new DaemonBridge(
                null, null, null, DaemonBridge.TimeSource.system(),
                () -> process, DaemonBridge.DaemonLifecycleHooks.NO_OP);
        assertTrue(bridge.start());
        try {
            RecordingSink sink = new RecordingSink();
            bridge.registerSessionSink(SESSION_ID, sink);

            OpenCodeSDKBridge sdk = new OpenCodeSDKBridge(bridge, new Gson());
            CompletableFuture<JsonElement> future = sdk.listMessages(SESSION_ID, "D:\\source\\demo");

            String requestId = awaitRequestId(process);
            assertNotNull(requestId);

            // 模拟故障回归：daemon 播报 3 条，但 entry 行在路上被弄丢（例如又被盖上了
            // session 戳而被路由到 sink）。过去这里会静默返回空列表 → 空白页面。
            process.emit(envelope(requestId, "{\"success\":true,\"messagesStart\":true,\"count\":3}"));
            process.emit(done(requestId));

            try {
                future.get(AWAIT_SECONDS, TimeUnit.SECONDS);
                fail("announced 3 entries but delivered none — the host must fail, "
                        + "not silently render an empty session");
            } catch (java.util.concurrent.ExecutionException expected) {
                String message = String.valueOf(expected.getCause());
                assertTrue("error must name the mismatch, got: " + message,
                        message.contains("announced 3") && message.contains("collected 0"));
            }
        } finally {
            closeQuietly(process);
            bridge.stop();
        }
    }

    @Test
    public void genuinelyEmptyHistoryStillSucceeds() throws Exception {
        // 空会话与加载失败必须可区分：播报 0 条 ⇒ 正常返回空列表，不抛错。
        ScriptedProcess process = new ScriptedProcess();
        process.emitReady();
        DaemonBridge bridge = new DaemonBridge(
                null, null, null, DaemonBridge.TimeSource.system(),
                () -> process, DaemonBridge.DaemonLifecycleHooks.NO_OP);
        assertTrue(bridge.start());
        try {
            OpenCodeSDKBridge sdk = new OpenCodeSDKBridge(bridge, new Gson());
            CompletableFuture<JsonElement> future = sdk.listMessages(SESSION_ID, "D:\\source\\demo");

            String requestId = awaitRequestId(process);
            process.emit(envelope(requestId, "{\"success\":true,\"messagesStart\":true,\"count\":0}"));
            process.emit(done(requestId));

            JsonElement result = future.get(AWAIT_SECONDS, TimeUnit.SECONDS);
            assertTrue(result.isJsonArray());
            assertEquals(0, result.getAsJsonArray().size());
        } finally {
            closeQuietly(process);
            bridge.stop();
        }
    }

    @Test
    public void newSessionIdReachesItsStartingRequestBeforeASinkExists() throws Exception {
        // 2026-10-21 故障：新会话首轮消息永远“正在响应”。daemon 的 [SESSION_ID]
        // 行被盖上了刚创建会话的 session 戳，而宿主此时还没有该会话的 sink；
        // 重构后的 deliverDataLine 把它当会话数据缓冲起来，宿主永远学不到 id，
        // 也就永远不注册 sink —— 后续 delta 全部滞留在孤儿缓冲区。
        ScriptedProcess process = new ScriptedProcess();
        process.emitReady();
        DaemonBridge bridge = new DaemonBridge(
                null, null, null, DaemonBridge.TimeSource.system(),
                () -> process, DaemonBridge.DaemonLifecycleHooks.NO_OP);
        assertTrue(bridge.start());
        try {
            RecordingSink requestLines = new RecordingSink();
            bridge.sendCommand("opencode.send", new JsonObject(), new DaemonBridge.DaemonOutputCallback() {
                @Override
                public void onLine(String line) {
                    requestLines.lines.add(line);
                }

                @Override
                public void onStderr(String text) {
                }

                @Override
                public void onError(String error) {
                }

                @Override
                public void onComplete(boolean success) {
                }
            });
            String requestId = awaitRequestId(process);
            assertNotNull("host never sent the send request", requestId);

            process.emit(envelope(requestId, "[SESSION_ID] ses_new", "ses_new"));
            awaitLine(requestLines.lines, "[SESSION_ID] ses_new",
                    "identity marker must reach the request that created the session");

            // 内容行仍归会话所有：宿主办完 id、注册 sink 后必须补发，且不进请求收集器。
            RecordingSink sessionSink = new RecordingSink();
            process.emit(envelope(requestId, "[CONTENT_DELTA] \"hi\"", "ses_new"));
            bridge.registerSessionSink("ses_new", sessionSink);
            awaitLine(sessionSink.lines, "[CONTENT_DELTA] \"hi\"",
                    "content awaited a sink that the bound session never got");
            assertFalse("content must not be handed to the request collector",
                    requestLines.lines.contains("[CONTENT_DELTA] \"hi\""));
        } finally {
            closeQuietly(process);
            bridge.stop();
        }
    }

    // ===== helpers =====

    private static void closeQuietly(ScriptedProcess process) {
        try {
            process.closeOutput();
        } catch (IOException ignored) {
        }
    }

    /**
     * 把一行内部 JSON 包进 NDJSON 信封。{@code line} 字段是 JSON 字符串，
     * 内部的引号与反斜杠必须转义 —— 直接拼接会让信封变成非法 JSON，
     * 宿主解析失败并丢弃整行（正是本测试最初跑不起来暴露的坑）。
     */
    private static String envelope(String requestId, String innerJson) {
        String escaped = innerJson.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"id\":\"" + requestId + "\",\"line\":\"" + escaped + "\"}";
    }

    /**
     * Envelope for a data line stamped with a session — the shape the daemon
     * produces once the session id is known.
     */
    private static String envelope(String requestId, String line, String session) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", requestId);
        obj.addProperty("session", session);
        obj.addProperty("line", line);
        return obj.toString();
    }

    private static String done(String requestId) {
        return "{\"id\":\"" + requestId + "\",\"done\":true,\"success\":true}";
    }

    /** opencode v2 `GET /api/session/{id}/message` 的单条形状：{info, parts}。 */
    private static String entry(String id, String role, String text) {
        JsonObject part = new JsonObject();
        part.addProperty("type", "text");
        part.addProperty("text", text);
        JsonArray parts = new JsonArray();
        parts.add(part);

        JsonObject info = new JsonObject();
        info.addProperty("id", id);
        info.addProperty("role", role);
        JsonObject time = new JsonObject();
        time.addProperty("created", 1_700_000_000_000L);
        info.add("time", time);

        JsonObject entry = new JsonObject();
        entry.add("info", info);
        entry.add("parts", parts);
        return new Gson().toJson(entry);
    }

    private static void awaitLine(List<String> lines, String expected, String message)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (lines.contains(expected)) {
                return;
            }
            Thread.sleep(20);
        }
        fail(message + " — expected: " + expected + ", saw: " + lines);
    }

    private static String awaitRequestId(ScriptedProcess process) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            String id = process.lastRequestId();
            if (id != null) {
                return id;
            }
            Thread.sleep(20);
        }
        return null;
    }
}
