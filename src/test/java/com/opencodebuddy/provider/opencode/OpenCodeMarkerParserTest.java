package com.opencodebuddy.provider.opencode;

import com.google.gson.Gson;
import com.opencodebuddy.provider.common.MessageCallback;
import com.opencodebuddy.provider.common.SDKResult;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
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
}
