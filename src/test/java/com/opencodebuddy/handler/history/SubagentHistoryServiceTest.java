package com.opencodebuddy.handler.history;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The host forwards core's canonical subagent state; it does not infer one.
 *
 * <p>core owns child-session resolution and the terminal-state rule. These
 * cases lock the forwarding contract: routing identity is added, core's status
 * is preserved verbatim, and a transport failure stays "running" so the next
 * poll can recover.</p>
 */
public class SubagentHistoryServiceTest {

    @Test
    public void forwardsCoreStatusWithoutReinterpretingIt() {
        JsonObject history = JsonParser.parseString("""
                {
                  "success": true,
                  "toolUseId": "call_task",
                  "childSessionID": "ses_child",
                  "status": "running",
                  "completed": false,
                  "messages": []
                }
                """).getAsJsonObject();
        JsonObject envelope = new JsonObject();
        envelope.addProperty("success", true);
        envelope.add("history", history);

        JsonObject response = SubagentHistoryService.canonicalResponse(
                request(), "ses_parent", envelope, null);

        assertEquals("running", string(response, "status"));
        assertFalse(response.get("completed").getAsBoolean());
        assertEquals("ses_child", string(response, "childSessionID"));
        assertEquals("ses_parent", string(response, "sessionId"));
        assertEquals("call_task", string(response, "toolUseId"));
        assertEquals("agent-1", string(response, "agentId"));
        assertEquals("opencode", string(response, "provider"));
    }

    @Test
    public void keepsACompletedChildCompleted() {
        JsonObject history = JsonParser.parseString("""
                {
                  "success": true,
                  "status": "completed",
                  "completed": true,
                  "messages": [{"type": "assistant"}]
                }
                """).getAsJsonObject();
        JsonObject envelope = new JsonObject();
        envelope.add("history", history);

        JsonObject response = SubagentHistoryService.canonicalResponse(
                request(), "ses_parent", envelope, null);

        assertEquals("completed", string(response, "status"));
        assertTrue(response.get("completed").getAsBoolean());
    }

    @Test
    public void aCoreTransportFailureIsRunningNotCompleted() {
        JsonObject response = SubagentHistoryService.canonicalResponse(
                request(), "ses_parent", null, new IllegalStateException("bridge down"));

        assertFalse(response.get("success").getAsBoolean());
        assertEquals("running", string(response, "status"));
        assertFalse(response.get("completed").getAsBoolean());
        assertEquals("bridge down", string(response, "error"));
        assertEquals(0, response.getAsJsonArray("messages").size());
        assertEquals("agent-1", string(response, "agentId"));
    }

    @Test
    public void aMalformedEnvelopeIsRunningNotCompleted() {
        JsonObject response = SubagentHistoryService.canonicalResponse(
                request(), "ses_parent", JsonParser.parseString("{\"success\":true}"), null);

        assertFalse(response.get("success").getAsBoolean());
        assertEquals("running", string(response, "status"));
        assertFalse(response.get("completed").getAsBoolean());
        assertEquals(0, response.getAsJsonArray("messages").size());
    }

    @Test
    public void routingFieldsComeFromTheRequestNotCore() {
        JsonObject history = JsonParser.parseString("""
                {"success": true, "status": "running", "completed": false, "messages": []}
                """).getAsJsonObject();
        JsonObject envelope = new JsonObject();
        envelope.add("history", history);

        JsonObject response = SubagentHistoryService.canonicalResponse(
                request(), "ses_parent", envelope, null);

        assertEquals("agent-1", string(response, "agentId"));
        assertEquals("agents/explore.md", string(response, "agentPath"));
        assertEquals("call_task", string(response, "toolUseId"));
        assertEquals("ses_parent", string(response, "sessionId"));
        assertTrue(response.getAsJsonArray("messages") instanceof JsonArray);
    }

    private static JsonObject request() {
        return JsonParser.parseString("""
                {
                  "sessionId": "ses_parent",
                  "toolUseId": "call_task",
                  "agentId": "agent-1",
                  "agentPath": "agents/explore.md",
                  "provider": "opencode"
                }
                """).getAsJsonObject();
    }

    private static String string(JsonObject object, String field) {
        return object.has(field) && !object.get(field).isJsonNull()
                ? object.get(field).getAsString() : "";
    }
}
