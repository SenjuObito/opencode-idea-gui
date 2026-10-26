package com.opencodebuddy.handler;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.opencodebuddy.handler.core.HandlerContext;
import com.opencodebuddy.provider.common.DaemonBridge;
import com.opencodebuddy.provider.opencode.OpenCodeSDKBridge;
import com.opencodebuddy.session.OpencodeSession;
import com.opencodebuddy.tab.TabCommand;
import com.opencodebuddy.tab.TabEvent;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The session commands are tab-scoped: the router hands each one to the tab
 * that sent it, and the handler must act on that tab's session only. These
 * tests pin that ownership, including the case where another tab is the active
 * one and the case where the tab has switched sessions since the command was
 * queued.
 */
public class TabSessionCommandHandlerTest {

    private static OpencodeSession session(String sessionId) {
        OpencodeSession session = new OpencodeSession(null, null);
        session.setSessionInfo(sessionId, "/tmp/project");
        return session;
    }

    private static HandlerContext context(
            RecordingBridge bridge,
            RecordingJsCallback jsCallback,
            OpencodeSession activeSession,
            Map<String, OpencodeSession> tabSessions
    ) {
        HandlerContext context = new HandlerContext(null, bridge, null, jsCallback);
        context.setSession(activeSession);
        context.setTabSessionLookup(tabSessions::get);
        return context;
    }

    private static TabCommand command(String tabId, String type, String sessionId) {
        JsonObject payload = new JsonObject();
        payload.addProperty("tabId", tabId);
        if (sessionId != null) {
            payload.addProperty("sessionId", sessionId);
        }
        return TabCommand.of(tabId, type, "req-1", payload);
    }

    @Test
    public void shareUsesTheCommandTabEvenWhenAnotherTabIsActive() {
        RecordingBridge bridge = new RecordingBridge();
        bridge.response = "{\"success\":true,\"share\":{\"url\":\"https://opencode.ai/share/ses_a\"}}";
        RecordingJsCallback jsCallback = new RecordingJsCallback();
        OpencodeSession tabA = session("ses_a");
        OpencodeSession tabB = session("ses_b");
        Map<String, OpencodeSession> tabs = new HashMap<>();
        tabs.put("tab-a", tabA);
        tabs.put("tab-b", tabB);

        TabSessionCommandHandler handler = new TabSessionCommandHandler(
                context(bridge, jsCallback, tabB, tabs), (targetTabId, newSessionId) -> { });

        handler.share(command("tab-a", "session.share", "ses_a"));

        assertEquals(List.of("opencode.shareSession"), bridge.methods);
        assertEquals("ses_a", bridge.params.get(0).get("sessionId").getAsString());
        TabEvent event = jsCallback.only("session.share.done");
        assertEquals("https://opencode.ai/share/ses_a", event.getPayload().get("data"));
        assertEquals("tab-a", event.getTabId());
        assertEquals("ses_a", event.getSessionId());
    }

    @Test
    public void shareFailureLandsOnTheSendingTab() {
        RecordingBridge bridge = new RecordingBridge();
        bridge.error = "Share disabled";
        RecordingJsCallback jsCallback = new RecordingJsCallback();
        Map<String, OpencodeSession> tabs = Map.of("tab-a", session("ses_a"));

        TabSessionCommandHandler handler = new TabSessionCommandHandler(
                context(bridge, jsCallback, null, tabs), (targetTabId, newSessionId) -> { });

        handler.share(command("tab-a", "session.share", "ses_a"));

        TabEvent event = jsCallback.only("session.share.failed");
        assertEquals("Share disabled", event.getPayload().get("data"));
        assertEquals("tab-a", event.getTabId());
        assertEquals("ses_a", event.getSessionId());
    }

