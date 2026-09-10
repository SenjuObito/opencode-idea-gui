package com.opencodebuddy.session;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts opencode SDK message entries ({@code [{info, parts}]}, as returned
 * by daemon {@code opencode.listMessages}) into the Claude-compatible message
 * JSON the Java session/history layer consumes.
 *
 * <p>User messages are restored for display, not replay: server-generated
 * {@code synthetic} text parts (the "Called the Read tool..." lines plus the
 * inlined file content the server produced when the prompt was sent) are
 * dropped, file parts become attachment chips / image previews, and the
 * plugin-injected context sections appended on send
 * ({@code ## IDE Context}, {@code ## Referenced Files}, …) are stripped from
 * the visible text via {@link UserTextSanitizer}. This mirrors the opencode
 * TUI transcript restore, which filters synthetic parts the same way.</p>
 *
 * <ul>
 *   <li>text part → {@code {type:'text', text}} (synthetic parts skipped)</li>
 *   <li>reasoning part → {@code {type:'thinking', thinking}}</li>
 *   <li>tool part → {@code {type:'tool_use', id, name, input}} plus a separate
 *       {@code [tool_result]} user message for completed/error output</li>
 *   <li>file part (image mime) → {@code {type:'image', src, mediaType}}</li>
 *   <li>file part (other mime) → {@code {type:'attachment', fileName, mediaType}}</li>
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
        List<JsonObject> attachmentBlocks = new ArrayList<>();
        List<JsonObject> imageBlocks = new ArrayList<>();
        StringBuilder text = new StringBuilder();

        for (JsonElement partElement : parts) {
            if (!partElement.isJsonObject()) {
                continue;
            }
            JsonObject part = partElement.getAsJsonObject();
            String type = string(part, "type");
            if ("text".equals(type) && part.has("text") && !part.get("text").isJsonNull()) {
                // Server-generated expansions (Read tool call lines + inlined file
                // content) are context for the model, never user-visible text.
                if (isSynthetic(part)) {
                    continue;
                }
                text.append(part.get("text").getAsString());
            } else if ("file".equals(type)) {
                JsonObject block = filePartToBlock(part);
                if (block != null) {
                    if ("image".equals(block.get("type").getAsString())) {
                        imageBlocks.add(block);
                    } else {
                        attachmentBlocks.add(block);
                    }
                }
            }
        }

        // Drop the context sections the plugin appended on send (## IDE Context,
        // ## Referenced Files, agent instructions, …) — model-only payload.
        String displayText = UserTextSanitizer.sanitize(text.toString());

        for (JsonObject block : attachmentBlocks) {
            blocks.add(block);
        }
        for (JsonObject block : imageBlocks) {
            blocks.add(block);
        }
        if (!displayText.isEmpty()) {
            JsonObject textBlock = new JsonObject();
            textBlock.addProperty("type", "text");
            textBlock.addProperty("text", displayText);
            blocks.add(textBlock);
        }

        return createMessage("user", displayText, info, blocks);
    }

    private static boolean isSynthetic(JsonObject part) {
        return part.has("synthetic") && !part.get("synthetic").isJsonNull()
                && part.get("synthetic").getAsBoolean();
    }

    /**
     * File part → frontend block. Image parts carry a data: URL the renderer
     * can use directly; everything else renders as an attachment chip.
     */
    private static JsonObject filePartToBlock(JsonObject part) {
        String mime = string(part, "mime");
        String url = string(part, "url");
        String filename = string(part, "filename");
        if (filename.isEmpty()) {
            filename = urlBasename(url);
        }

        if (mime.startsWith("image/") && url.startsWith("data:")) {
            JsonObject block = new JsonObject();
            block.addProperty("type", "image");
            block.addProperty("src", url);
            block.addProperty("mediaType", mime);
            return block;
        }

        if (filename.isEmpty() && mime.isEmpty()) {
            return null;
        }

        JsonObject block = new JsonObject();
        block.addProperty("type", "attachment");
        block.addProperty("fileName", filename);
        if (!mime.isEmpty()) {
            block.addProperty("mediaType", mime);
        }
        return block;
    }

    /**
     * Basename of a file:///data: URL for chip display when the part carries
     * no filename.
     */
    private static String urlBasename(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        int hash = url.indexOf('#');
        String path = hash > 0 ? url.substring(0, hash) : url;
        int query = path.indexOf('?');
        if (query > 0) {
            path = path.substring(0, query);
        }
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        try {
            return java.net.URLDecoder.decode(name, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return name;
        }
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
