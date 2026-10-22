package com.opencodebuddy.handler;

import com.opencodebuddy.handler.core.HandlerContext;
import com.opencodebuddy.prompt.PromptBroker;
import com.opencodebuddy.permission.PermissionRequest;
import com.google.gson.JsonObject;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 问题弹窗 / 权限弹窗的身份归属测试。
 *
 * <p>弹窗「不弹」或「弹到别的会话」都源于同一处：显示时拿不到请求自己的
 * sessionId，就顺手借当前活跃会话的。本用例守住规则：</p>
 *
 * <ul>
 *   <li>请求自带 sessionId → 一律用它，哪怕调用方传了另一个 target；</li>
 *   <li>请求没有 sessionId → 可以用调用方明确传来的 target，但绝不猜活跃会话；</li>
 *   <li>两者都没有 → 仍然弹窗（前端按 permission id 路由回复），只是 payload
 *       里不带 sessionId，让缺陷在上层暴露而不是被猜出来。</li>
 * </ul>
 */
public class PermissionDialogIdentityTest {

    private RecordingJsCallback js;
    private PermissionHandler handler;

    @Before
    public void setUp() {
        js = new RecordingJsCallback();
        handler = new PermissionHandler(contextStub(js));
    }

    @Test
    public void permissionPromptKeepsItsOwnSessionIdEvenWhenAnotherSessionIsTheTarget() {
        // 会话 B 的请求，借道活跃会话 A 的调用路径进来：身份必须留在 B。
        PermissionRequest request = new PermissionRequest(
                "per_b", "ses_b", "Bash", Map.of("command", "ls"), null, null);

        handler.showPermissionDialog(request, "ses_a");

        JsonObject payload = lastPermissionPayload();
        assertEquals("ses_b", payload.get("sessionId").getAsString());
        assertEquals("per_b", payload.get("channelId").getAsString());
    }

    @Test
    public void permissionPromptFallsBackToTheCallersTargetWhenRequestHasNone() {
        // 老构造器（无 sessionId）+ 调用方明确给出 target：用 target 是合法的。
        PermissionRequest request = new PermissionRequest(
                "per_c", "Bash", Map.of("command", "ls"), null);

        handler.showPermissionDialog(request, "ses_c");

        JsonObject payload = lastPermissionPayload();
        assertEquals("ses_c", payload.get("sessionId").getAsString());
    }

    @Test
    public void permissionPromptRefusesToBorrowTheActiveSession() {
        // 没有请求身份、也没有调用方 target：绝不允许填当前活跃会话。
        PermissionRequest request = new PermissionRequest(
                "per_anon", "Bash", Map.of("command", "ls"), null);

        handler.showPermissionDialog(request, null);

        // 弹窗仍然要弹（回复按 permission id 走注册表，能回到正确的会话），
        // 丢弹窗才是「已取消却没有对话框」那个 bug。
        JsonObject payload = lastPermissionPayload();
        assertNotNull("permission dialog must still be shown", payload);
        assertFalse("must not invent a session id",
                payload.has("sessionId") && !payload.get("sessionId").isJsonNull());
    }

    @Test
    public void twoSessionsPermissionPromptsDoNotOverwriteEachOther() {
        handler.showPermissionDialog(
                new PermissionRequest("per_1", "ses_1", "Bash", Map.of(), null, null), "ses_other");
        handler.showPermissionDialog(
                new PermissionRequest("per_2", "ses_2", "Read", Map.of(), null, null), "ses_other");

        List<JsonObject> payloads = permissionPayloads();
        assertEquals(2, payloads.size());
        assertEquals("ses_1", payloads.get(0).get("sessionId").getAsString());
        assertEquals("per_1", payloads.get(0).get("channelId").getAsString());
        assertEquals("ses_2", payloads.get(1).get("sessionId").getAsString());
        assertEquals("per_2", payloads.get(1).get("channelId").getAsString());
    }

    @Test
    public void questionPromptKeepsItsOwnSessionIdAndRecordsItForTheReply() throws Exception {
        handler.onQuestionRequested(
                "{\"requestId\":\"q_b\",\"sessionId\":\"ses_q_b\",\"tool\":\"ask\",\"questions\":[]}",
                "ses_q_a");

        JsonObject payload = lastQuestionPayload();
        assertEquals("ses_q_b", payload.get("sessionId").getAsString());
        // 回复时用这条记录里的 sessionId 回帖，与当前活跃会话无关。
        assertEquals("ses_q_b", pendingQuestionSessionId("q_b"));
    }