    @Test
    public void refusesWhenTheTabHasSwitchedSessions() {
        RecordingBridge bridge = new RecordingBridge();
        RecordingJsCallback jsCallback = new RecordingJsCallback();
        Map<String, OpencodeSession> tabs = Map.of("tab-a", session("ses_new"));

        TabSessionCommandHandler handler = new TabSessionCommandHandler(
                context(bridge, jsCallback, null, tabs), (targetTabId, newSessionId) -> { });

        // The command was queued while the tab still showed ses_old.
        handler.share(command("tab-a", "session.share", "ses_old"));

        assertTrue(bridge.methods.isEmpty());
        TabEvent event = jsCallback.only("session.share.failed");
        assertEquals("the tab now shows another session", event.getPayload().get("data"));
    }

    @Test
    public void refusesWhenTheTabHasNoSession() {
        RecordingBridge bridge = new RecordingBridge();
        RecordingJsCallback jsCallback = new RecordingJsCallback();

        TabSessionCommandHandler handler = new TabSessionCommandHandler(
                context(bridge, jsCallback, null, Map.of()), (targetTabId, newSessionId) -> { });

        handler.share(command("tab-a", "session.share", null));

        assertTrue(bridge.methods.isEmpty());
        TabEvent event = jsCallback.only("session.share.failed");
        assertEquals("the tab has no session", event.getPayload().get("data"));
    }

    @Test
    public void unshareSucceedsOnTheSendingTab() {
        RecordingBridge bridge = new RecordingBridge();
        RecordingJsCallback jsCallback = new RecordingJsCallback();
        Map<String, OpencodeSession> tabs = Map.of("tab-a", session("ses_a"));

        TabSessionCommandHandler handler = new TabSessionCommandHandler(
                context(bridge, jsCallback, null, tabs), (targetTabId, newSessionId) -> { });

        handler.unshare(command("tab-a", "session.unshare", "ses_a"));

        assertEquals(List.of("opencode.unshareSession"), bridge.methods);
        assertEquals("ses_a", bridge.params.get(0).get("sessionId").getAsString());
        TabEvent event = jsCallback.only("session.unshare.done");
        assertEquals("tab-a", event.getTabId());
        assertEquals("ses_a", event.getSessionId());
    }

    @Test
    public void compactFailureIsReportedExactlyOnce() {
        RecordingBridge bridge = new RecordingBridge();
        bridge.error = "Nothing to compact yet";
        RecordingJsCallback jsCallback = new RecordingJsCallback();
        Map<String, OpencodeSession> tabs = Map.of("tab-a", session("ses_a"));

        TabSessionCommandHandler handler = new TabSessionCommandHandler(
                context(bridge, jsCallback, null, tabs), (targetTabId, newSessionId) -> { });

        handler.compact(command("tab-a", "session.compact", "ses_a"));

        assertEquals(1, jsCallback.events.size());
        TabEvent event = jsCallback.only("session.compact.failed");
        assertEquals(List.of("ses_a", "Nothing to compact yet"), event.getPayload().get("args"));
        assertEquals("tab-a", event.getTabId());
        assertEquals("ses_a", event.getSessionId());
    }

    @Test
    public void revertPublishesStateOnTheCommandTab() {
        RecordingBridge bridge = new RecordingBridge();
        bridge.response = "{\"session\":{\"revert\":{\"messageID\":\"msg_1\"}}}";
        RecordingJsCallback jsCallback = new RecordingJsCallback();
        OpencodeSession tabA = session("ses_a");
        Map<String, OpencodeSession> tabs = Map.of("tab-a", tabA);

        TabSessionCommandHandler handler = new TabSessionCommandHandler(
                context(bridge, jsCallback, null, tabs), (targetTabId, newSessionId) -> { });

        JsonObject payload = new JsonObject();
        payload.addProperty("tabId", "tab-a");
        payload.addProperty("sessionId", "ses_a");
        payload.addProperty("messageId", "msg_1");
        handler.revert(TabCommand.of("tab-a", "session.revert", "req-1", payload));

        assertEquals(List.of("opencode.revert"), bridge.methods);
        assertEquals("msg_1", bridge.params.get(0).get("messageID").getAsString());
        TabEvent event = jsCallback.only("revert.changed");
        assertEquals("tab-a", event.getTabId());
        assertEquals("ses_a", event.getSessionId());
        JsonObject state = (JsonObject) event.getPayload().get("state");
        assertTrue(state.get("hasRevert").getAsBoolean());
        assertEquals("msg_1", state.get("messageId").getAsString());
        assertNotNull(tabA.getRevertState());
    }

