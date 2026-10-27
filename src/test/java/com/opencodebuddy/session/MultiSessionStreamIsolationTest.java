package com.opencodebuddy.session;

import com.opencodebuddy.handler.core.HandlerContext;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.ui.jcef.JBCefBrowser;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 多会话隔离回归测试：证明「两个会话同时在跑时，消息不会串」。
 *
 * <p>这三层原本都靠「当前活跃会话」推断归属，推断失败就静默落到前台，
 * 于是一个会话的 delta/快照/弹窗写进另一个会话。本用例守住两件事实：</p>
 *
 * <ol>
 *   <li>{@link StreamMessageCoalescer} 每帧快照都盖自己的 sessionId，
 *       两个 coalescer 互不越界；</li>
 *   <li>没有身份的 coalescer 直接丢弃快照，但终态信号（flush 回调 →
 *       onStreamEnd/showLoading(false)）仍然送达，否则标签页会一直 loading。</li>
 * </ol>
 *
 * <p>另外守住历史加载路径：两个会话并发 loadFromServer 时，各自只读自己的
 * transcript、只回调自己的前端，一个会话加载失败不会污染另一个。</p>
 *
 * <p>{@code StreamMessageCoalescer} 走 {@code executeOnPooledThread} +
 * {@code invokeLater}，而轻量单测里 {@code ApplicationManager.getApplication()}
 * 是 null。这里在类级别装一个「同步执行」的 Application 存根，并在结束时还原
 * 成原来的值（null），不影响其他测试类。</p>
 */
public class MultiSessionStreamIsolationTest {

    /** The snapshot/tail pushes the coalescer published. */
    private static List<String> snapshotCalls(RecordingEventSink sink) {
        List<String> calls = new ArrayList<>();
        for (String call : sink.calls()) {
            if (call.startsWith("message.snapshot") || call.startsWith("message.tail")) {
                calls.add(call);
            }
        }
        return calls;
    }

    private static final long AWAIT_SECONDS = 15;

    private static Application previousApplication;

    @BeforeClass
    public static void installSynchronousApplication() {
        previousApplication = ApplicationManager.getApplication();
        ApplicationManager.setApplication(synchronousApplication());
    }

    /**
     * 平台测试框架会在类与方法之间重置 Application，所以每个用例前再确认一次。
     */
    @org.junit.Before
    public void ensureSynchronousApplication() {
        if (ApplicationManager.getApplication() == null) {
            ApplicationManager.setApplication(synchronousApplication());
        }
    }

    @AfterClass
    public static void restoreApplication() {
        // 还原成本测试套件初始的状态，不影响其他测试类。
        ApplicationManager.setApplication(previousApplication);
    }

