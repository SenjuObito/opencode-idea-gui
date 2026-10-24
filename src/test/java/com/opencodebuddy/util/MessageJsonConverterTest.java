package com.opencodebuddy.util;

import com.opencodebuddy.handler.core.HandlerContext;
import com.opencodebuddy.session.OpencodeSession;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MessageJsonConverterTest {

    @Test
    public void convertMessagesToJsonKeepsOnlyFrontendRelevantRawFields() {
        JsonObject textBlock = new JsonObject();
        textBlock.addProperty("type", "text");
        textBlock.addProperty("text", "Hello from Claude");

        JsonArray content = new JsonArray();
        content.add(textBlock);

        JsonObject message = new JsonObject();
        message.addProperty("id", "msg-1");
        message.addProperty("model", "claude-sonnet-4-6");
        JsonObject usage = new JsonObject();
        usage.addProperty("input_tokens", 321);
        message.add("usage", usage);
        message.add("content", content);

        JsonObject raw = new JsonObject();
        raw.addProperty("uuid", "uuid-1");
        raw.addProperty("type", "assistant");
        raw.addProperty("sessionId", "session-1");
        raw.addProperty("parentUuid", "parent-1");
        raw.add("message", message);
        JsonObject turnUsage = new JsonObject();
        turnUsage.addProperty("input_tokens", 1200);
        turnUsage.addProperty("cache_read_input_tokens", 36310);
        turnUsage.addProperty("output_tokens", 456);
        raw.add("turnUsage", turnUsage);
        raw.addProperty("turnCostUsd", 0.0123);

        OpencodeSession.Message sessionMessage = new OpencodeSession.Message(
                OpencodeSession.Message.Type.ASSISTANT,
                "Hello from Claude",
                raw
        );

        JsonArray transportMessages = JsonParser.parseString(
                MessageJsonConverter.convertMessagesToJson(List.of(sessionMessage))
        ).getAsJsonArray();

        JsonObject transportRaw = transportMessages.get(0).getAsJsonObject().getAsJsonObject("raw");
        assertEquals("uuid-1", transportRaw.get("uuid").getAsString());
        assertEquals("assistant", transportRaw.get("type").getAsString());
        assertFalse(transportRaw.has("sessionId"));
        assertFalse(transportRaw.has("parentUuid"));

        // turnUsage (whole-turn aggregate) is transported for the per-turn token display
        assertTrue(transportRaw.has("turnUsage"));
        assertEquals(1200, transportRaw.getAsJsonObject("turnUsage").get("input_tokens").getAsInt());
        assertTrue(transportRaw.has("turnCostUsd"));
        assertEquals(0.0123, transportRaw.get("turnCostUsd").getAsDouble(), 0.000001);

        JsonObject transportMessage = transportRaw.getAsJsonObject("message");
        assertTrue(transportMessage.has("content"));
        assertFalse(transportMessage.has("id"));
        assertFalse(transportMessage.has("model"));
        // message.usage feeds the status bar only and must NOT be transported per message
        assertFalse(transportMessage.has("usage"));
    }

    @Test
    public void truncateRawForTransportPreservesToolResultBlocksWhileDroppingOuterMetadata() {
        JsonObject toolResult = new JsonObject();
        toolResult.addProperty("type", "tool_result");
        toolResult.addProperty("tool_use_id", "tool-1");
        toolResult.addProperty("content", "x".repeat(25000));

        JsonArray content = new JsonArray();
        content.add(toolResult);

        JsonObject message = new JsonObject();
        message.add("content", content);

        JsonObject raw = new JsonObject();
        raw.addProperty("type", "user");
        raw.addProperty("uuid", "uuid-2");
        raw.addProperty("cwd", "/workspace");
        raw.addProperty("parentUuid", "parent-2");
        raw.add("message", message);

        JsonObject transportRaw = MessageJsonConverter.truncateRawForTransport(raw);

        assertFalse(transportRaw.has("cwd"));
        assertFalse(transportRaw.has("parentUuid"));

        JsonObject transportToolResult = transportRaw
                .getAsJsonObject("message")
                .getAsJsonArray("content")
                .get(0)
                .getAsJsonObject();

        assertEquals("tool-1", transportToolResult.get("tool_use_id").getAsString());
        assertTrue(transportToolResult.get("content").getAsString().contains("truncated"));
    }

    @Test
    public void usageUpdateIsTaggedWithTheOwningSessionInsteadOfTheActiveOne() {
        List<String[]> calls = new ArrayList<>();
        HandlerContext context = new HandlerContext(null, null, null, new HandlerContext.JsCallback() {
            @Override
            public void callJavaScript(String functionName, String... args) {
                List<String> call = new ArrayList<>();
                call.add(functionName);
                java.util.Collections.addAll(call, args);
                calls.add(call.toArray(new String[0]));
            }

            @Override
            public String escapeJs(String str) {
                return str;
            }
        });

        JsonObject tokens = new JsonObject();
        tokens.addProperty("input", 2_000);
        tokens.addProperty("output", 100);
        JsonObject raw = new JsonObject();
        raw.addProperty("type", "assistant");
        raw.add("tokens", tokens);
        OpencodeSession.Message assistant = new OpencodeSession.Message(
                OpencodeSession.Message.Type.ASSISTANT, "reply", raw);

        MessageJsonConverter.pushUsageUpdateFromMessages(
                List.of(assistant), "session-a", "opencode", "model-a[200k]", context);

        assertEquals(1, calls.size());
        assertEquals("window.onUsageUpdate", calls.get(0)[0]);
        assertEquals("session-a", calls.get(0)[1]);
        // HandlerContext callers receive the JsUtils-escaped payload.
        JsonObject payload = JsonParser.parseString(
                calls.get(0)[2].replace("\\\"", "\"")).getAsJsonObject();
        assertEquals(2_000, payload.get("usedTokens").getAsInt());
        assertEquals(200_000, payload.get("maxTokens").getAsInt());
        assertEquals(1, payload.get("percentage").getAsInt());
    }

    @Test
    public void usageUpdateWithoutSessionIdIsDropped() {
        List<String> calls = new ArrayList<>();
        HandlerContext context = new HandlerContext(null, null, null, new HandlerContext.JsCallback() {
            @Override
            public void callJavaScript(String functionName, String... args) {
                calls.add(functionName);
            }

            @Override
            public String escapeJs(String str) {
                return str;
            }
        });

        JsonObject tokens = new JsonObject();
        tokens.addProperty("input", 2_000);
        JsonObject raw = new JsonObject();
        raw.addProperty("type", "assistant");
        raw.add("tokens", tokens);
        OpencodeSession.Message assistant = new OpencodeSession.Message(
                OpencodeSession.Message.Type.ASSISTANT, "reply", raw);

        MessageJsonConverter.pushUsageUpdateFromMessages(
                List.of(assistant), null, "opencode", "model-a", context);
        MessageJsonConverter.pushUsageUpdateFromMessages(
                List.of(assistant), "", "opencode", "model-a", context);

        assertTrue(calls.isEmpty());
    }

    @Test
    public void convertMessagesToJsonPreservesCompactFields() {
        JsonObject raw = new JsonObject();
        raw.addProperty("type", "user");
        raw.addProperty("isCompactSummary", true);
        JsonObject compactMeta = new JsonObject();
        compactMeta.addProperty("trigger", "manual");
        compactMeta.addProperty("messagesSummarized", 10);
        raw.add("compactMetadata", compactMeta);

        OpencodeSession.Message sessionMessage = new OpencodeSession.Message(
                OpencodeSession.Message.Type.USER,
                "Summary content",
                raw
        );

        JsonArray transportMessages = JsonParser.parseString(
                MessageJsonConverter.convertMessagesToJson(List.of(sessionMessage))
        ).getAsJsonArray();

        JsonObject transportRaw = transportMessages.get(0).getAsJsonObject().getAsJsonObject("raw");
        assertTrue(transportRaw.get("isCompactSummary").getAsBoolean());
        assertTrue(transportRaw.has("compactMetadata"));
        assertEquals("manual", transportRaw.getAsJsonObject("compactMetadata").get("trigger").getAsString());
        assertEquals(10, transportRaw.getAsJsonObject("compactMetadata").get("messagesSummarized").getAsInt());
    }

    @Test
    public void convertMessagesToJsonPreservesNormalizedTerminalError() {
        JsonObject raw = new JsonObject();
        raw.addProperty("type", "error");
        raw.addProperty("finish", "error");
        JsonObject error = new JsonObject();
        error.addProperty("kind", "provider.quota");
        error.addProperty("message", "Free usage limit reached");
        error.addProperty("status", 429);
        raw.add("error", error);

        OpencodeSession.Message sessionMessage = new OpencodeSession.Message(
                OpencodeSession.Message.Type.ERROR,
                "Free usage limit reached",
                raw
        );

        JsonArray transportMessages = JsonParser.parseString(
                MessageJsonConverter.convertMessagesToJson(List.of(sessionMessage))
        ).getAsJsonArray();

        JsonObject transportRaw = transportMessages.get(0).getAsJsonObject().getAsJsonObject("raw");
        assertEquals("error", transportRaw.get("finish").getAsString());
        JsonObject transportedError = transportRaw.getAsJsonObject("error");
        assertEquals("provider.quota", transportedError.get("kind").getAsString());
        assertEquals(429, transportedError.get("status").getAsInt());
    }
}
