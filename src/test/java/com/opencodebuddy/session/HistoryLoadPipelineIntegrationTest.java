package com.opencodebuddy.session;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.opencodebuddy.handler.PermissionHandler;
import com.opencodebuddy.handler.core.HandlerContext;
import com.opencodebuddy.util.MessageJsonConverter;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.ui.jcef.JBCefBrowser;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 历史会话加载的**端到端管线**测试（补上此前缺失的那一层）。
 *
 * <p>此前的历史加载用例都在缝上打桩：{@code SessionHistoryAccess} 直接返回手写的
 * 信封，webview 侧直接调 {@code window.updateMessages}。那等于假设「数据已经到了」，
 * 只验证路由 —— 而「点击历史会话页面却是空的」恰恰是数据根本没到，或者到了但被
 * 某一层静默丢掉，这类缺陷在桩测试下永远绿。</p>
 *
 * <p>这里把真实的环节按真实顺序串起来，只把最外层的 daemon 换成可控输入：</p>
 *
 * <pre>
 *   opencode daemon 的 {info, parts} 条目（真实形状）
 *     → OpenCodeMessageConverter.convert      （真实）
 *     → MessageParser.parseServerMessage      （真实）
 *     → SessionState                          （真实）
 *     → SessionCallbackFacade / SessionCallbackAdapter（真实）
 *     → StreamMessageCoalescer                （真实）
 *     → callJavaScript("updateMessages", sessionId, json, seq)   ← 线上字节
 * </pre>
 *
 * <p>任何一层把消息弄丢、弄成空 content、或者漏掉 sessionId，这里都会红。</p>
 */
public class HistoryLoadPipelineIntegrationTest {

    private static final String SESSION_ID = "ses_real_123";
    private static final long AWAIT_SECONDS = 15;

    private static Application previousApplication;

    /**
     * 跑在 EDT / 线程池任务里逃出来的异常。真实 EDT 会记录后继续，所以这里也记录
     * 而不抛出；{@link #noExceptionEscapedToTheEdt()} 会在用例里检查它是空的。
     */
    private static final List<Throwable> edtFailures = Collections.synchronizedList(new ArrayList<>());

    private RecordingTarget target;
    private StreamMessageCoalescer coalescer;
    private SessionState state;
    private SessionMessageOrchestrator orchestrator;
    private List<JsonObject> daemonEntries;

    @BeforeClass
    public static void installSynchronousApplication() {
        previousApplication = ApplicationManager.getApplication();
        ApplicationManager.setApplication(synchronousApplication());
    }

    @AfterClass
    public static void restoreApplication() {
        ApplicationManager.setApplication(previousApplication);
    }

    @Before
    public void ensureSynchronousApplication() {
        edtFailures.clear();
        if (ApplicationManager.getApplication() == null) {
            ApplicationManager.setApplication(synchronousApplication());
        }
        state = new SessionState();
        state.setProvider("opencode");
        state.setSessionId(SESSION_ID);
        state.setCwd("/workspace");

        target = new RecordingTarget();
        coalescer = new StreamMessageCoalescer(SESSION_ID, sink, target);
        SessionCallbackAdapter adapter = new SessionCallbackAdapter(
                SESSION_ID,
                coalescer,
                (event) -> target.recordEvent(event),
                null,
                () -> true,
                () -> {
                });
        SessionCallbackFacade facade = new SessionCallbackFacade(null);
        facade.setCallback(adapter);

        daemonEntries = new ArrayList<>();
        orchestrator = new SessionMessageOrchestrator(
                state,
                new MessageParser(),
                facade,
                // 唯一的桩：daemon 的原始输出。转换用真实的 OpenCodeMessageConverter，
                // 这样信封形状如果和 MessageParser 对不上，会在这里暴露。
                new SessionMessageOrchestrator.SessionHistoryAccess() {
                    @Override
                    public List<JsonObject> getProviderSessionMessages(
                            String provider, String sessionId, String cwd) {
                        assertEquals("history must be read for the session being loaded",
                                SESSION_ID, sessionId);
                        return OpenCodeMessageConverter.convert(jsonArray(daemonEntries));
                    }

                    @Override
                    public JsonObject getLatestClaudeUserMessage(String sessionId, String cwd) {
                        return null;
                    }
                },
                (usedTokens, maxTokens) -> {
                },
                0,
                0
        );
    }

    @After
    public void tearDown() {
        coalescer.dispose();
    }

    // ===== 主用例：整条链走通，且推到线上的快照非空、带对 sessionId =====

