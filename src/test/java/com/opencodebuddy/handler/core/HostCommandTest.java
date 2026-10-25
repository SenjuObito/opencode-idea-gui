package com.opencodebuddy.handler.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class HostCommandTest {

    private static String envelope(String type, String tabId, String requestId, String sessionId, String payload) {
        JsonObject root = new JsonObject();
        root.addProperty("v", 2);
        root.addProperty("type", type);
        if (tabId != null) {
            root.addProperty("tabId", tabId);
        }
        if (requestId != null) {
            root.addProperty("requestId", requestId);
        }
        if (sessionId != null) {
            root.addProperty("sessionId", sessionId);
        }
        root.add("payload", JsonParser.parseString(payload == null ? "{}" : payload));
        return root.toString();
    }

    private static HostCommand.Invalid expectInvalid(String content, String expectedCode) {
        try {
            HostCommand.parse(content, "session.send");
            fail("expected the envelope to be rejected");
            return null;
        } catch (HostCommand.Invalid invalid) {
            assertEquals(expectedCode, invalid.getCode());
            return invalid;
        }
    }

    @Test
    public void acceptsAValidEnvelopeAndMergesIdentityIntoThePayload() throws Exception {
        HostCommand command = HostCommand.parse(
                envelope("session.send", "tab-1", "req-1", "ses_a", "{\"text\":\"hi\"}"),
                "session.send");

        assertEquals("session.send", command.getType());
        assertEquals("tab-1", command.getTabId());
        assertEquals("ses_a", command.getSessionId());
        assertEquals("req-1", command.getCommandId());

        JsonObject payload = JsonParser.parseString(command.getPayloadJson()).getAsJsonObject();
        assertEquals("hi", payload.get("text").getAsString());
        assertEquals("tab-1", payload.get("tabId").getAsString());
        assertEquals("ses_a", payload.get("sessionId").getAsString());
    }

    @Test
    public void aTabOnlyCommandHasNoSession() throws Exception {
        HostCommand command = HostCommand.parse(
                envelope("session.send", "tab-1", "req-1", null, "{}"),
                "session.send");

        assertNull(command.getSessionId());
        JsonObject payload = JsonParser.parseString(command.getPayloadJson()).getAsJsonObject();
        assertTrue(payload.has("tabId"));
        assertTrue(!payload.has("sessionId"));
    }

    @Test
    public void theEnvelopeRoutingIdentityWinsOverThePayload() throws Exception {
        HostCommand command = HostCommand.parse(
                envelope("session.send", "tab-1", "req-1", "ses_a",
                        "{\"tabId\":\"tab-other\",\"sessionId\":\"ses_other\",\"text\":\"hi\"}"),
                "session.send");

        JsonObject payload = JsonParser.parseString(command.getPayloadJson()).getAsJsonObject();
        assertEquals("tab-1", payload.get("tabId").getAsString());
        assertEquals("ses_a", payload.get("sessionId").getAsString());
    }

    @Test
    public void thePayloadRequestIdIsNotReplacedByTheCommandId() throws Exception {
        // A prompt reply carries the OpenCode request id in its payload while
        // the envelope carries an unrelated idempotency key. Overwriting the
        // payload id here sends the answer to a request that does not exist.
        HostCommand command = HostCommand.parse(
                envelope("question.reply", "tab-1", "cmd-uuid", "ses_a",
                        "{\"requestId\":\"call_abc\",\"answers\":{}}"),
                "question.reply");

        assertEquals("cmd-uuid", command.getCommandId());
        JsonObject payload = JsonParser.parseString(command.getPayloadJson()).getAsJsonObject();
        assertEquals("call_abc", payload.get("requestId").getAsString());
    }

    @Test
    public void theCommandIdSuppliesAMissingPayloadRequestId() throws Exception {
        HostCommand command = HostCommand.parse(
                envelope("session.send", "tab-1", "cmd-uuid", "ses_a", "{\"text\":\"hi\"}"),
                "session.send");

        JsonObject payload = JsonParser.parseString(command.getPayloadJson()).getAsJsonObject();
        assertEquals("cmd-uuid", payload.get("requestId").getAsString());
    }

    @Test
    public void rejectsAnUnknownVersion() {
        expectInvalid(envelope("session.send", "tab-1", "req-1", null, "{}")
                .replace("\"v\":2", "\"v\":1"), "UNSUPPORTED_VERSION");
    }

    @Test
    public void rejectsAMissingTabId() {
        expectInvalid(envelope("session.send", null, "req-1", null, "{}"), "MISSING_TAB_ID");
    }

    @Test
    public void rejectsAMissingRequestId() {
        expectInvalid(envelope("session.send", "tab-1", null, null, "{}"), "MISSING_REQUEST_ID");
    }

    @Test
    public void rejectsAnotherCommandType() {
        expectInvalid(envelope("session.interrupt", "tab-1", "req-1", null, "{}"), "UNKNOWN_TYPE");
    }

    @Test
    public void rejectsGarbage() {
        expectInvalid("not json", "BAD_ENVELOPE");
        expectInvalid("", "BAD_ENVELOPE");
    }
}
