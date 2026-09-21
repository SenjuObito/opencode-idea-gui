package com.opencodebuddy.session;

import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class OpenCodeMessageHandlerTest {

    private SessionState state;
    private CallbackHandler callbackHandler;
    private OpenCodeMessageHandler handler;

    @Before
    public void setUp() {
        state = new SessionState();
        callbackHandler = new CallbackHandler();
        handler = new OpenCodeMessageHandler(state, callbackHandler);
    }

    @Test
    public void testTodoUpdatedDispatchesToNotifyTodoUpdated() {
        AtomicReference<String> receivedTodoJson = new AtomicReference<>();
        callbackHandler.setCallback(new OpencodeSession.SessionCallback() {
            @Override
            public void onTodoUpdated(String jsonContent) {
                receivedTodoJson.set(jsonContent);
            }
            @Override
            public void onMessageUpdate(java.util.List<OpencodeSession.Message> messages) {}
            @Override
            public void onStateChange(boolean busy, boolean loading, String error) {}
            @Override
            public void onStatusMessage(String message) {}
            @Override
            public void onSessionIdReceived(String sessionId) {}
            @Override
            public void onSessionTitleReceived(String sessionId, String title) {}
            @Override
            public void onPermissionRequested(com.opencodebuddy.permission.PermissionRequest request) {}
            @Override
            public void onThinkingStatusChanged(boolean isThinking) {}
            @Override
            public void onSlashCommandsReceived(java.util.List<String> slashCommands) {}
            @Override
            public void onNodeLog(String log) {}
            @Override
            public void onSummaryReceived(String summary) {}
        });

        String payload = "{\"sessionID\":\"ses_123\",\"todos\":[{\"id\":\"1\",\"content\":\"Task 1\",\"status\":\"completed\"}]}";
        handler.onMessage("todo_updated", payload);

        assertEquals(payload, receivedTodoJson.get());
    }

    @Test
    public void testUserMessageResetsStreamingAccumulatorAcrossTurns() {
        // Turn 1 Assistant response
        String assistantJson = "{\"role\":\"assistant\",\"content\":\"Turn 1 answer\"}";
        handler.onMessage("assistant", assistantJson);
        assertEquals(1, state.getMessages().size());
        assertEquals("Turn 1 answer", state.getMessages().get(0).content);

        // User starts Turn 2
        String userJson = "{\"role\":\"user\",\"content\":\"Turn 2 prompt\"}";
        handler.onMessage("user", userJson);
        assertEquals(2, state.getMessages().size());
        assertEquals("Turn 2 prompt", state.getMessages().get(1).content);

        // Turn 2 Assistant response should be appended as a new message, not overwriting Turn 1
        String assistantJson2 = "{\"role\":\"assistant\",\"content\":\"Turn 2 answer\"}";
        handler.onMessage("assistant", assistantJson2);
        assertEquals(3, state.getMessages().size());
        assertEquals("Turn 1 answer", state.getMessages().get(0).content);
        assertEquals("Turn 2 prompt", state.getMessages().get(1).content);
        assertEquals("Turn 2 answer", state.getMessages().get(2).content);
    }

    @Test
    public void testBlockResetResetsAccumulator() {
        handler.onMessage("block_reset", "");
        String assistantJson = "{\"role\":\"assistant\",\"content\":\"Post-reset answer\"}";
        handler.onMessage("assistant", assistantJson);
        assertEquals(1, state.getMessages().size());
        assertEquals("Post-reset answer", state.getMessages().get(0).content);
    }

    @Test
    public void testOnErrorAddsErrorMessageToState() {
        handler.onError("API error: rate limited");
        assertEquals(1, state.getMessages().size());
        assertEquals(OpencodeSession.Message.Type.ERROR, state.getMessages().get(0).type);
        assertEquals("API error: rate limited", state.getMessages().get(0).content);
        assertFalse(state.isBusy());
        assertFalse(state.isLoading());
    }
}
