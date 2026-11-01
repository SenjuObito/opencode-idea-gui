package com.opencodebuddy.provider.opencode;

import com.google.gson.Gson;
import com.opencodebuddy.provider.common.MessageCallback;
import com.opencodebuddy.provider.common.SDKResult;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class OpenCodeMarkerParserTest {

    private Gson gson;
    private OpenCodeMarkerParser parser;

    @Before
    public void setUp() {
        gson = new Gson();
        parser = new OpenCodeMarkerParser(gson);
    }

    @Test
    public void testTodoUpdatedWithDoublyEncodedPayload() {
        // ai-bridge emitJsonStringMarker('[TODO_UPDATED]', JSON.stringify({ sessionID, todos }))
        // emits a double-encoded string: [TODO_UPDATED] "{\"sessionID\":\"ses_123\",\"todos\":[...]}"
        String rawJson = "{\"sessionID\":\"ses_123\",\"todos\":[{\"content\":\"Task 1\",\"status\":\"completed\"}]}";
        String doubleEncodedLine = "[TODO_UPDATED] " + gson.toJson(rawJson);

        AtomicReference<String> receivedType = new AtomicReference<>();
        AtomicReference<String> receivedContent = new AtomicReference<>();

        MessageCallback callback = new MessageCallback() {
            @Override
            public void onMessage(String type, String content) {
                receivedType.set(type);
                receivedContent.set(content);
            }

            @Override
            public void onError(String error) {}

            @Override
            public void onComplete(SDKResult result) {}
        };

        OpenCodeMarkerParser.StreamContext context = new OpenCodeMarkerParser.StreamContext();
        SDKResult result = new SDKResult();

        parser.processOutputLine(doubleEncodedLine, callback, result, context);

        assertEquals("todo_updated", receivedType.get());
        assertNotNull(receivedContent.get());
        // Verify it is raw JSON object, NOT wrapped in quotes
        assertEquals(rawJson, receivedContent.get());
        assertTrue(receivedContent.get().startsWith("{") && receivedContent.get().endsWith("}"));
    }

    @Test
    public void testSubagentStatusMarkerCarriesTheBareJsonObject() {
        // core emits [SUBAGENT_STATUS] via emitJsonStringMarker, i.e. the payload
        // is JSON-stringified once: [SUBAGENT_STATUS] "{\"toolUseId\":...}"
        String rawJson = "{\"parentSessionId\":\"ses_parent\",\"toolUseId\":\"call_1\","
                + "\"childSessionId\":\"ses_child\",\"status\":\"completed\",\"summary\":\"done\"}";
        String line = "[SUBAGENT_STATUS] " + gson.toJson(rawJson);

        AtomicReference<String> receivedType = new AtomicReference<>();
        AtomicReference<String> receivedContent = new AtomicReference<>();

        MessageCallback callback = new MessageCallback() {
            @Override
            public void onMessage(String type, String content) {
                receivedType.set(type);
                receivedContent.set(content);
            }

            @Override
            public void onError(String error) {}

            @Override
            public void onComplete(SDKResult result) {}
        };

        parser.processOutputLine(line, callback, new SDKResult(),
                new OpenCodeMarkerParser.StreamContext());

        assertEquals("subagent_status", receivedType.get());
        assertEquals(rawJson, receivedContent.get());
    }

    @Test
    public void testTodoUpdatedWithSinglyEncodedPayload() {
        String rawJson = "{\"sessionID\":\"ses_123\",\"todos\":[{\"content\":\"Task 1\",\"status\":\"completed\"}]}";
        String line = "[TODO_UPDATED] " + rawJson;

        AtomicReference<String> receivedType = new AtomicReference<>();
        AtomicReference<String> receivedContent = new AtomicReference<>();

        MessageCallback callback = new MessageCallback() {
            @Override
            public void onMessage(String type, String content) {
                receivedType.set(type);
                receivedContent.set(content);
            }

            @Override
            public void onError(String error) {}

            @Override
            public void onComplete(SDKResult result) {}
        };

        OpenCodeMarkerParser.StreamContext context = new OpenCodeMarkerParser.StreamContext();
        SDKResult result = new SDKResult();

        parser.processOutputLine(line, callback, result, context);

        assertEquals("todo_updated", receivedType.get());
        assertEquals(rawJson, receivedContent.get());
    }

    @Test
    public void testTurnErrorWithAbortedKindIsSuppressed() {
        // v2 reports a user stop on the same channel as a real failure:
        // [TURN_ERROR] {"sessionID":"ses_1","kind":"aborted","message":"Step interrupted"}
        String line = "[TURN_ERROR] " + gson.toJson(
                "{\"sessionID\":\"ses_1\",\"kind\":\"aborted\",\"message\":\"Step interrupted\"}");

        AtomicReference<String> receivedType = new AtomicReference<>();

        MessageCallback callback = new MessageCallback() {
            @Override
            public void onMessage(String type, String content) {
                receivedType.set(type);
            }

            @Override
            public void onError(String error) {}

            @Override
            public void onComplete(SDKResult result) {}
        };

        parser.processOutputLine(line, callback, new SDKResult(),
                new OpenCodeMarkerParser.StreamContext());

        assertNull(receivedType.get());
    }

    @Test
    public void testTurnErrorAfterAbortedTurnEndIsSuppressed() {
        AtomicReference<String> receivedType = new AtomicReference<>();

        MessageCallback callback = new MessageCallback() {
            @Override
            public void onMessage(String type, String content) {
                receivedType.set(type);
            }

            @Override
            public void onError(String error) {}

            @Override
            public void onComplete(SDKResult result) {}
        };

        OpenCodeMarkerParser.StreamContext context = new OpenCodeMarkerParser.StreamContext();
        SDKResult result = new SDKResult();

        parser.processOutputLine(
                "[TURN_END] {\"sessionId\":\"ses_1\",\"turnId\":7,\"reason\":\"aborted\"}",
                callback, result, context);
        assertEquals("turn_end", receivedType.get());
        assertTrue(context.wasAborted.get());

        receivedType.set(null);
        // A late, untyped error after the abort must stay invisible too.
        parser.processOutputLine("[TURN_ERROR] \"Step interrupted\"", callback, result, context);
        assertNull(receivedType.get());

        receivedType.set(null);
        parser.processOutputLine("[SEND_ERROR] {\"error\":\"User interrupted\"}", callback, result, context);
        assertNull(receivedType.get());
        assertTrue(!context.hadSendError.get());
    }

    @Test
    public void testCompactionMarkersParsing() {
        java.util.List<String> receivedTypes = new java.util.ArrayList<>();
        java.util.List<String> receivedContents = new java.util.ArrayList<>();

        MessageCallback callback = new MessageCallback() {
            @Override
            public void onMessage(String type, String content) {
                receivedTypes.add(type);
                receivedContents.add(content);
            }

            @Override
            public void onError(String error) {}

            @Override
            public void onComplete(SDKResult result) {}
        };

        OpenCodeMarkerParser.StreamContext context = new OpenCodeMarkerParser.StreamContext();
        SDKResult result = new SDKResult();

        // 1. [COMPACTION_STARTED]
        parser.processOutputLine("[COMPACTION_STARTED] {\"sessionID\":\"ses_123\",\"reason\":\"auto\"}", callback, result, context);
        assertEquals("compaction_started", receivedTypes.get(0));
        assertTrue(receivedContents.get(0).contains("ses_123"));

        // 2. [COMPACTION_DELTA]
        parser.processOutputLine("[COMPACTION_DELTA] {\"sessionID\":\"ses_123\",\"text\":\"Summarizing...\"}", callback, result, context);
        assertEquals("compaction_delta", receivedTypes.get(1));

        // 3. [COMPACTION_ENDED]
        parser.processOutputLine("[COMPACTION_ENDED] {\"sessionID\":\"ses_123\",\"summary\":\"Summary done\",\"reason\":\"auto\"}", callback, result, context);
        assertEquals("compaction_ended", receivedTypes.get(2));
        assertTrue(receivedContents.get(2).contains("Summary done"));

        // 4. [COMPACTION_FAILED]
        parser.processOutputLine("[COMPACTION_FAILED] {\"sessionID\":\"ses_123\",\"error\":\"Rate limit exceeded\"}", callback, result, context);
        assertEquals("compaction_failed", receivedTypes.get(3));
        assertTrue(receivedContents.get(3).contains("Rate limit exceeded"));
    }
}