    /** Runs pooled + EDT work inline so the assertions are deterministic. */
    private static Application synchronousApplication() {
        return (Application) Proxy.newProxyInstance(
                Application.class.getClassLoader(),
                new Class<?>[] {Application.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "executeOnPooledThread":
                        case "invokeLater":
                            if (args != null && args.length >= 1 && args[0] instanceof Runnable) {
                                ((Runnable) args[0]).run();
                            }
                            return null;
                        case "isDispatchThread":
                            return Boolean.TRUE;
                        case "isWriteThread":
                            return Boolean.FALSE;
                        case "isReadAccessAllowed":
                        case "hasWriteAction":
                            return Boolean.FALSE;
                        case "isDisposed":
                        case "isUnitTestMode":
                        case "isHeadlessEnvironment":
                        case "isInternal":
                        case "isEAP":
                            return Boolean.FALSE;
                        case "getAnyModalityState":
                        case "getDefaultModalityState":
                        case "getCurrentModalityState":
                        case "getNoneModalityState":
                            return ModalityState.NON_MODAL;
                        default:
                            return null;
                    }
                });
    }

    // ===== 数据面：coalescer 按会话打戳 =====

    @Test
    public void eachCoalescerStampsItsOwnSessionIdOnEverySnapshot() throws Exception {
        RecordingTarget targetA = new RecordingTarget();
        RecordingEventSink sinkA = new RecordingEventSink();
        StreamMessageCoalescer coalescerA = new StreamMessageCoalescer("ses_a", sinkA, targetA);
        RecordingTarget targetB = new RecordingTarget();
        RecordingEventSink sinkB = new RecordingEventSink();
        StreamMessageCoalescer coalescerB = new StreamMessageCoalescer("ses_b", sinkB, targetB);
        try {
            // 交替 enqueue：模拟两个会话同时流式输出。
            coalescerA.enqueue(List.of(message("A1")));
            coalescerB.enqueue(List.of(message("B1")));
            coalescerA.enqueue(List.of(message("A1"), message("A2")));
            coalescerB.enqueue(List.of(message("B1"), message("B2")));

            CountDownLatch flushed = new CountDownLatch(2);
            coalescerA.flush(seq -> flushed.countDown());
            coalescerB.flush(seq -> flushed.countDown());
            assertTrue("flush callbacks never ran", flushed.await(AWAIT_SECONDS, TimeUnit.SECONDS));

            assertFalse("session A produced no snapshot at all", snapshotCalls(sinkA).isEmpty());
            assertFalse("session B produced no snapshot at all", snapshotCalls(sinkB).isEmpty());
            for (String call : snapshotCalls(sinkA)) {
                assertTrue("A's push carried a foreign session id: " + call,
                        call.startsWith("message.snapshot:ses_a:["));
            }
            for (String call : snapshotCalls(sinkB)) {
                assertTrue("B's push carried a foreign session id: " + call,
                        call.startsWith("message.snapshot:ses_b:["));
            }
            // 内容也不许串：A 的最后一个快照只有 A 的消息。
            List<String> snapsA = snapshotCalls(sinkA);
            String lastA = snapsA.get(snapsA.size() - 1);
            assertTrue("A's snapshot lost its own message: " + lastA, lastA.contains("A2"));
            assertFalse("A's snapshot contains session B's message: " + lastA, lastA.contains("B1"));
            List<String> snapsB = snapshotCalls(sinkB);
            String lastB = snapsB.get(snapsB.size() - 1);
            assertTrue("B's snapshot lost its own message: " + lastB, lastB.contains("B2"));
            assertFalse("B's snapshot contains session A's message: " + lastB, lastB.contains("A1"));
        } finally {
            coalescerA.dispose();
            coalescerB.dispose();
        }
    }

    @Test
    public void everySnapshotCarriesTheOwningTabId() throws Exception {
        RecordingTarget targetA = new RecordingTarget();
        RecordingEventSink sinkA = new RecordingEventSink();
        StreamMessageCoalescer coalescerA = new StreamMessageCoalescer("ses_a", "tab-a", sinkA, targetA);
        RecordingTarget targetB = new RecordingTarget();
        RecordingEventSink sinkB = new RecordingEventSink();
        StreamMessageCoalescer coalescerB = new StreamMessageCoalescer("ses_b", "tab-b", sinkB, targetB);
        try {
            coalescerA.enqueue(List.of(message("A1")));
            coalescerB.enqueue(List.of(message("B1")));

            CountDownLatch flushed = new CountDownLatch(2);
            coalescerA.flush(seq -> flushed.countDown());
            coalescerB.flush(seq -> flushed.countDown());
            assertTrue("flush callbacks never ran", flushed.await(AWAIT_SECONDS, TimeUnit.SECONDS));

            assertFalse("session A produced no snapshot at all", snapshotCalls(sinkA).isEmpty());
            assertFalse("session B produced no snapshot at all", snapshotCalls(sinkB).isEmpty());
            // The tab id is what lets the webview route a snapshot to exactly one
            // tab; without it the frontend has to look the owner up by session.
            for (com.opencodebuddy.tab.TabEvent event : sinkA.events) {
                assertEquals("A's push lost its tab id: " + event, "tab-a", event.getTabId());
                assertEquals("A's push lost its session id: " + event, "ses_a", event.getSessionId());
            }
            for (com.opencodebuddy.tab.TabEvent event : sinkB.events) {
                assertEquals("B's push lost its tab id: " + event, "tab-b", event.getTabId());
                assertEquals("B's push lost its session id: " + event, "ses_b", event.getSessionId());
            }
        } finally {
            coalescerA.dispose();
            coalescerB.dispose();
        }
    }

    @Test
    public void usageUpdateIsStampedWithTheStreamingSessionNotTheActiveOne() throws Exception {
        RecordingTarget target = new RecordingTarget();
        RecordingEventSink sink = new RecordingEventSink();
        OpencodeSession owner = new OpencodeSession(null, null);
        owner.setProvider("opencode");
        owner.setModel("model-a[200k]");
        target.setOwner(owner);

        // HandlerContext follows whichever tab is on screen. Point it at a
        // different session with a different context window; the usage push must
        // still be attributed to (and sized by) the coalescer's own session.
        OpencodeSession active = new OpencodeSession(null, null);
        active.getState().setSessionId("ses_b");
        active.setProvider("opencode");
        active.setModel("model-b[100k]");
        target.getHandlerContext().setSession(active);
        target.getHandlerContext().setCurrentModel("model-b[100k]");

        StreamMessageCoalescer coalescer = new StreamMessageCoalescer("ses_a", sink, target);
        try {
            com.google.gson.JsonObject tokens = new com.google.gson.JsonObject();
            tokens.addProperty("input", 2_000);
            com.google.gson.JsonObject raw = new com.google.gson.JsonObject();
            raw.addProperty("type", "assistant");
            raw.add("tokens", tokens);
            OpencodeSession.Message assistant = new OpencodeSession.Message(
                    OpencodeSession.Message.Type.ASSISTANT, "A's reply", raw);

            coalescer.enqueue(List.of(assistant));
            CountDownLatch flushed = new CountDownLatch(1);
            coalescer.flush(seq -> flushed.countDown());
            assertTrue("flush callback never ran", flushed.await(AWAIT_SECONDS, TimeUnit.SECONDS));

            String usageCall = null;
            for (String call : target.calls) {
                if (call.startsWith("window.onUsageUpdate:")) {
                    usageCall = call;
                }
            }
            // Usage travels as the named event; the envelope carries the session
            // and the payload carries the counters.
            com.opencodebuddy.tab.TabEvent usageEvent = null;
            for (com.opencodebuddy.tab.TabEvent event : target.events) {
                if ("usage.updated".equals(event.getType())) {
                    usageEvent = event;
                }
            }
            assertNull("the legacy positional usage call is gone", usageCall);
            assertNotNull("no usage update was pushed", usageEvent);
            assertEquals("usage was attributed to the wrong session",
                    "ses_a", usageEvent.getSessionId());
            assertFalse("usage leaked to the active session",
                    "ses_b".equals(usageEvent.getSessionId()));
            com.google.gson.JsonObject usagePayload =
                    (com.google.gson.JsonObject) usageEvent.getPayload().get("usage");
            assertNotNull("usage payload is missing", usagePayload);
            assertTrue("usage used the active session's context window",
                    usagePayload.get("maxTokens").getAsInt() == 200_000);
        } finally {
            coalescer.dispose();
        }
    }

    @Test
    public void snapshotWithoutSessionIdentityIsDroppedButTerminalStillFires() throws Exception {
        RecordingTarget target = new RecordingTarget();
        RecordingEventSink sink = new RecordingEventSink();
        // sessionId 未解析（null）：既不能猜「当前屏幕上的会话」，也不能把
        // 快照糊到前台去。
        StreamMessageCoalescer coalescer = new StreamMessageCoalescer((String) null, sink, target);
        try {
            coalescer.enqueue(List.of(message("ghost")));

            CountDownLatch flushed = new CountDownLatch(1);
            coalescer.flush(seq -> flushed.countDown());
            assertTrue("flush callback never ran", flushed.await(AWAIT_SECONDS, TimeUnit.SECONDS));

            for (String call : sink.calls()) {
                assertFalse("un-attributable snapshot reached the webview: " + call,
                        call.startsWith("message.snapshot") || call.startsWith("message.tail"));
            }
        } finally {
            coalescer.dispose();
        }
    }

    @Test
    public void blankSessionIdentityIsAlsoDropped() throws Exception {
        RecordingTarget target = new RecordingTarget();
        RecordingEventSink sink = new RecordingEventSink();
        // 空串与 null 同义：前端把 '' 读成「当前会话」，所以同样必须丢。
        StreamMessageCoalescer coalescer = new StreamMessageCoalescer("   ", sink, target);
        try {
            coalescer.enqueue(List.of(message("ghost")));
            CountDownLatch flushed = new CountDownLatch(1);
            coalescer.flush(seq -> flushed.countDown());
            assertTrue(flushed.await(AWAIT_SECONDS, TimeUnit.SECONDS));

            for (String call : target.calls) {
                assertFalse("blank session id reached the webview: " + call,
                        call.startsWith("message.snapshot") || call.startsWith("message.tail"));
            }
        } finally {
            coalescer.dispose();
        }
    }

    @Test
    public void setSessionIdReAttributesAnExistingCoalescer() throws Exception {
        // 宿主在 registerSession 时回填 adapter.setSessionId → coalescer.setSessionId，
        // 之后同一实例的快照必须带上新身份。
        RecordingTarget target = new RecordingTarget();
        RecordingEventSink sink = new RecordingEventSink();
        StreamMessageCoalescer coalescer = new StreamMessageCoalescer((String) null, sink, target);
        try {
            coalescer.setSessionId("ses_late");
            coalescer.enqueue(List.of(message("late")));
            CountDownLatch flushed = new CountDownLatch(1);
            coalescer.flush(seq -> flushed.countDown());
            assertTrue(flushed.await(AWAIT_SECONDS, TimeUnit.SECONDS));

            assertFalse(snapshotCalls(sink).isEmpty());
            assertTrue(snapshotCalls(sink).get(snapshotCalls(sink).size() - 1)
                    .startsWith("message.snapshot:ses_late:["));
        } finally {
            coalescer.dispose();
        }
    }

    // ===== 历史会话加载：两个会话并发不串 =====

    @Test
    public void concurrentHistoryLoadsKeepTheirOwnTranscriptAndCallback() throws Exception {
        SessionState stateA = state("ses_a");
        SessionState stateB = state("ses_b");
        RecordingCallback callbackA = new RecordingCallback();
        RecordingCallback callbackB = new RecordingCallback();
        RecordingHistoryAccess historyA = new RecordingHistoryAccess("ses_a");
        RecordingHistoryAccess historyB = new RecordingHistoryAccess("ses_b");
        historyA.providerHistory = List.of(providerMessage("user", "A question"), providerMessage("assistant", "A answer"));
        historyB.providerHistory = List.of(providerMessage("user", "B question"), providerMessage("assistant", "B answer"));

        SessionMessageOrchestrator orchestratorA = orchestrator(stateA, callbackA, historyA);
        SessionMessageOrchestrator orchestratorB = orchestrator(stateB, callbackB, historyB);

        CompletableFuture.allOf(orchestratorA.loadFromServer(), orchestratorB.loadFromServer()).join();

        // 各自的 state 只有自己的消息
        assertEquals(2, stateA.getMessages().size());
        assertEquals(2, stateB.getMessages().size());
        assertEquals("A question", stateA.getMessages().get(0).content);
        assertEquals("A answer", stateA.getMessages().get(1).content);
        assertEquals("B question", stateB.getMessages().get(0).content);
        assertEquals("B answer", stateB.getMessages().get(1).content);

        // 各自只读自己的 transcript
        assertEquals(List.of("ses_a"), historyA.requestedSessionIds);
        assertEquals(List.of("ses_b"), historyB.requestedSessionIds);

        // 各自只推给自己的前端
        assertEquals(1, callbackA.messageUpdates.size());
        assertEquals(1, callbackB.messageUpdates.size());
        assertEquals("A question", callbackA.messageUpdates.get(0).get(0).content);
        assertEquals("B question", callbackB.messageUpdates.get(0).get(0).content);

        assertFalse(stateA.isLoading());
        assertFalse(stateB.isLoading());
    }

    @Test
    public void oneHistoryLoadFailureDoesNotCorruptTheOtherSession() throws Exception {
        SessionState stateA = state("ses_ok");
        SessionState stateB = state("ses_broken");
        RecordingCallback callbackA = new RecordingCallback();
        RecordingCallback callbackB = new RecordingCallback();
        RecordingHistoryAccess historyA = new RecordingHistoryAccess("ses_ok");
        historyA.providerHistory = List.of(providerMessage("assistant", "good answer"));
        SessionMessageOrchestrator.SessionHistoryAccess failing =
                new SessionMessageOrchestrator.SessionHistoryAccess() {
                    @Override
                    public List<com.google.gson.JsonObject> getProviderSessionMessages(
                            String provider, String sessionId, String cwd) {
                        throw new RuntimeException("connection refused");
                    }

                    @Override
                    public com.google.gson.JsonObject getLatestClaudeUserMessage(String sessionId, String cwd) {
                        return null;
                    }
                };

        SessionMessageOrchestrator orchestratorA = orchestrator(stateA, callbackA, historyA);
        SessionMessageOrchestrator orchestratorB = orchestrator(stateB, callbackB, failing);

        // 失败必须传播出去（否则调用方只看到「加载成功但消息为空」的幽灵状态）
        Throwable thrown = null;
        try {
            orchestratorB.loadFromServer().join();
        } catch (Exception e) {
            thrown = e;
        }
        orchestratorA.loadFromServer().join();

        assertTrue("history load failure must surface to the caller", thrown != null);
        assertEquals("connection refused", stateB.getError());
        assertFalse(stateB.isLoading());
        assertTrue("failed session must not have published messages", callbackB.messageUpdates.isEmpty());

        // 另一个会话完好无损
        assertNull(stateA.getError());
        assertEquals(1, stateA.getMessages().size());
        assertEquals("good answer", stateA.getMessages().get(0).content);
        assertEquals(1, callbackA.messageUpdates.size());
    }

    // ===== helpers =====

    private static SessionState state(String sessionId) {
        SessionState state = new SessionState();
        state.setProvider("claude");
        state.setSessionId(sessionId);
        state.setCwd("/workspace");
        return state;
    }

    private static SessionMessageOrchestrator orchestrator(
            SessionState state, RecordingCallback callback, SessionMessageOrchestrator.SessionHistoryAccess history) {
        SessionCallbackFacade facade = new SessionCallbackFacade(null);
        facade.setCallback(callback);
        return new SessionMessageOrchestrator(
                state,
                new MessageParser(),
                facade,
                history,
                (usedTokens, maxTokens) -> {
                },
                0,
                0
        );
    }

    private static OpencodeSession.Message message(String content) {
        return new OpencodeSession.Message(OpencodeSession.Message.Type.ASSISTANT, content);
    }

    private static com.google.gson.JsonObject providerMessage(String type, String text) {
        com.google.gson.JsonObject contentBlock = new com.google.gson.JsonObject();
        contentBlock.addProperty("type", "text");
        contentBlock.addProperty("text", text);
        com.google.gson.JsonArray content = new com.google.gson.JsonArray();
        content.add(contentBlock);
        com.google.gson.JsonObject message = new com.google.gson.JsonObject();
        message.add("content", content);
        com.google.gson.JsonObject serverMessage = new com.google.gson.JsonObject();
        serverMessage.addProperty("type", type);
        serverMessage.add("message", message);
        return serverMessage;
    }

    /** Records every callJavaScript so we can assert which session it was stamped with. */
    private static final class RecordingTarget implements StreamMessageCoalescer.JsCallbackTarget {
        private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        private final List<String> updateCalls = Collections.synchronizedList(new ArrayList<>());
        private final List<com.opencodebuddy.tab.TabEvent> events = Collections.synchronizedList(new ArrayList<>());
        private final HandlerContext handlerContext;
        private volatile OpencodeSession owner;

        private RecordingTarget() {
            this.handlerContext = new HandlerContext(null, null, null, new HandlerContext.JsCallback() {
                @Override
                public void callJavaScript(String functionName, String... args) {
                    record(functionName, args);
                }

                @Override
                public void publishEvent(com.opencodebuddy.tab.TabEvent event) {
                    events.add(event);
                }

                @Override
                public String escapeJs(String str) {
                    return str != null ? str : "";
                }
            });
        }

        private void record(String functionName, String... args) {
            StringBuilder sb = new StringBuilder(functionName);
            for (String arg : args) {
                sb.append(':').append(arg);
            }
            String call = sb.toString();
            calls.add(call);
            if (call.startsWith("message.snapshot") || call.startsWith("message.tail")) {
                updateCalls.add(call);
            }
        }

        private void setOwner(OpencodeSession owner) {
            this.owner = owner;
        }

        // Legacy call recorder: the coalescer publishes events now, so this is
        // no longer an interface method. Kept so the recorded fixtures stay intact.
        public void callJavaScript(String functionName, String... args) {
            record(functionName, args);
        }

        @Override
        public JBCefBrowser getBrowser() {
            return null;
        }

        @Override
        public boolean isDisposed() {
            return false;
        }

        @Override
        public HandlerContext getHandlerContext() {
            return handlerContext;
        }

        @Override
        public OpencodeSession getSession() {
            return owner;
        }
    }

    /** History access that records which session id it was asked for. */
    private static final class RecordingHistoryAccess implements SessionMessageOrchestrator.SessionHistoryAccess {
        private final String expectedSessionId;
        private final List<String> requestedSessionIds = Collections.synchronizedList(new ArrayList<>());
        private volatile List<com.google.gson.JsonObject> providerHistory = List.of();

        private RecordingHistoryAccess(String expectedSessionId) {
            this.expectedSessionId = expectedSessionId;
        }

        @Override
        public List<com.google.gson.JsonObject> getProviderSessionMessages(
                String provider, String sessionId, String cwd) {
            requestedSessionIds.add(sessionId);
            assertEquals("history was read for the wrong session", expectedSessionId, sessionId);
            return providerHistory;
        }

        @Override
        public com.google.gson.JsonObject getLatestClaudeUserMessage(String sessionId, String cwd) {
            return null;
        }
    }

    private static final class RecordingCallback implements OpencodeSession.SessionCallback {
        private final List<List<OpencodeSession.Message>> messageUpdates =
                Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onMessageUpdate(List<OpencodeSession.Message> messages) {
            messageUpdates.add(messages);
        }

        @Override
        public void onStateChange(boolean busy, boolean loading, String error) {
        }

        @Override
        public void onUsageUpdate(int usedTokens, int maxTokens) {
        }

        @Override
        public void onUserMessageUuidPatched(String content, String uuid) {
        }

        @Override
        public void onSessionIdReceived(String sessionId) {
        }

        @Override
        public void onPermissionRequested(com.opencodebuddy.permission.PermissionRequest request) {
        }

        @Override
        public void onThinkingStatusChanged(boolean isThinking) {
        }

        @Override
        public void onSlashCommandsReceived(List<String> slashCommands) {
        }

        @Override
        public void onNodeLog(String log) {
        }

        @Override
        public void onSummaryReceived(String summary) {
        }
    }
}