    @Test
    public void questionPromptFallsBackToTheCallersTargetWhenRequestHasNone() throws Exception {
        handler.onQuestionRequested(
                "{\"requestId\":\"q_c\",\"tool\":\"ask\",\"questions\":[]}", "ses_q_c");

        assertEquals("ses_q_c", lastQuestionPayload().get("sessionId").getAsString());
        assertEquals("ses_q_c", pendingQuestionSessionId("q_c"));
    }

    @Test
    public void questionPromptRefusesToBorrowTheActiveSession() throws Exception {
        handler.onQuestionRequested(
                "{\"requestId\":\"q_anon\",\"tool\":\"ask\",\"questions\":[]}", null);

        JsonObject payload = lastQuestionPayload();
        assertNotNull("question dialog must still be shown", payload);
        assertFalse("must not invent a session id",
                payload.has("sessionId") && !payload.get("sessionId").isJsonNull());
        assertNull("pending entry must carry no session either", pendingQuestionSessionId("q_anon"));
    }

    @Test
    public void closedQuestionIsDroppedFromThePendingTable() throws Exception {
        handler.onQuestionRequested(
                "{\"requestId\":\"q_closed\",\"sessionId\":\"ses_q\",\"tool\":\"ask\",\"questions\":[]}", null);
        assertNotNull(pendingQuestionSessionId("q_closed"));

        handler.onPromptClosed("question", "{\"requestId\":\"q_closed\"}");

        assertNull(pendingQuestionSessionId("q_closed"));
    }

    // ===== helpers =====

    private JsonObject lastPermissionPayload() {
        List<JsonObject> payloads = permissionPayloads();
        assertFalse("no permission dialog was shown", payloads.isEmpty());
        return payloads.get(payloads.size() - 1);
    }

    private List<JsonObject> permissionPayloads() {
        return js.payloadsFor("permission.requested");
    }

    private JsonObject lastQuestionPayload() {
        List<JsonObject> payloads = js.payloadsFor("question.requested");
        assertFalse("no question dialog was shown", payloads.isEmpty());
        return payloads.get(payloads.size() - 1);
    }

    private String pendingQuestionSessionId(String requestId) throws Exception {
        Field brokerField = PermissionHandler.class.getDeclaredField("prompts");
        brokerField.setAccessible(true);
        PromptBroker broker = (PromptBroker) brokerField.get(handler);
        Field f = PromptBroker.class.getDeclaredField("questions");
        f.setAccessible(true);
        Map<?, ?> pending = (Map<?, ?>) f.get(broker);
        Object entry = pending.get(requestId);
        if (entry == null) {
            return null;
        }
        Field sessionField = entry.getClass().getDeclaredField("sessionId");
        sessionField.setAccessible(true);
        return (String) sessionField.get(entry);
    }

    private static HandlerContext contextStub(RecordingJsCallback js) {
        return new HandlerContext(
                null,
                null,
                null,
                new HandlerContext.JsCallback() {
                    @Override
                    public void callJavaScript(String functionName, String... args) {
                        js.record(functionName, args);
                    }

                    @Override
                    public String escapeJs(String str) {
                        return str != null ? str : "";
                    }

                    @Override
                    public void publishEvent(com.opencodebuddy.tab.TabEvent event) {
                        js.recordEvent(event);
                    }
                }
        );
    }

    /** Records (function, parsed JSON payload) pairs pushed to the webview. */
    private static final class RecordingJsCallback {
        private final List<String> functions = new ArrayList<>();
        private final Map<String, List<JsonObject>> payloads = new HashMap<>();

        /** The named events the handler published, by semantic type. */
        void recordEvent(com.opencodebuddy.tab.TabEvent event) {
            JsonObject payload = event.toJson().getAsJsonObject("payload");
            payloads.computeIfAbsent(event.getType(), k -> new ArrayList<>()).add(payload);
        }

        void record(String functionName, String... args) {
            functions.add(functionName);
            if (args.length > 0 && args[0] != null && !args[0].isBlank()) {
                try {
                    JsonObject parsed = com.google.gson.JsonParser.parseString(args[0]).getAsJsonObject();
                    payloads.computeIfAbsent(functionName, k -> new ArrayList<>()).add(parsed);
                } catch (Exception ignored) {
                    // Non-JSON argument (e.g. a boolean flag) — nothing to record.
                }
            }
        }

        List<JsonObject> payloadsFor(String functionName) {
            return payloads.getOrDefault(functionName, List.of());
        }
    }
}