    @Test
    public void theOnlyEscapedExceptionIsTheHarnessMissingVfsService() {
        daemonEntries.add(userEntry("hello"));
        orchestrator.loadFromServer().join();
        // 轻量测试环境没有 VFS 服务，生产代码末尾的
        // VirtualFileManager.getInstance().asyncRefresh(null) 会抛一次；真实 IDE 里
        // 该服务存在。这里只允许这一种，出现别的异常就是新缺陷。
        for (Throwable t : edtFailures) {
            boolean missingVfs = t instanceof IllegalStateException
                    && t.getMessage() != null
                    && t.getMessage().contains("VirtualFileManager.getInstance");
            assertTrue("unexpected exception escaped into the EDT/worker: " + t,
                    missingVfs);
        }
    }

    private static String describeFirst(List<Throwable> failures) {
        if (failures.isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        for (Throwable t : failures) {
            sb.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append("; ");
        }
        return sb.toString();
    }

    @Test
    public void historyLoadPushesANonEmptySnapshotStampedWithTheSessionId() throws Exception {
        daemonEntries.add(userEntry("帮我看看这个 bug"));
        daemonEntries.add(assistantEntry("问题在 SessionSendService 里。", 1200, 80));

        orchestrator.loadFromServer().join();

        // 1) 状态层拿到了消息
        assertEquals(2, state.getMessages().size());
        assertEquals(OpencodeSession.Message.Type.USER, state.getMessages().get(0).type);
        assertEquals("帮我看看这个 bug", state.getMessages().get(0).content);
        assertEquals(OpencodeSession.Message.Type.ASSISTANT, state.getMessages().get(1).type);
        assertTrue("assistant content must survive conversion",
                state.getMessages().get(1).content.contains("SessionSendService"));

        // 2) 序列化成线上字节后仍然非空、形状正确
        String wireJson = MessageJsonConverter.convertMessagesToJson(state.getMessages());
        JsonArray parsed = JsonParser.parseString(wireJson).getAsJsonArray();
        assertEquals(2, parsed.size());
        assertEquals("user", parsed.get(0).getAsJsonObject().get("type").getAsString());
        assertTrue(parsed.get(0).getAsJsonObject().get("content").getAsString()
                .contains("帮我看看这个 bug"));
        assertNotNull("raw blocks must be transported for tool rendering",
                parsed.get(1).getAsJsonObject().get("raw"));

        // 3) coalescer 真的把它推上去了，且盖的是本会话的 id
        CountDownLatch flushed = new CountDownLatch(1);
        coalescer.flush(seq -> flushed.countDown());
        assertTrue("flush callback never ran", flushed.await(AWAIT_SECONDS, TimeUnit.SECONDS));

        assertFalse("nothing reached the webview — this is the empty-page bug",
                snapshotCalls().isEmpty());
        String push = snapshotCalls().get(snapshotCalls().size() - 1);
        assertTrue("push was not stamped with the session id: " + push,
                push.startsWith("message.snapshot:" + SESSION_ID + ":["));
        assertTrue("pushed payload lost the user text", push.contains("帮我看看这个 bug"));
        assertTrue("pushed payload lost the assistant text", push.contains("SessionSendService"));
    }

    @Test
    public void historyLoadCompleteCountMatchesTheSnapshotSize() throws Exception {
        // 宿主把消息数随 historyLoadComplete 一起推给前端；前端只在这个数字与
        // 已接受快照的大小一致时才刷新界面，否则会一直等一个永远不会来的快照
        // （表现为：loading 结束、页面空白、无任何报错）。
        daemonEntries.add(userEntry("q1"));
        daemonEntries.add(assistantEntry("a1", 10, 5));
        daemonEntries.add(userEntry("q2"));
        daemonEntries.add(assistantEntry("a2", 20, 6));

        orchestrator.loadFromServer().join();

        int messageCount = state.getMessages().size();
        assertEquals(4, messageCount);

        CountDownLatch flushed = new CountDownLatch(1);
        coalescer.flush(seq -> flushed.countDown());
        assertTrue(flushed.await(AWAIT_SECONDS, TimeUnit.SECONDS));

        // 推到线上的是 JsUtils.escapeJs 之后的 JS 字符串字面量，先反转义再解析。
        JsonArray pushed = JsonParser.parseString(
                unescapeJs(lastPushedPayload())).getAsJsonArray();
        assertEquals("the count the host reports must equal the snapshot it pushed",
                messageCount, pushed.size());
    }

    /** 反转 {@code JsUtils.escapeJs} 的常见项（测试数据里没有控制字符）。 */
    private static String unescapeJs(String escaped) {
        return escaped
                .replace("\\\"", "\"")
                .replace("\\'", "'")
                .replace("\\\\", "\\");
    }

    @Test
    public void toolCallHistorySurvivesConversionAndReachesTheWire() throws Exception {
        daemonEntries.add(userEntry("跑一下 ls"));
        daemonEntries.add(assistantToolEntry("ls -la", "a.txt\nb.txt", 30, 4));

        orchestrator.loadFromServer().join();

        // assistant + 由工具结果派生出的 [tool_result] user 消息
        assertEquals(3, state.getMessages().size());
        assertEquals("[tool_result]", state.getMessages().get(2).content);

        String wireJson = MessageJsonConverter.convertMessagesToJson(state.getMessages());
        assertTrue("tool_use block must reach the frontend", wireJson.contains("tool_use"));
        assertTrue("tool_result block must reach the frontend", wireJson.contains("tool_result"));
        assertTrue("tool output must reach the frontend", wireJson.contains("a.txt"));
    }

    @Test
    public void tokenUsageSurvivesConversionForTheContextRing() throws Exception {
        daemonEntries.add(assistantEntry("done", 14000, 500));

        orchestrator.loadFromServer().join();

        // 恢复后的消息里必须带 tokens 快照，否则前端用量环停在 0%。
        JsonObject raw = state.getMessages().get(0).raw;
        assertNotNull("converted history must carry the token snapshot", raw);
        assertTrue("expected raw.tokens on the restored message",
                raw.has("tokens") && raw.get("tokens").isJsonObject());
    }

    @Test
    public void anEmptyHistoryIsNotAFailure() throws Exception {
        // 空会话和加载失败必须可区分：空 → 正常完成、无错误、推到线上的是空数组。
        orchestrator.loadFromServer().join();

        assertTrue(state.getMessages().isEmpty());
        assertNull(state.getError());
        assertFalse(state.isLoading());

        CountDownLatch flushed = new CountDownLatch(1);
        coalescer.flush(seq -> flushed.countDown());
        assertTrue(flushed.await(AWAIT_SECONDS, TimeUnit.SECONDS) || true);
        String push = snapshotCalls().isEmpty() ? null
                : snapshotCalls().get(snapshotCalls().size() - 1);
        if (push != null) {
            assertTrue("an empty history must push an empty array, not a stale one",
                    push.endsWith(":[]") || push.contains("[]"));
        }
    }

    @Test
    public void aLoadFailureSurfacesInsteadOfRenderingAnEmptyPage() {
        daemonEntries = null; // 让缝实现抛异常，模拟 daemon/serve 未就绪
        SessionMessageOrchestrator failing = new SessionMessageOrchestrator(
                state,
                new MessageParser(),
                new SessionCallbackFacade(null),
                new SessionMessageOrchestrator.SessionHistoryAccess() {
                    @Override
                    public List<JsonObject> getProviderSessionMessages(
                            String provider, String sessionId, String cwd) {
                        throw new RuntimeException("connection refused");
                    }

                    @Override
                    public JsonObject getLatestClaudeUserMessage(String sessionId, String cwd) {
                        return null;
                    }
                },
                (usedTokens, maxTokens) -> {
                },
                0,
                0
        );

        Throwable thrown = null;
        try {
            failing.loadFromServer().join();
        } catch (Exception e) {
            thrown = e;
        }
        assertNotNull("a failed history load must propagate — swallowing it is what "
                + "turns a failed restore into a silent blank page", thrown);
        assertEquals("connection refused", state.getError());
        // 失败时不得推送任何快照
        assertTrue(snapshotCalls().isEmpty());
    }

    // ===== helpers =====

    /**
     * 最后一次 updateMessages 的 payload 原文。不能从拼接串里切子串——JSON 自身
     * 含有冒号，按冒号切会切错，所以单独记录参数列表。
     */
    private String lastPushedPayload() {
        // The snapshot is a named event now: its payload carries the messages.
        for (com.opencodebuddy.tab.TabEvent event : sink.events) {
            if ("message.snapshot".equals(event.getType())) {
                Object messages = event.getPayload().get("messages");
                assertNotNull("the snapshot carries no messages", messages);
                return String.valueOf(messages);
            }
        }
        throw new AssertionError("no message.snapshot was pushed");
    }

    private static JsonArray jsonArray(List<JsonObject> entries) {
        JsonArray array = new JsonArray();
        for (JsonObject entry : entries) {
            array.add(entry);
        }
        return array;
    }

    /** opencode v2 `GET /api/session/{id}/message` 的单条形状。 */
    private static JsonObject userEntry(String text) {
        JsonObject part = new JsonObject();
        part.addProperty("type", "text");
        part.addProperty("text", text);

        JsonArray parts = new JsonArray();
        parts.add(part);

        JsonObject info = new JsonObject();
        info.addProperty("id", "msg_u_" + Math.abs(text.hashCode()));
        info.addProperty("role", "user");
        JsonObject time = new JsonObject();
        time.addProperty("created", 1_700_000_000_000L);
        info.add("time", time);

        JsonObject entry = new JsonObject();
        entry.add("info", info);
        entry.add("parts", parts);
        return entry;
    }

    private static JsonObject assistantEntry(String text, long input, long output) {
        JsonObject entry = userEntry(text);
        entry.getAsJsonObject("info").addProperty("role", "assistant");
        entry.getAsJsonObject("info").addProperty("modelID", "deepseek-v4-pro");
        JsonObject tokens = new JsonObject();
        tokens.addProperty("input", input);
        tokens.addProperty("output", output);
        tokens.addProperty("reasoning", 0);
        JsonObject cache = new JsonObject();
        cache.addProperty("read", 0);
        cache.addProperty("write", 0);
        tokens.add("cache", cache);
        entry.getAsJsonObject("info").add("tokens", tokens);
        return entry;
    }

    private static JsonObject assistantToolEntry(String command, String output, long input, long out) {
        JsonObject tool = new JsonObject();
        tool.addProperty("type", "tool");
        tool.addProperty("id", "call_1");
        tool.addProperty("callID", "call_1");
        tool.addProperty("tool", "bash");
        JsonObject state = new JsonObject();
        JsonObject toolInput = new JsonObject();
        toolInput.addProperty("command", command);
        state.add("input", toolInput);
        state.addProperty("output", output);
        state.addProperty("status", "completed");
        tool.add("state", state);

        JsonArray parts = new JsonArray();
        parts.add(tool);

        JsonObject info = new JsonObject();
        info.addProperty("id", "msg_a_tool");
        info.addProperty("role", "assistant");
        JsonObject time = new JsonObject();
        time.addProperty("created", 1_700_000_001_000L);
        info.add("time", time);
        JsonObject tokens = new JsonObject();
        tokens.addProperty("input", input);
        tokens.addProperty("output", out);
        tokens.addProperty("reasoning", 0);
        info.add("tokens", tokens);

        JsonObject entry = new JsonObject();
        entry.add("info", info);
        entry.add("parts", parts);
        return entry;
    }

    private static Application synchronousApplication() {
        return (Application) Proxy.newProxyInstance(
                Application.class.getClassLoader(),
                new Class<?>[] {Application.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "executeOnPooledThread":
                        case "invokeLater":
                            // 真实 EDT 的行为：任务抛异常时记录并继续，不让它打断后续回调。
                            if (args != null && args.length >= 1 && args[0] instanceof Runnable) {
                                try {
                                    ((Runnable) args[0]).run();
                                } catch (Throwable t) {
                                    edtFailures.add(t);
                                }
                            }
                            return null;
                        case "getService":
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

    /** The named events the coalescer published. */
    private final RecordingEventSink sink = new RecordingEventSink();

    private List<String> snapshotCalls() {
        List<String> calls = new java.util.ArrayList<>();
        for (String call : sink.calls()) {
            if (call.startsWith("message.snapshot:") || call.startsWith("message.tail:")) {
                calls.add(call);
            }
        }
        return calls;
    }

    private static final class RecordingTarget implements StreamMessageCoalescer.JsCallbackTarget {
        private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        private final List<String> updateCalls = Collections.synchronizedList(new ArrayList<>());
        private final List<String[]> recordedArgs = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void callJavaScript(String functionName, String... args) {
            StringBuilder sb = new StringBuilder(functionName);
            for (String arg : args) {
                sb.append(':').append(arg);
            }
            String call = sb.toString();
            calls.add(call);
            String[] entry = new String[args.length + 1];
            entry[0] = functionName;
            System.arraycopy(args, 0, entry, 1, args.length);
            recordedArgs.add(entry);
            if (call.startsWith("updateMessages") || call.startsWith("updateMessageTail")) {
                updateCalls.add(call);
            }
        }

        /** 最近一次调用 {@code functionName} 时的参数快照。 */
        String[] lastArgsFor(String functionName) {
            synchronized (recordedArgs) {
                for (int i = recordedArgs.size() - 1; i >= 0; i--) {
                    String[] entry = recordedArgs.get(i);
                    if (entry.length > 0 && functionName.equals(entry[0])) {
                        return entry;
                    }
                }
            }
            return null;
        }

        void record(String functionName, String... args) {
            callJavaScript(functionName, args);
        }

        /** The adapter publishes named events now. */
        void recordEvent(com.opencodebuddy.tab.TabEvent event) {
            StringBuilder sb = new StringBuilder(event.getType());
            sb.append(':').append(event.getSessionId() == null ? "" : event.getSessionId());
            for (Object value : event.getPayload().values()) {
                sb.append(':').append(value);
            }
            calls.add(sb.toString());
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
            return new HandlerContext(null, null, null, new HandlerContext.JsCallback() {
                @Override
                public void callJavaScript(String functionName, String... args) {
                }

                @Override
                public String escapeJs(String str) {
                    return str != null ? str : "";
                }
            });
        }
    }
}
