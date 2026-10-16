package com.opencodebuddy.session;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts opencode SDK message entries ({@code [{info, parts}]}, as returned
 * by daemon {@code opencode.listMessages}) into the OpenCode-compatible message
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

    private static final int MAX_TOOL_RESULT_CHARS = 20_000;

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

        // Extract inlined attachment blocks (<attachment filename="...">) from text
        // so they render as attachment chips in the UI when restored from history.
        Pattern inlinedPattern = Pattern.compile("<attachment\\s+filename=\"([^\"]+)\"[^>]*>", Pattern.CASE_INSENSITIVE);
        Matcher attMatcher = inlinedPattern.matcher(text.toString());
        while (attMatcher.find()) {
            String filename = attMatcher.group(1);
            boolean alreadyPresent = attachmentBlocks.stream()
                    .anyMatch(b -> filename.equals(string(b, "fileName")));
            if (!alreadyPresent) {
                JsonObject block = new JsonObject();
                block.addProperty("type", "attachment");
                block.addProperty("fileName", filename);
                block.addProperty("mediaType", "text/plain");
                attachmentBlocks.add(block);
            }
        }

        // Drop the context sections the plugin appended on send (## IDE Context,
        // ## Referenced Files, ## Attached Files, inlined attachments, agent instructions, …) — model-only payload.
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
                if ("shell".equalsIgnoreCase(toolName)) {
                    toolName = "bash";
                }
                JsonObject block = new JsonObject();
                block.addProperty("type", "tool_use");
                block.addProperty("id", callId);
                block.addProperty("name", toolName);
                if (isQuestionTool(toolName)) {
                    normalizeQuestionToolBlock(block, state);
                } else {
                    block.add("input", state.has("input") && state.get("input").isJsonObject()
                            ? state.getAsJsonObject("input") : new JsonObject());
                }
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
                    String rawContent = !error.isEmpty() ? error : output;
                    resultBlock.addProperty("content", truncateToolContent(rawContent));
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
            JsonObject tokens = info.getAsJsonObject("tokens");
            raw.add("tokens", tokens);

            JsonObject turnUsage = new JsonObject();
            turnUsage.addProperty("input_tokens", readInt(tokens, "input"));
            turnUsage.addProperty("output_tokens", readInt(tokens, "output") + readInt(tokens, "reasoning"));
            turnUsage.addProperty("reasoning_tokens", readInt(tokens, "reasoning"));
            int cacheRead = 0;
            int cacheWrite = 0;
            if (tokens.has("cache") && tokens.get("cache").isJsonObject()) {
                JsonObject cache = tokens.getAsJsonObject("cache");
                cacheRead = readInt(cache, "read");
                cacheWrite = readInt(cache, "write");
            }
            turnUsage.addProperty("cache_read_input_tokens", cacheRead);
            turnUsage.addProperty("cache_creation_input_tokens", cacheWrite);

            int total = readInt(tokens, "total");
            if (total <= 0) {
                total = readInt(tokens, "input") + readInt(tokens, "output") + readInt(tokens, "reasoning")
                        + cacheRead + cacheWrite;
            }
            turnUsage.addProperty("total_tokens", total);
            raw.add("turnUsage", turnUsage);
            message.add("usage", turnUsage);

            if (info.has("cost") && !info.get("cost").isJsonNull()) {
                try {
                    double cost = info.get("cost").getAsDouble();
                    if (cost > 0) {
                        raw.addProperty("turnCostUsd", cost);
                    }
                } catch (Exception ignored) {
                }
            }
        }

        if (info.has("time") && info.get("time").isJsonObject()) {
            JsonObject timeObj = info.getAsJsonObject("time");
            long created = readLong(timeObj, "created");
            if (created <= 0) {
                created = readLong(timeObj, "start");
            }
            long completed = readLong(timeObj, "completed");
            if (completed <= 0) {
                completed = readLong(timeObj, "end");
            }
            if (created > 0 && completed >= created) {
                long diff = completed - created;
                if (created < 100_000_000_000L) {
                    diff *= 1000L;
                }
                raw.addProperty("durationMs", diff);
            }
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
                    long val = time.get("created").getAsLong();
                    if (val > 0 && val < 100_000_000_000L) {
                        return val * 1000L;
                    }
                    return val;
                }
            }
        } catch (Exception ignored) {
        }
        return System.currentTimeMillis();
    }

    private static String truncateToolContent(String s) {
        if (s == null || s.length() <= MAX_TOOL_RESULT_CHARS) {
            return s != null ? s : "";
        }
        String marker = "\n... [truncated, total " + s.length() + " chars]";
        int available = Math.max(0, MAX_TOOL_RESULT_CHARS - marker.length());
        return s.substring(0, available) + marker;
    }

    private static boolean isQuestionTool(String toolName) {
        return "question".equalsIgnoreCase(toolName) || "askuserquestion".equalsIgnoreCase(toolName);
    }

    private static void normalizeQuestionToolBlock(JsonObject block, JsonObject state) {
        JsonArray normalizedQuestions = new JsonArray();
        JsonElement rawInput = state.get("input");
        if (rawInput != null && rawInput.isJsonObject()) {
            JsonObject inputObj = rawInput.getAsJsonObject();
            if (inputObj.has("questions") && inputObj.get("questions").isJsonArray()) {
                for (JsonElement qElem : inputObj.getAsJsonArray("questions")) {
                    if (qElem.isJsonObject()) {
                        JsonObject qObj = qElem.getAsJsonObject();
                        JsonObject item = new JsonObject();
                        String qText = qObj.has("question") && !qObj.get("question").isJsonNull()
                                ? qObj.get("question").getAsString()
                                : (qObj.has("text") && !qObj.get("text").isJsonNull() ? qObj.get("text").getAsString() : "");
                        item.addProperty("question", qText);
                        item.addProperty("header", string(qObj, "header"));
                        item.addProperty("multiSelect", qObj.has("multiple") ? qObj.get("multiple").getAsBoolean()
                                : (qObj.has("multiSelect") && qObj.get("multiSelect").getAsBoolean()));
                        item.addProperty("custom", !qObj.has("custom") || qObj.get("custom").getAsBoolean());
                        item.add("options", qObj.has("options") && qObj.get("options").isJsonArray()
                                ? qObj.getAsJsonArray("options") : new JsonArray());
                        if (qObj.has("key")) item.add("key", qObj.get("key"));
                        normalizedQuestions.add(item);
                    } else if (qElem.isJsonPrimitive()) {
                        JsonObject item = new JsonObject();
                        item.addProperty("question", qElem.getAsString());
                        item.addProperty("header", "");
                        item.addProperty("multiSelect", false);
                        item.addProperty("custom", true);
                        item.add("options", new JsonArray());
                        normalizedQuestions.add(item);
                    }
                }
            } else if (inputObj.has("question") && inputObj.get("question").isJsonPrimitive()) {
                JsonObject item = new JsonObject();
                item.addProperty("question", inputObj.get("question").getAsString());
                item.addProperty("header", string(inputObj, "header"));
                item.addProperty("multiSelect", false);
                item.addProperty("custom", true);
                item.add("options", inputObj.has("options") && inputObj.get("options").isJsonArray()
                        ? inputObj.getAsJsonArray("options") : new JsonArray());
                normalizedQuestions.add(item);
            }
        } else if (rawInput != null && rawInput.isJsonPrimitive()) {
            JsonObject item = new JsonObject();
            item.addProperty("question", rawInput.getAsString());
            item.addProperty("header", "");
            item.addProperty("multiSelect", false);
            item.addProperty("custom", true);
            item.add("options", new JsonArray());
            normalizedQuestions.add(item);
        }

        JsonObject normalizedInput = new JsonObject();
        normalizedInput.add("questions", normalizedQuestions);
        block.add("input", normalizedInput);

        JsonObject answersObj = new JsonObject();
        String output = string(state, "output");
        String error = string(state, "error");
        boolean isCancelled = !error.isEmpty() || output.contains("question rejected");

        // 优先从 OpenCode 原生 metadata.answers (二维数组) 还原答案
        if (state.has("metadata") && state.get("metadata").isJsonObject()) {
            JsonObject meta = state.getAsJsonObject("metadata");
            if (meta.has("answers") && meta.get("answers").isJsonArray()) {
                JsonArray answersArr = meta.getAsJsonArray("answers");
                for (int i = 0; i < answersArr.size(); i++) {
                    JsonElement aElem = answersArr.get(i);
                    String ansStr = "";
                    if (aElem.isJsonArray()) {
                        List<String> list = new ArrayList<>();
                        for (JsonElement item : aElem.getAsJsonArray()) {
                            if (item.isJsonPrimitive()) list.add(item.getAsString());
                        }
                        ansStr = String.join(", ", list);
                    } else if (aElem.isJsonPrimitive()) {
                        ansStr = aElem.getAsString();
                    }
                    if (i < normalizedQuestions.size()) {
                        String qText = string(normalizedQuestions.get(i).getAsJsonObject(), "question");
                        if (!qText.isEmpty()) {
                            answersObj.addProperty(qText, ansStr);
                        }
                    }
                }
            }
        }

        // 兜底：如果 metadata 没有答案，但 output 不为空且非取消状态，从 output 字符串提取
        if (answersObj.size() == 0 && !output.isEmpty() && !isCancelled) {
            Pattern p = Pattern.compile("\"([^\"]+)\"\\s*=\\s*\"([^\"]+)\"");
            Matcher m = p.matcher(output);
            while (m.find()) {
                answersObj.addProperty(m.group(1), m.group(2));
            }
        }

        String status = isCancelled ? "cancelled" : (answersObj.size() > 0 ? "answered" : "unanswered");

        JsonObject questionCard = new JsonObject();
        questionCard.addProperty("status", status);
        questionCard.add("questions", normalizedQuestions);
        questionCard.add("answers", answersObj);
        block.add("questionCard", questionCard);
    }

    private static String string(JsonObject obj, String key) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull()
                ? obj.get(key).getAsString() : "";
    }

    private static int readInt(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return 0;
        }
        try {
            return obj.get(key).getAsInt();
        } catch (Exception e) {
            return 0;
        }
    }

    private static long readLong(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return 0;
        }
        try {
            return obj.get(key).getAsLong();
        } catch (Exception e) {
            return 0;
        }
    }
}
