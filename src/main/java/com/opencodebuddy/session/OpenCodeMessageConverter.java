package com.opencodebuddy.session;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts opencode SDK message entries ({@code [{info, parts}]}, as returned
 * by daemon {@code opencode.listMessages}) into the Claude-compatible message
 * JSON the Java session/history layer consumes:
 * <ul>
 *   <li>text part → {@code {type:'text', text}}</li>
 *   <li>reasoning part → {@code {type:'thinking', thinking}}</li>
 *   <li>tool part → {@code {type:'tool_use', id, name, input}} plus a separate
 *       {@code [tool_result]} user message for completed/error output</li>
 * </ul>
 */
public final class OpenCodeMessageConverter {

    private OpenCodeMessageConverter() {
    }

    public static List<JsonObject> convert(JsonElement element) {
        List<JsonObject> out = new ArrayList<>();
        if (element == null || !element.isJsonArray()) {
            return out;
        }
        for (JsonElement entryElement : element.getAsJsonArray()) {
            if (!entryElement.isJsonObject()) {
                continue;
            }
            JsonObject entry = entryElement.getAsJsonObject();
            JsonObject info = entry.has("info") && entry.get("info").isJsonObject()
                    ? entry.getAsJsonObject("info") : new JsonObject();
            JsonArray parts = entry.has("parts") && entry.get("parts").isJsonArray()
                    ? entry.getAsJsonArray("parts") : new JsonArray();
            String role = info.has("role") && !info.get("role").isJsonNull()
                    ? info.get("role").getAsString() : "";
            if ("user".equals(role)) {
                out.add(buildUserMessage(parts, info));
            } else if ("assistant".equals(role)) {
                out.addAll(buildAssistantMessages(parts, info));
            }
            // system / other roles skipped
        }
        return out;
    }

    private static JsonObject buildUserMessage(JsonArray parts, JsonObject info) {
        JsonArray blocks = new JsonArray();
        StringBuilder text = new StringBuilder();
        for (JsonElement partElement : parts) {
            if (!partElement.isJsonObject()) {
                continue;
            }
            JsonObject part = partElement.getAsJsonObject();
            String type = string(part, "type");
            if ("text".equals(type) && part.has("text") && !part.get("text").isJsonNull()) {
                String value = part.get("text").getAsString();
                text.append(value);
                JsonObject block = new JsonObject();
                block.addProperty("type", "text");
                block.addProperty("text", value);
                blocks.add(block);
            }
        }
        return createMessage("user", text.toString(), info, blocks);
    }

    private static List<JsonObject> buildAssistantMessages(JsonArray parts, JsonObject info) {
        JsonArray blocks = new JsonArray();
        StringBuilder text = new StringBuilder();
        List<JsonObject> toolResults = new ArrayList<>();

        for (JsonElement partElement : parts) {
            if (!partElement.isJsonObject()) {
                continue;
            }
            JsonObject part = partElement.getAsJsonObject();
            String type = string(part, "type");
            if ("text".equals(type) && part.has("text") && !part.get("text").isJsonNull()) {
                String value = part.get("text").getAsString();
                text.append(value);
                JsonObject block = new JsonObject();
                block.addProperty("type", "text");
                block.addProperty("text", value);
                blocks.add(block);
            } else if ("reasoning".equals(type) && part.has("text") && !part.get("text").isJsonNull()) {
                JsonObject block = new JsonObject();
                block.addProperty("type", "thinking");
                block.addProperty("thinking", part.get("text").getAsString());
                blocks.add(block);
            } else if ("tool".equals(type)) {
                JsonObject state = part.has("state") && part.get("state").isJsonObject()
                        ? part.getAsJsonObject("state") : new JsonObject();
                String callId = part.has("callID") && !part.get("callID").isJsonNull()
                        ? part.get("callID").getAsString() : string(part, "id");
                String toolName = string(part, "tool");
                JsonObject block = new JsonObject();
                block.addProperty("type", "tool_use");
                block.addProperty("id", callId);
                block.addProperty("name", toolName);
                block.add("input", state.has("input") && state.get("input").isJsonObject()
                        ? state.getAsJsonObject("input") : new JsonObject());
                blocks.add(block);

                // Completed/errored tool output → separate [tool_result] user message.
                String output = string(state, "output");
                String error = string(state, "error");
                if (!output.isEmpty() || !error.isEmpty()) {
                    JsonObject resultRaw = new JsonObject();
                    JsonObject message = new JsonObject();
                    JsonArray content = new JsonArray();
                    JsonObject resultBlock = new JsonObject();
                    resultBlock.addProperty("type", "tool_result");
                    resultBlock.addProperty("tool_use_id", callId);
                    resultBlock.addProperty("content", !error.isEmpty() ? error : output);
                    content.add(resultBlock);
                    message.add("content", content);
                    resultRaw.addProperty("type", "user");
                    resultRaw.add("message", message);
                    JsonObject resultMessage = new JsonObject();
                    resultMessage.addProperty("type", "user");
                    resultMessage.addProperty("content", "[tool_result]");
                    resultMessage.addProperty("timestamp", timestamp(info));
                    resultMessage.add("raw", resultRaw);
                    toolResults.add(resultMessage);
                }
            }
        }

        JsonObject raw = new JsonObject();
        raw.addProperty("type", "assistant");
        JsonObject message = new JsonObject();
        message.add("content", blocks);
        raw.add("message", message);
        String id = string(info, "id");
        if (!id.isEmpty()) {
            raw.addProperty("id", id);
        }
        String model = string(info, "modelID");
        if (!model.isEmpty()) {
            raw.addProperty("model", model);
        }
        if (info.has("tokens") && info.get("tokens").isJsonObject()) {
            raw.add("tokens", info.getAsJsonObject("tokens"));
        }

        JsonObject assistant = new JsonObject();
        assistant.addProperty("type", "assistant");
        assistant.addProperty("content", text.toString());
        assistant.addProperty("timestamp", timestamp(info));
        assistant.add("raw", raw);
        List<JsonObject> out = new ArrayList<>();
        out.add(assistant);
        out.addAll(toolResults);
        return out;
    }

    private static JsonObject createMessage(String type, String content, JsonObject info, JsonArray blocks) {
        JsonObject raw = new JsonObject();
        raw.addProperty("type", type);
        JsonObject message = new JsonObject();
        message.add("content", blocks);
        raw.add("message", message);
        String id = string(info, "id");
        if (!id.isEmpty()) {
            raw.addProperty("id", id);
        }
        JsonObject out = new JsonObject();
        out.addProperty("type", type);
        out.addProperty("content", content);
        out.addProperty("timestamp", timestamp(info));
        out.add("raw", raw);
        return out;
    }

    private static long timestamp(JsonObject info) {
        try {
            if (info.has("time") && info.get("time").isJsonObject()) {
                JsonObject time = info.getAsJsonObject("time");
                if (time.has("created") && !time.get("created").isJsonNull()) {
                    return time.get("created").getAsLong();
                }
            }
        } catch (Exception ignored) {
        }
        return System.currentTimeMillis();
    }

    private static String string(JsonObject obj, String key) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull()
                ? obj.get(key).getAsString() : "";
    }
}
