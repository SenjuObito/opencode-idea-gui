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
    public void testSubagentStatusDispatchesToNotifySubagentStatus() {
        AtomicReference<String> receivedStatusJson = new AtomicReference<>();
        callbackHandler.setCallback(new OpencodeSession.SessionCallback() {
            @Override
            public void onSubagentStatus(String jsonContent) {
                receivedStatusJson.set(jsonContent);
            }
            @Override
            public void onMessageUpdate(java.util.List<OpencodeSession.Message> messages) {}
            @Override
            public void onStateChange(boolean busy, boolean loading, String error) {}
            @Override
            public void onSessionIdReceived(String sessionId) {}
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

        String payload = "{\"parentSessionId\":\"ses_parent\",\"toolUseId\":\"call_1\","
                + "\"childSessionId\":\"ses_child\",\"status\":\"completed\"}";
        handler.onMessage("subagent_status", payload);

        assertEquals(payload, receivedStatusJson.get());
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

    @Test
    public void testStreamingOrder_Thinking_ThenTools_ThenFormalText() {
        handler.onMessage("stream_start", "");
        handler.onMessage("thinking_delta", "正在分析环境配置...");
        handler.onMessage("thinking_delta", "需要搜索 .env 相关文件。");

        // Tool 1: glob search
        String tool1 = "{\"role\":\"assistant\",\"content\":\"\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"id\":\"search_1\",\"name\":\"glob\",\"input\":{\"pattern\":\"*.env*\"}}]}}";
        handler.onMessage("assistant", tool1);

        // Tool 1 result
        String tool1Result = "{\"role\":\"user\",\"content\":\"[tool_result]\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"search_1\",\"content\":\"found .env.prd\"}]}}";
        handler.onMessage("user", tool1Result);

        // Tool 2: read file
        String tool2 = "{\"role\":\"assistant\",\"content\":\"\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"id\":\"read_1\",\"name\":\"read\",\"input\":{\"path\":\".env.prd\"}}]}}";
        handler.onMessage("assistant", tool2);

        // Tool 2 result
        String tool2Result = "{\"role\":\"user\",\"content\":\"[tool_result]\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"read_1\",\"content\":\"PORT=8080\"}]}}";
        handler.onMessage("user", tool2Result);

        // Post-tool formal text response arrives via content_delta
        handler.onMessage("content_delta", "根据搜索和读取结果，");
        handler.onMessage("content_delta", "服务运行在 8080 端口。");
        handler.onMessage("stream_end", "");

        // Find assistant message
        OpencodeSession.Message assistantMsg = null;
        for (OpencodeSession.Message m : state.getMessages()) {
            if (m.type == OpencodeSession.Message.Type.ASSISTANT) {
                assistantMsg = m;
            }
        }
        assertNotNull("Assistant message must exist", assistantMsg);
        assertNotNull("Raw must exist", assistantMsg.raw);

        com.google.gson.JsonArray contentArray = assistantMsg.raw.getAsJsonObject("message").getAsJsonArray("content");
        assertEquals("Should have 4 blocks: thinking, tool1, tool2, text", 4, contentArray.size());

        // Verify block order strictly matches server streaming sequence
        com.google.gson.JsonObject block0 = contentArray.get(0).getAsJsonObject();
        assertEquals("thinking", block0.get("type").getAsString());
        assertEquals("正在分析环境配置...需要搜索 .env 相关文件。", block0.get("thinking").getAsString());

        com.google.gson.JsonObject block1 = contentArray.get(1).getAsJsonObject();
        assertEquals("tool_use", block1.get("type").getAsString());
        assertEquals("search_1", block1.get("id").getAsString());

        com.google.gson.JsonObject block2 = contentArray.get(2).getAsJsonObject();
        assertEquals("tool_use", block2.get("type").getAsString());
        assertEquals("read_1", block2.get("id").getAsString());

        com.google.gson.JsonObject block3 = contentArray.get(3).getAsJsonObject();
        assertEquals("text", block3.get("type").getAsString());
        assertEquals("根据搜索和读取结果，服务运行在 8080 端口。", block3.get("text").getAsString());
    }

    @Test
    public void testStreamingOrder_LeadText_Thinking_Tools_FormalText() {
        handler.onMessage("stream_start", "");
        handler.onMessage("thinking_delta", "正在分析...");
        handler.onMessage("content_delta", "我来为您查询配置。");

        // Tool call
        String tool1 = "{\"role\":\"assistant\",\"content\":\"\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"id\":\"search_1\",\"name\":\"glob\",\"input\":{\"pattern\":\"*.env*\"}}]}}";
        handler.onMessage("assistant", tool1);

        String tool1Result = "{\"role\":\"user\",\"content\":\"[tool_result]\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"search_1\",\"content\":\"found .env.prd\"}]}}";
        handler.onMessage("user", tool1Result);

        // Formal response after tool
        handler.onMessage("content_delta", "查询完毕，配置在 .env.prd 中。");
        handler.onMessage("stream_end", "");

        OpencodeSession.Message assistantMsg = null;
        for (OpencodeSession.Message m : state.getMessages()) {
            if (m.type == OpencodeSession.Message.Type.ASSISTANT) {
                assistantMsg = m;
            }
        }
        assertNotNull(assistantMsg);

        com.google.gson.JsonArray contentArray = assistantMsg.raw.getAsJsonObject("message").getAsJsonArray("content");
        assertEquals("Should have 4 blocks: thinking, lead-in text, tool_use, formal text", 4, contentArray.size());

        assertEquals("thinking", contentArray.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("text", contentArray.get(1).getAsJsonObject().get("type").getAsString());
        assertEquals("我来为您查询配置。", contentArray.get(1).getAsJsonObject().get("text").getAsString());

        assertEquals("tool_use", contentArray.get(2).getAsJsonObject().get("type").getAsString());

        assertEquals("text", contentArray.get(3).getAsJsonObject().get("type").getAsString());
        assertEquals("查询完毕，配置在 .env.prd 中。", contentArray.get(3).getAsJsonObject().get("text").getAsString());
    }

    @Test
    public void testMultiSessionSegmentIsolation() {
        SessionState state2 = new SessionState();
        CallbackHandler callbackHandler2 = new CallbackHandler();
        OpenCodeMessageHandler handler2 = new OpenCodeMessageHandler(state2, callbackHandler2);

        // Session 1: thinking + tool
        handler.onMessage("stream_start", "");
        handler.onMessage("thinking_delta", "Session 1 thinking...");
        String tool1 = "{\"role\":\"assistant\",\"content\":\"\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"id\":\"s1_tool\",\"name\":\"glob\",\"input\":{}}]}}";
        handler.onMessage("assistant", tool1);

        // Session 2: pure text stream
        handler2.onMessage("stream_start", "");
        handler2.onMessage("content_delta", "Session 2 response.");
        handler2.onMessage("stream_end", "");

        // Session 1 receives tool result then post-tool text
        String tool1Result = "{\"role\":\"user\",\"content\":\"[tool_result]\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"s1_tool\",\"content\":\"done\"}]}}";
        handler.onMessage("user", tool1Result);
        handler.onMessage("content_delta", "Session 1 post-tool response.");
        handler.onMessage("stream_end", "");

        // Verify Session 1 blocks
        OpencodeSession.Message s1Msg = null;
        for (OpencodeSession.Message m : state.getMessages()) {
            if (m.type == OpencodeSession.Message.Type.ASSISTANT) {
                s1Msg = m;
            }
        }
        assertNotNull(s1Msg);
        com.google.gson.JsonArray s1Content = s1Msg.raw.getAsJsonObject("message").getAsJsonArray("content");
        assertEquals(3, s1Content.size());
        assertEquals("thinking", s1Content.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("tool_use", s1Content.get(1).getAsJsonObject().get("type").getAsString());
        assertEquals("text", s1Content.get(2).getAsJsonObject().get("type").getAsString());
        assertEquals("Session 1 post-tool response.", s1Content.get(2).getAsJsonObject().get("text").getAsString());

        // Verify Session 2 blocks
        OpencodeSession.Message s2Msg = null;
        for (OpencodeSession.Message m : state2.getMessages()) {
            if (m.type == OpencodeSession.Message.Type.ASSISTANT) {
                s2Msg = m;
            }
        }
        assertNotNull(s2Msg);
        com.google.gson.JsonArray s2Content = s2Msg.raw.getAsJsonObject("message").getAsJsonArray("content");
        assertEquals(1, s2Content.size());
        assertEquals("text", s2Content.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("Session 2 response.", s2Content.get(0).getAsJsonObject().get("text").getAsString());
    }
}

