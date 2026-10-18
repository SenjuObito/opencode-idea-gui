package com.opencodebuddy.session;

import com.opencodebuddy.provider.common.MessageCallback;
import com.opencodebuddy.provider.common.SDKResult;
import com.opencodebuddy.provider.opencode.OpenCodeMarkerParser;
import com.google.gson.Gson;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Retry / quota surfacing: the daemon reports "why is this turn spinning" via
 * {@code [STATUS]} (session.status:retry) and reports a late real failure via
 * {@code [TURN_ERROR]}. Both used to be lost host-side — {@code [STATUS]} had no
 * parser branch at all, and the late error was mis-routed to the compaction
 * channel (logged and dropped) — so the only thing a user ever saw was the
 * generic local timeout.
 */
public class OpenCodeRetryStatusRoutingTest {

    private OpenCodeMarkerParser parser;
    private OpenCodeMessageHandler handler;
    private SessionState state;
    private CallbackHandler callbackHandler;

    @Before
    public void setUp() {
        parser = new OpenCodeMarkerParser(new Gson());
        state = new SessionState();
        callbackHandler = new CallbackHandler();
        handler = new OpenCodeMessageHandler(state, callbackHandler);
    }

    // ===== Parser =====

    @Test
    public void statusMarkerRoutesStructuredPayloadAsRetryStatus() {
        RecordingCallback callback = new RecordingCallback();
        OpenCodeMarkerParser.StreamContext context = new OpenCodeMarkerParser.StreamContext();

        String payload = "{\"sessionID\":\"ses_1\",\"type\":\"retry\",\"message\":\"Free usage exceeded\","
                + "\"attempt\":3,\"next\":1792140000000,\"action\":{\"reason\":\"free_tier_limit\","
                + "\"link\":\"https://opencode.ai/go\"}}";
        parser.processOutputLine("[STATUS] " + payload, callback, new SDKResult(), context);

        assertEquals(1, callback.types.size());
        assertEquals("retry_status", callback.types.get(0));
        assertTrue(callback.contents.get(0).contains("free_tier_limit"));
    }

    @Test
    public void statusMarkerIgnoresPreconnectNoise() {
        RecordingCallback callback = new RecordingCallback();
        OpenCodeMarkerParser.StreamContext context = new OpenCodeMarkerParser.StreamContext();

        // The daemon also prints "[STATUS] preconnect ready ..." straight to stdout.
        // Routing it would surface internal warm-up chatter as a user-facing toast.
        parser.processOutputLine("[STATUS] preconnect ready sessionId=ses_1", callback,
                new SDKResult(), context);

        assertTrue(callback.types.isEmpty());
    }

    @Test
    public void turnErrorMarkerRoutesDecodedObject() {
        RecordingCallback callback = new RecordingCallback();
        OpenCodeMarkerParser.StreamContext context = new OpenCodeMarkerParser.StreamContext();

        String payload = "{\"sessionID\":\"ses_1\",\"message\":\"Rate limit exceeded\","
                + "\"source\":\"late-after-timeout\"}";
        parser.processOutputLine("[TURN_ERROR] " + payload, callback, new SDKResult(), context);

        assertEquals(1, callback.types.size());
        assertEquals("turn_error", callback.types.get(0));
        assertTrue(callback.contents.get(0).contains("Rate limit exceeded"));
    }

    @Test
    public void sessionCompactResultMarkerRoutesDecodedObject() {
        RecordingCallback callback = new RecordingCallback();
        OpenCodeMarkerParser.StreamContext context = new OpenCodeMarkerParser.StreamContext();

        String payload = "{\"sessionID\":\"ses_1\",\"success\":true}";
        parser.processOutputLine("[SESSION_COMPACT_RESULT] " + payload, callback, new SDKResult(), context);

        assertEquals(1, callback.types.size());
        assertEquals("session_compact_result", callback.types.get(0));
        assertTrue(callback.contents.get(0).contains("ses_1"));
    }

