package com.opencodebuddy.session;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

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

    private static final Logger LOG = Logger.getInstance(OpenCodeMessageConverter.class);

    private static final int MAX_TOOL_RESULT_CHARS = 20_000;

    private OpenCodeMessageConverter() {
    }

    /**
     * Read a JSON value as a string without ever throwing.
     *
     * <p>v2 turned several fields that used to be plain strings into objects —
     * {@code tool.state.error} became {@code {type, message}}, for instance.
     * Gson's {@code getAsString()} on a non-primitive throws
     * {@link UnsupportedOperationException} whose message is just the element
     * type ("JsonObject"), and nothing used to catch it, so one such field
     * failed the entire history load. Returning "" for a non-primitive keeps
     * the value readable when it *is* a string and harmless when it is not.</p>
     */
    private static String textOf(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return "";
        }
        return element.getAsString();
    }

    /** Read a boolean flag without throwing on a missing/non-primitive value. */
    private static boolean isTrue(JsonObject obj, String key) {
        return obj != null && obj.has(key) && obj.get(key).isJsonPrimitive()
                && obj.get(key).getAsBoolean();
    }

    /**
     * Read the normalized error contract emitted by opencode-buddy-core.
     *
     * <p>Core flattens v1 {@code {name,data:{message}}} and v2
     * {@code {type,message}} into one {@code {kind,message,status,retryable}}
     * object. The host deliberately does not re-implement either engine's
     * shape; the legacy {@code type} fallback only covers diagnostics payloads
     * that never travelled through the core normalizer (e.g. a tool state).</p>
     */
    private static String errorText(JsonObject state) {
        if (state == null || !state.has("error")) {
            return "";
        }
        JsonElement err = state.get("error");
        if (err.isJsonPrimitive()) {
            return err.getAsString();
        }
        if (err.isJsonObject()) {
            JsonObject obj = err.getAsJsonObject();
            String message = textOf(obj.get("message"));
            if (!message.isEmpty()) {
                return message;
            }
            String kind = textOf(obj.get("kind"));
            if (!kind.isEmpty()) {
                return kind;
            }
            return textOf(obj.get("type"));
        }
        return "";
    }

    public static List<JsonObject> convert(JsonElement element) {
        List<JsonObject> out = new ArrayList<>();
        if (element == null || !element.isJsonArray()) {
            return out;
        }
        JsonArray array = element.getAsJsonArray();
        for (int i = 0; i < array.size(); i++) {
            JsonElement entryElement = array.get(i);
            if (!entryElement.isJsonObject()) {
                continue;
            }
            // One malformed entry must not fail the whole restore. History used
            // to come back as "Failed to load messages for session X: JsonObject"
            // — a Gson type error from a single field — and render a blank tab
            // with no way to see the rest of the conversation. Skipping the
            // entry keeps everything else visible and logs what was dropped.
            try {
                JsonObject entry = entryElement.getAsJsonObject();
                JsonObject info = entry.has("info") && entry.get("info").isJsonObject()
                        ? entry.getAsJsonObject("info") : new JsonObject();
                JsonArray parts = entry.has("parts") && entry.get("parts").isJsonArray()
                        ? entry.getAsJsonArray("parts") : new JsonArray();
                String role = string(info, "role");
                if (isCompactionEntry(info)) {
                    out.add(buildCompactionMessage(parts, info));
                } else if ("user".equals(role)) {
                    if (!isCompactionTriggerUser(parts)) {
                        String shellCommand = findShellCommandForUserMessage(array, i, string(info, "id"));
                        out.add(buildUserMessage(parts, info, shellCommand));
                    }
                } else if ("assistant".equals(role)) {
                    out.addAll(buildAssistantMessages(parts, info));
                }
                // system / other roles skipped
            } catch (RuntimeException e) {
                LOG.warn("[OpenCodeMessageConverter] skipped a malformed message entry: "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        return out;
    }

    private static String findShellCommandForUserMessage(JsonArray array, int userIndex, String userMsgId) {
        for (int j = userIndex + 1; j < array.size(); j++) {
            JsonElement el = array.get(j);
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject nextEntry = el.getAsJsonObject();
            JsonObject nextInfo = nextEntry.has("info") && nextEntry.get("info").isJsonObject()
                    ? nextEntry.getAsJsonObject("info") : null;
            if (nextInfo == null) {
                continue;
            }
            String nextRole = string(nextInfo, "role");
            if ("user".equals(nextRole)) {
                break;
            }
            if ("assistant".equals(nextRole)) {
                String parentId = string(nextInfo, "parentID");
                if (!userMsgId.isEmpty() && !parentId.isEmpty() && !userMsgId.equals(parentId)) {
                    continue;
                }
                JsonArray nextParts = nextEntry.has("parts") && nextEntry.get("parts").isJsonArray()
                        ? nextEntry.getAsJsonArray("parts") : null;
                if (nextParts != null) {
                    for (JsonElement pEl : nextParts) {
                        if (!pEl.isJsonObject()) {
                            continue;
                        }
                        JsonObject p = pEl.getAsJsonObject();
                        String toolName = string(p, "tool");
                        if ("bash".equalsIgnoreCase(toolName) || "shell".equalsIgnoreCase(toolName)) {
                            String cmd = extractShellCommand(p);
                            if (cmd != null && !cmd.isBlank()) {
                                return cmd;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private static String extractShellCommand(JsonObject part) {
        JsonObject input = null;
        if (part.has("state") && part.get("state").isJsonObject()) {
            JsonObject state = part.getAsJsonObject("state");
            if (state.has("input") && state.get("input").isJsonObject()) {
                input = state.getAsJsonObject("input");
            }
        }
        if (input == null && part.has("input") && part.get("input").isJsonObject()) {
            input = part.getAsJsonObject("input");
        }
        if (input != null && input.has("command") && !input.get("command").isJsonNull()) {
            return input.get("command").getAsString();
        }
        return null;
    }

    private static JsonObject buildUserMessage(JsonArray parts, JsonObject info) {
        return buildUserMessage(parts, info, null);
    }

    private static JsonObject buildUserMessage(JsonArray parts, JsonObject info, String shellCommand) {
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
                text.append(textOf(part.get("text")));
            } else if ("file".equals(type)) {
                JsonObject block = filePartToBlock(part);
                if (block != null) {
                    if ("image".equals(textOf(block.get("type")))) {
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

        if (displayText.isEmpty() && shellCommand != null && !shellCommand.isBlank()) {
            displayText = "!bash " + shellCommand.trim();
        }

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

    private static boolean isCompactionEntry(JsonObject info) {
        if (info == null) {
            return false;
        }
        String role = string(info, "role");
        if ("compaction".equals(role) || isTrue(info, "isCompactSummary")) {
            return true;
        }
        // OpenCode v1 compaction: an assistant message with mode='compaction', agent='compaction', or summary=true
        if ("compaction".equals(string(info, "mode")) || "compaction".equals(string(info, "agent"))) {
            return true;
        }
        return isTrue(info, "summary") && ("assistant".equals(role) || role.isEmpty());
    }

    private static boolean isCompactionTriggerUser(JsonArray parts) {
        if (parts == null || parts.size() == 0) {
            return false;
        }
        boolean hasCompactionPart = false;
        for (JsonElement p : parts) {
            if (p.isJsonObject()) {
                String type = string(p.getAsJsonObject(), "type");
                if ("compaction".equals(type)) {
                    hasCompactionPart = true;
                } else if (!type.isEmpty()) {
                    return false;
                }
            }
        }
        return hasCompactionPart;
    }

    private static JsonObject buildCompactionMessage(JsonArray parts, JsonObject info) {
        StringBuilder text = new StringBuilder();
        for (JsonElement partElement : parts) {
            if (partElement.isJsonObject()) {
                JsonObject part = partElement.getAsJsonObject();
                if ("text".equals(string(part, "type")) && part.has("text") && !part.get("text").isJsonNull()) {
                    text.append(textOf(part.get("text")));
                }
            }
        }
        String summaryText = text.toString();
        if (summaryText.isEmpty() && info.has("summary")) {
            summaryText = textOf(info.get("summary"));
        }

        JsonArray blocks = new JsonArray();
        JsonObject textBlock = new JsonObject();
        textBlock.addProperty("type", "text");
        textBlock.addProperty("text", summaryText);
        blocks.add(textBlock);

        JsonObject msg = createMessage("user", summaryText, info, blocks);
        JsonObject raw = msg.getAsJsonObject("raw");
        raw.addProperty("isCompactSummary", true);

        if (info.has("compactMetadata") && info.get("compactMetadata").isJsonObject()) {
            JsonObject meta = info.getAsJsonObject("compactMetadata").deepCopy();
            // Older cores copied the entire summary into `userContext`.
            // That field is a user-supplied /compact focus, so showing the
            // summary there printed the whole conversation summary twice.
            if (meta.has("userContext") && !meta.get("userContext").isJsonNull()
                    && summaryText.equals(textOf(meta.get("userContext")))) {
                meta.remove("userContext");
            }
            if (!meta.has("trigger") && info.has("reason")) {
                String reason = string(info, "reason");
                if (!reason.isEmpty()) {
                    meta.addProperty("trigger", reason);
                }
            }
            raw.add("compactMetadata", meta);
            raw.add("summarizeMetadata", meta.deepCopy());
        } else {
            JsonObject meta = new JsonObject();
            String reason = string(info, "reason");
            meta.addProperty("trigger", reason.isEmpty() ? "manual" : reason);
            raw.add("compactMetadata", meta);
            raw.add("summarizeMetadata", meta.deepCopy());
        }

        return msg;
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

        boolean hasAbortedNotice = false;
        String abortedNoticeText = "已停止生成";
        String errorContent = "";

        for (JsonElement partElement : parts) {
            if (!partElement.isJsonObject()) {
                continue;
            }
            JsonObject part = partElement.getAsJsonObject();
            String type = string(part, "type");
            if ("text".equals(type) && part.has("text") && !part.get("text").isJsonNull()) {
                String value = textOf(part.get("text"));
                if (isAbortedPart(part)) {
                    hasAbortedNotice = true;
                    if (!value.isBlank()) {
                        abortedNoticeText = value;
                    }
                    continue;
                }
                String trimmed = value.trim();
                boolean isErrorPart = isTrue(part, "isError") || (trimmed.startsWith("[Error:") && trimmed.endsWith("]"));
                if (isErrorPart) {
                    String extracted = trimmed.startsWith("[Error:") && trimmed.endsWith("]")
                            ? trimmed.substring(7, trimmed.length() - 1).trim()
                            : trimmed;
                    if (!extracted.isEmpty()) {
                        errorContent = extracted;
                        continue;
                    }
                }
                text.append(value);
                JsonObject block = new JsonObject();
                block.addProperty("type", "text");
                block.addProperty("text", value);
                blocks.add(block);
            } else if ("reasoning".equals(type) && part.has("text") && !part.get("text").isJsonNull()) {
                JsonObject block = new JsonObject();
                block.addProperty("type", "thinking");
                block.addProperty("thinking", textOf(part.get("text")));
                blocks.add(block);
            } else if ("tool".equals(type)) {
                JsonObject state = part.has("state") && part.get("state").isJsonObject()
                        ? part.getAsJsonObject("state") : new JsonObject();
                String callId = part.has("callID") && !part.get("callID").isJsonNull()
                        ? textOf(part.get("callID")) : string(part, "id");
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
                String error = errorText(state);
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

        if (!hasAbortedNotice && isAbortedTurn(info, parts)) {
            hasAbortedNotice = true;
        }

        if (errorContent.isEmpty() && !hasAbortedNotice && !isAbortedTurn(info, parts)) {
            errorContent = errorText(info);
        }

        List<JsonObject> out = new ArrayList<>();
        if (blocks.size() > 0 || (!hasAbortedNotice && errorContent.isEmpty())) {
            JsonObject assistant = new JsonObject();
            assistant.addProperty("type", "assistant");
            assistant.addProperty("content", text.toString());
            assistant.addProperty("timestamp", timestamp(info));
            assistant.add("raw", raw);
            out.add(assistant);
        }

        out.addAll(toolResults);

        if (hasAbortedNotice) {
            JsonObject notification = new JsonObject();
            notification.addProperty("type", "notification");
            notification.addProperty("content", abortedNoticeText);
            notification.addProperty("timestamp", timestamp(info));
            JsonObject notifRaw = new JsonObject();
            notifRaw.addProperty("type", "notification");
            notifRaw.addProperty("subtype", "stopped");
            notifRaw.addProperty("aborted", true);
            notification.add("raw", notifRaw);
            out.add(notification);
        }

        if (!errorContent.isEmpty()) {
            JsonObject errorMsg = new JsonObject();
            errorMsg.addProperty("type", "error");
            errorMsg.addProperty("content", errorContent);
            errorMsg.addProperty("timestamp", timestamp(info));
            JsonObject errorRaw = new JsonObject();
            errorRaw.addProperty("type", "error");
            if (info.has("error") && info.get("error").isJsonObject()) {
                errorRaw.add("error", info.getAsJsonObject("error").deepCopy());
            }
            String finish = string(info, "finish");
            if (!finish.isEmpty()) {
                errorRaw.addProperty("finish", finish);
            }
            if (isTrue(info, "aborted")) {
                errorRaw.addProperty("aborted", true);
            }
            errorMsg.add("raw", errorRaw);
            out.add(errorMsg);
        }

        return out;
    }

    private static boolean isAbortedPart(JsonObject part) {
        if (isTrue(part, "aborted")) {
            return true;
        }
        if ("text".equals(string(part, "type"))) {
            String txt = textOf(part.get("text")).trim();
            return "已停止生成".equals(txt) || "Generation stopped".equalsIgnoreCase(txt);
        }
        return false;
    }

    private static boolean isAbortedTurn(JsonObject info, JsonArray parts) {
        if (isTrue(info, "aborted")) {
            return true;
        }
        String finish = string(info, "finish");
        if ("interrupted".equalsIgnoreCase(finish) || "abort".equalsIgnoreCase(finish)) {
            return true;
        }
        if (info.has("error") && !info.get("error").isJsonNull()) {
            String errStr = info.get("error").toString().toLowerCase();
            if (errStr.contains("aborted") || errStr.contains("interrupted")) {
                return true;
            }
        }
        if (parts != null) {
            for (JsonElement p : parts) {
                if (p.isJsonObject() && isAbortedPart(p.getAsJsonObject())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static JsonObject createMessage(String type, String content, JsonObject info, JsonArray blocks) {
        JsonObject raw = new JsonObject();
        raw.addProperty("type", type);
        JsonObject message = new JsonObject();
        message.add("content", blocks);
        raw.add("message", message);
        JsonObject out = new JsonObject();
        String id = string(info, "id");
        if (!id.isEmpty()) {
            out.addProperty("id", id);
            raw.addProperty("id", id);
        }
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
                                ? textOf(qObj.get("question"))
                                : textOf(qObj.get("text"));
                        item.addProperty("question", qText);
                        item.addProperty("header", string(qObj, "header"));
                        item.addProperty("multiSelect", qObj.has("multiple") ? qObj.get("multiple").getAsBoolean()
                                : (qObj.has("multiSelect") && qObj.get("multiSelect").getAsBoolean()));
                        item.addProperty("custom", !qObj.has("custom") || qObj.get("custom").getAsBoolean());
                        item.add("options", qObj.has("options") && qObj.get("options").isJsonArray()
                                ? qObj.getAsJsonArray("options") : new JsonArray());
                        if (qObj.has("key")) {
                            item.add("key", qObj.get("key"));
                        }
                        normalizedQuestions.add(item);
                    } else if (qElem.isJsonPrimitive()) {
                        JsonObject item = new JsonObject();
                        item.addProperty("question", textOf(qElem));
                        item.addProperty("header", "");
                        item.addProperty("multiSelect", false);
                        item.addProperty("custom", true);
                        item.add("options", new JsonArray());
                        normalizedQuestions.add(item);
                    }
                }
            } else if (inputObj.has("question") && inputObj.get("question").isJsonPrimitive()) {
                JsonObject item = new JsonObject();
                item.addProperty("question", textOf(inputObj.get("question")));
                item.addProperty("header", string(inputObj, "header"));
                item.addProperty("multiSelect", false);
                item.addProperty("custom", true);
                item.add("options", inputObj.has("options") && inputObj.get("options").isJsonArray()
                        ? inputObj.getAsJsonArray("options") : new JsonArray());
                normalizedQuestions.add(item);
            }
        } else if (rawInput != null && rawInput.isJsonPrimitive()) {
            JsonObject item = new JsonObject();
            item.addProperty("question", textOf(rawInput));
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
        // v2 stores errors as {type, message}; the shared helper reads both
        // that shape and v1's plain string. Using string() here silently
        // turned an aborted question into "unanswered".
        String error = errorText(state);
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
                            if (item.isJsonPrimitive()) {
                                list.add(item.getAsString());
                            }
                        }
                        ansStr = String.join(", ", list);
                    } else if (aElem.isJsonPrimitive()) {
                        ansStr = textOf(aElem);
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

        // Real answers win over an error marker: a tool can briefly report an
        // error while the server is settling, and an answered question must
        // never regress to "cancelled".
        String status = answersObj.size() > 0
                ? "answered"
                : (isCancelled ? "cancelled" : "unanswered");

        JsonObject questionCard = new JsonObject();
        questionCard.addProperty("status", status);
        questionCard.add("questions", normalizedQuestions);
        questionCard.add("answers", answersObj);
        block.add("questionCard", questionCard);
    }

    private static String string(JsonObject obj, String key) {
        return obj != null && obj.has(key) ? textOf(obj.get(key)) : "";
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