    @Test
    public void forkUsesTheRoutedTabNotThePayloadSource() {
        RecordingBridge bridge = new RecordingBridge();
        bridge.response = "{\"newSessionId\":\"ses_new\"}";
        RecordingJsCallback jsCallback = new RecordingJsCallback();
        AtomicReference<String> bound = new AtomicReference<>();
        OpencodeSession tabA = session("ses_a");
        Map<String, OpencodeSession> tabs = Map.of("tab-a", tabA, "tab-b", session("ses_b"));

        TabSessionCommandHandler handler = new TabSessionCommandHandler(
                context(bridge, jsCallback, null, tabs),
                (targetTabId, newSessionId) -> bound.set(targetTabId + ":" + newSessionId));

        JsonObject payload = new JsonObject();
        payload.addProperty("tabId", "tab-a");
        payload.addProperty("sessionId", "ses_a");
        // A stale payload must not redirect the fork to another conversation.
        payload.addProperty("sourceTabId", "tab-b");
        payload.addProperty("targetTabId", "tab-fork");
        payload.addProperty("messageId", "msg_1");

        handler.fork(TabCommand.of("tab-a", "session.fork", "req-1", payload));

        assertEquals(List.of("opencode.fork"), bridge.methods);
        assertEquals("ses_a", bridge.params.get(0).get("sessionId").getAsString());
        assertEquals("msg_1", bridge.params.get(0).get("messageID").getAsString());
        assertEquals("tab-fork:ses_new", bound.get());
        TabEvent event = jsCallback.only("session.fork.bound");
        assertEquals("tab-fork", event.getTabId());
        assertEquals("ses_new", event.getSessionId());
    }

    @Test
    public void forkWithoutASourceSessionFails() {
        RecordingBridge bridge = new RecordingBridge();
        RecordingJsCallback jsCallback = new RecordingJsCallback();

        TabSessionCommandHandler handler = new TabSessionCommandHandler(
                context(bridge, jsCallback, null, Map.of()), (targetTabId, newSessionId) -> { });

        JsonObject payload = new JsonObject();
        payload.addProperty("targetTabId", "tab-fork");
        handler.fork(TabCommand.of("tab-empty", "session.fork", "req-1", payload));

        assertTrue(bridge.methods.isEmpty());
        TabEvent event = jsCallback.only("session.fork.failed");
        assertEquals("the source tab has no session to fork", event.getPayload().get("data"));
        assertEquals("tab-empty", event.getTabId());
    }

    private static final class RecordingBridge extends OpenCodeSDKBridge {
        final List<String> methods = new ArrayList<>();
        final List<JsonObject> params = new ArrayList<>();
        String response = "{\"success\":true}";
        String error;

        RecordingBridge() {
            super(null, new Gson());
        }

        @Override
        public CompletableFuture<Boolean> request(
                String method,
                JsonObject params,
                DaemonBridge.DaemonOutputCallback callback
        ) {
            methods.add(method);
            this.params.add(params.deepCopy());
            if (error != null) {
                callback.onError(error);
                callback.onComplete(false);
                return CompletableFuture.completedFuture(false);
            }
            if (response != null) {
                callback.onLine(response);
            }
            callback.onComplete(true);
            return CompletableFuture.completedFuture(true);
        }
    }

    private static final class RecordingJsCallback implements HandlerContext.JsCallback {
        final List<TabEvent> events = new ArrayList<>();

        TabEvent only(String type) {
            TabEvent found = null;
            for (TabEvent event : events) {
                if (type.equals(event.getType())) {
                    assertFalse("duplicate " + type, found != null);
                    found = event;
                }
            }
            assertNotNull("missing " + type + " in " + events, found);
            return found;
        }

        @Override
        public void callJavaScript(String functionName, String... args) {
        }

        @Override
        public void publishEvent(TabEvent event) {
            events.add(event);
        }

        @Override
        public String escapeJs(String str) {
            return str != null ? str : "";
        }
    }
}