    // ===== Handler =====

    @Test
    public void retryStatusIsForwardedToTheCallback() {
        final List<String> received = new ArrayList<>();
        callbackHandler.setCallback(new BaseCallback() {
            @Override
            public void onRetryStatus(String jsonContent) {
                received.add(jsonContent);
            }
        });

        handler.onMessage("retry_status", "{\"message\":\"Free usage exceeded\"}");

        assertEquals(1, received.size());
        assertTrue(received.get(0).contains("Free usage exceeded"));
    }

    @Test
    public void lateTurnErrorRewritesTheGenericTimeoutRow() {
        // A previously reported local failure row is corrected by the late
        // authoritative server reason, not duplicated as a second error bubble.
        state.setBusy(false);
        state.setLoading(false);
        state.addMessage(new OpencodeSession.Message(
                OpencodeSession.Message.Type.ERROR, "Local request failure."));
        callbackHandler.setCallback(new BaseCallback());

        handler.onMessage("turn_error",
                "{\"sessionID\":\"ses_1\",\"message\":\"Free usage exceeded, subscribe to Go\"}");

        List<OpencodeSession.Message> messages = state.getMessages();
        assertEquals(1, messages.size());
        assertEquals(OpencodeSession.Message.Type.ERROR, messages.get(0).type);
        assertEquals("Free usage exceeded, subscribe to Go", messages.get(0).content);
        assertEquals("Free usage exceeded, subscribe to Go", state.getError());
        assertFalse(state.isLoading());
    }

    @Test
    public void lateTurnErrorAppendsWhenNoTimeoutRowExists() {
        state.addMessage(new OpencodeSession.Message(
                OpencodeSession.Message.Type.ASSISTANT, "partial answer"));
        callbackHandler.setCallback(new BaseCallback());

        handler.onMessage("turn_error", "{\"message\":\"Rate limit exceeded\"}");

        List<OpencodeSession.Message> messages = state.getMessages();
        assertEquals(2, messages.size());
        assertEquals(OpencodeSession.Message.Type.ERROR, messages.get(1).type);
        assertEquals("Rate limit exceeded", messages.get(1).content);
    }

    // ===== SessionState =====

    @Test
    public void replaceLastErrorMessageOnlyTouchesATrailingErrorRow() {
        state.addMessage(new OpencodeSession.Message(OpencodeSession.Message.Type.USER, "hi"));
        state.addMessage(new OpencodeSession.Message(OpencodeSession.Message.Type.ASSISTANT, "answer"));

        assertFalse(state.replaceLastErrorMessage("boom"));
        assertEquals(3, state.getMessages().size());
        assertEquals(OpencodeSession.Message.Type.ERROR, state.getMessages().get(2).type);

        assertTrue(state.replaceLastErrorMessage("boom 2"));
        assertEquals(3, state.getMessages().size());
        assertEquals("boom 2", state.getMessages().get(2).content);
    }

    @Test
    public void replaceLastErrorMessageIgnoresBlankInput() {
        state.addMessage(new OpencodeSession.Message(OpencodeSession.Message.Type.ERROR, "original"));

        assertFalse(state.replaceLastErrorMessage("   "));
        assertEquals("original", state.getMessages().get(0).content);
    }

    // ===== Helpers =====

    private static final class RecordingCallback implements MessageCallback {
        final List<String> types = new ArrayList<>();
        final List<String> contents = new ArrayList<>();

        @Override
        public void onMessage(String type, String content) {
            types.add(type);
            contents.add(content);
        }

        @Override
        public void onError(String error) {
        }

        @Override
        public void onComplete(SDKResult result) {
        }
    }

    /** No-op callback; tests override only the method they assert on. */
    private static class BaseCallback implements OpencodeSession.SessionCallback {
        @Override
        public void onMessageUpdate(List<OpencodeSession.Message> messages) {
        }

        @Override
        public void onStateChange(boolean busy, boolean loading, String error) {
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
