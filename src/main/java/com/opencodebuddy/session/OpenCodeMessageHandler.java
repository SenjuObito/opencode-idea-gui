package com.opencodebuddy.session;

import com.opencodebuddy.permission.PermissionRequest;
import com.opencodebuddy.provider.common.MessageCallback;
import com.opencodebuddy.provider.common.SDKResult;
import com.opencodebuddy.session.ClaudeSession.Message;
import com.opencodebuddy.util.UsageCostCalculator;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenCode message callback handler.
 *
 * <p>Processes the Claude-compatible message events produced by
 * {@link OpenCodeMarkerParser} from the daemon's opencode marker stream:
 * streaming text/thinking deltas, assistant/user/tool messages, usage,
 * session ids, plus opencode-specific permission/question/todo/revert
 * passthrough events.</p>
 */
public class OpenCodeMessageHandler implements MessageCallback {

    private static final Logger LOG = Logger.getInstance(OpenCodeMessageHandler.class);

    private final SessionState state;
    private final CallbackHandler callbackHandler;
    private final MessageMerger messageMerger = new MessageMerger();

    /** Content accumulator for the current assistant message. */
    private final StringBuilder assistantContent = new StringBuilder();

    /** Current assistant message object being processed. */
    private Message currentAssistantMessage = null;

    private boolean isStreaming = false;
    private boolean streamEndedThisTurn = false;

    public OpenCodeMessageHandler(SessionState state, CallbackHandler callbackHandler) {
        this.state = state;
        this.callbackHandler = callbackHandler;
    }

    @Override
    public void onMessage(String type, String content) {
        LOG.debug("OpenCodeMessageHandler.onMessage: type=" + type
                + ", content length=" + (content != null ? content.length() : 0));

        switch (type) {
            case "assistant" -> handleAssistantMessage(content);
            case "user" -> handleUserMessage(content);
            case "session_id" -> handleSessionId(content);
            case "stream_start" -> handleStreamStart();
            case "stream_end" -> handleStreamEnd();
            case "thinking_delta" -> handleThinkingDelta(content);
            case "content_delta", "content" -> handleContentDelta(content);
            case "status" -> {
                if (content != null && !content.trim().isEmpty()) {
                    callbackHandler.notifyStatusMessage(content);
                }
            }
            case "node_log" -> {
                if (content != null && !content.isBlank()) {
                    callbackHandler.notifyNodeLog(content);
                }
            }
            case "usage" -> handleUsage(content);
            case "message_start", "message_end", "block_reset", "tool_result" -> {
                // Stream lifecycle markers; cleanup is driven by stream_end/onComplete.
            }
            case "permission_request" -> handlePermissionRequest(content);
            case "permission_closed" -> handlePermissionClosed(content);
            case "question_request" -> handleQuestionRequest(content);
            case "question_closed" -> handleQuestionClosed(content);
            case "todo_updated" -> handleTodoUpdated(content);
            case "revert_state" -> handleRevertState(content);
            case "session_compact_result" -> handleSessionCompactResult(content);
            default -> LOG.debug("OpenCodeMessageHandler: Unhandled message type: " + type);
        }
    }

    @Override
    public void onError(String error) {
        boolean wasStreaming = isStreaming;
        isStreaming = false;
        streamEndedThisTurn = false;
        state.setError(error);
        state.setBusy(false);
        state.setLoading(false);

        Message errorMessage = new Message(Message.Type.ERROR, error);
        state.addMessage(errorMessage);

        // Signal stream-end BEFORE pushing the error snapshot so the webview's
        // onStreamEnd cancellation drops the pending snapshot and the error
        // bubble renders without a duplicate "API request failed" flash.
        if (wasStreaming) {
            callbackHandler.notifyStreamEnd();
        }
        callbackHandler.notifyMessageUpdate(state.getMessages());
        resetStreamingAccumulator();
        callbackHandler.notifyStateChange(state.isBusy(), state.isLoading(), state.getError());
    }

    @Override
    public void onComplete(SDKResult result) {
        boolean streamEndedBeforeComplete = streamEndedThisTurn;
        boolean wasStreaming = isStreaming;

        isStreaming = false;
        streamEndedThisTurn = false;
        state.setBusy(false);
        state.setLoading(false);
        state.updateLastModifiedTime();

        if (wasStreaming && !streamEndedBeforeComplete) {
            LOG.warn("OpenCode onComplete called without prior stream_end; forcing stream cleanup");
            callbackHandler.notifyMessageUpdate(state.getMessages());
            callbackHandler.notifyStreamEnd();
        }

        resetStreamingAccumulator();
        recordSessionInHistoryIndex();
        callbackHandler.notifyStateChange(state.isBusy(), state.isLoading(), state.getError());
    }

    /**
     * Record/update this session in the plugin-side history index so the
     * history view can list sessions without an opencode session-list command.
     */
    private void recordSessionInHistoryIndex() {
        String sessionId = state.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            List<Message> messages = state.getMessages();
            String title = null;
            long firstTimestamp = 0;
            for (Message message : messages) {
                if (message.type == Message.Type.USER) {
                    title = truncateTitle(message.content);
                    firstTimestamp = message.timestamp;
                    break;
                }
            }
            long lastTimestamp = 0;
            for (Message message : messages) {
                if (message.timestamp > lastTimestamp) {
                    lastTimestamp = message.timestamp;
                }
            }
            com.opencodebuddy.cache.OpenCodeSessionIndex.getInstance().upsert(
                    sessionId,
                    title,
                    state.getModel(),
                    state.getCwd(),
                    firstTimestamp,
                    lastTimestamp > 0 ? lastTimestamp : state.getLastModifiedTime(),
                    messages.size());
        } catch (Exception e) {
            LOG.debug("Failed to record session in history index: " + e.getMessage());
        }
    }

    private static String truncateTitle(String content) {
        if (content == null) {
            return null;
        }
        String singleLine = content.replaceAll("\\s+", " ").trim();
        if (singleLine.isEmpty()) {
            return null;
        }
        return singleLine.length() <= 80 ? singleLine : singleLine.substring(0, 80);
    }

    // ===== Message events =====

    private void handleAssistantMessage(String jsonContent) {
        try {
            com.google.gson.Gson gson = new com.google.gson.Gson();
            com.google.gson.JsonObject msgJson = gson.fromJson(jsonContent, com.google.gson.JsonObject.class);

            Message parsed = parseServerMessage(msgJson, Message.Type.ASSISTANT);
            if (parsed == null) {
                LOG.debug("OpenCode assistant message filtered out");
                return;
            }

            if (currentAssistantMessage != null) {
                com.google.gson.JsonObject mergedRaw = messageMerger.mergeAssistantMessage(
                        currentAssistantMessage.raw, parsed.raw);
                currentAssistantMessage.content = parsed.content;
                currentAssistantMessage.raw = mergedRaw;
                assistantContent.setLength(0);
                assistantContent.append(parsed.content != null ? parsed.content : "");
            } else {
                state.addMessage(parsed);
            }
            callbackHandler.notifyMessageUpdate(state.getMessages());
        } catch (Exception e) {
            LOG.warn("Failed to parse assistant message: " + e.getMessage());
        }
    }

    private void handleUserMessage(String jsonContent) {
        try {
            com.google.gson.Gson gson = new com.google.gson.Gson();
            com.google.gson.JsonObject msgJson = gson.fromJson(jsonContent, com.google.gson.JsonObject.class);

            Message parsed = parseServerMessage(msgJson, Message.Type.USER);
            if (parsed == null) {
                LOG.debug("OpenCode user message filtered out");
                return;
            }

            state.addMessage(parsed);
            callbackHandler.notifyMessageUpdate(state.getMessages());
        } catch (Exception e) {
            LOG.warn("Failed to parse user message: " + e.getMessage());
        }
    }

    private void handleSessionId(String sessionId) {
        if (sessionId != null && !sessionId.trim().isEmpty()) {
            state.setSessionId(sessionId.trim());
            callbackHandler.notifySessionIdReceived(sessionId.trim());
            LOG.info("Captured opencode session id: " + sessionId.trim());
        }
    }

    private void handleUsage(String jsonContent) {
        try {
            com.google.gson.Gson gson = new com.google.gson.Gson();
            com.google.gson.JsonObject usage = gson.fromJson(jsonContent, com.google.gson.JsonObject.class);
            if (usage == null) {
                return;
            }

            // opencode usage: {input_tokens, output_tokens, reasoning_tokens,
            // cache_read, cache_creation} — normalize to the Claude usage schema.
            int input = readInt(usage, "input_tokens");
            int output = readInt(usage, "output_tokens");
            int cacheRead = readInt(usage, "cache_read_input_tokens", "cache_read");
            int cacheCreation = readInt(usage, "cache_creation_input_tokens", "cache_creation");

            com.google.gson.JsonObject turnUsage = new com.google.gson.JsonObject();
            turnUsage.addProperty("input_tokens", Math.max(0, input - cacheRead));
            turnUsage.addProperty("cache_creation_input_tokens", cacheCreation);
            turnUsage.addProperty("cache_read_input_tokens", cacheRead);
            turnUsage.addProperty("output_tokens", output + readInt(usage, "reasoning_tokens"));

            boolean updated = attachUsageToLastAssistant(turnUsage);
            if (updated) {
                callbackHandler.notifyMessageUpdate(state.getMessages());
            } else {
                LOG.debug("OpenCode usage received but no assistant message to attach");
            }
        } catch (Exception e) {
            LOG.debug("Failed to parse OpenCode usage: " + e.getMessage());
        }
    }

    private boolean attachUsageToLastAssistant(com.google.gson.JsonObject turnUsage) {
        java.util.List<Message> messages = state.getMessagesReference();
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if (msg.type == Message.Type.ASSISTANT && msg.raw != null) {
                msg.raw.add("turnUsage", turnUsage);
                Double turnCostUsd = UsageCostCalculator.calculateTurnCostUsd(
                        SessionProviderRouter.PROVIDER_ID, turnUsage, state.getModel());
                if (turnCostUsd != null) {
                    msg.raw.addProperty("turnCostUsd", turnCostUsd);
                }
                return true;
            }
        }
        return false;
    }

    private static int readInt(JsonObject json, String... keys) {
        for (String key : keys) {
            if (json.has(key) && !json.get(key).isJsonNull()) {
                return Math.max(0, json.get(key).getAsInt());
            }
        }
        return 0;
    }

    // ===== opencode-specific events =====

    private void handlePermissionRequest(String jsonContent) {
        try {
            com.google.gson.Gson gson = new com.google.gson.Gson();
            JsonObject payload = gson.fromJson(jsonContent, JsonObject.class);
            if (payload == null) {
                return;
            }
            String permissionId = stringOrNull(payload, "permissionId");
            String toolName = stringOrNull(payload, "toolName");
            Map<String, Object> inputs = new HashMap<>();
            if (payload.has("inputs") && payload.get("inputs").isJsonObject()) {
                for (Map.Entry<String, com.google.gson.JsonElement> e
                        : payload.getAsJsonObject("inputs").entrySet()) {
                    if (e.getValue().isJsonPrimitive()) {
                        inputs.put(e.getKey(), e.getValue().getAsString());
                    }
                }
            }
            // Route through the shared permission pipeline: the callback adapter
            // forwards to PermissionHandler.showPermissionDialog, and the reply
            // is bridged back to opencode.replyPermission by the window.
            PermissionRequest request = new PermissionRequest(
                    permissionId != null ? permissionId : "", toolName, inputs, null);
            LOG.info("[OpenCode] permission requested: tool=" + toolName
                    + " permissionId=" + permissionId);
            callbackHandler.notifyPermissionRequested(request);
        } catch (Exception e) {
            LOG.warn("Failed to parse permission request: " + e.getMessage());
        }
    }

    private void handlePermissionClosed(String jsonContent) {
        LOG.info("[OpenCode] permission closed: " + jsonContent);
    }

    private void handleQuestionRequest(String jsonContent) {
        LOG.info("[OpenCode] question requested (webview dialog): " + jsonContent);
        // Forward the raw normalized payload so the webview question dialog can
        // render it once the webview side is wired (window.onQuestionRequested).
        callbackHandler.notifyTaskEvent(jsonContent);
    }

    private void handleQuestionClosed(String jsonContent) {
        LOG.info("[OpenCode] question closed: " + jsonContent);
    }

    private void handleTodoUpdated(String jsonContent) {
        callbackHandler.notifyTaskEvent(jsonContent);
    }

    private void handleRevertState(String jsonContent) {
        LOG.debug("[OpenCode] revert state: " + jsonContent);
    }

    private void handleSessionCompactResult(String jsonContent) {
        LOG.info("[OpenCode] session compact result: " + jsonContent);
    }

    // ===== Streaming =====

    private void handleContentDelta(String content) {
        if (content == null || content.isEmpty()) {
            return;
        }

        assistantContent.append(content);

        if (currentAssistantMessage == null) {
            currentAssistantMessage = new Message(Message.Type.ASSISTANT, assistantContent.toString());
            state.addMessage(currentAssistantMessage);
        } else {
            currentAssistantMessage.content = assistantContent.toString();
        }

        callbackHandler.notifyContentDelta(content);
        callbackHandler.notifyMessageUpdate(state.getMessages());
    }

    private void handleThinkingDelta(String content) {
        if (content == null || content.isEmpty()) {
            return;
        }

        ensureCurrentAssistantMessageExists();
        applyThinkingDeltaToRaw(content);
        callbackHandler.notifyThinkingDelta(content);
    }

    private void ensureCurrentAssistantMessageExists() {
        if (currentAssistantMessage == null) {
            com.google.gson.JsonObject raw = new com.google.gson.JsonObject();
            raw.addProperty("type", "assistant");
            com.google.gson.JsonObject messageObj = new com.google.gson.JsonObject();
            messageObj.add("content", new com.google.gson.JsonArray());
            raw.add("message", messageObj);
            currentAssistantMessage = new Message(Message.Type.ASSISTANT, "", raw);
            state.addMessage(currentAssistantMessage);
        }
        if (currentAssistantMessage.raw == null) {
            com.google.gson.JsonObject raw = new com.google.gson.JsonObject();
            raw.addProperty("type", "assistant");
            com.google.gson.JsonObject messageObj = new com.google.gson.JsonObject();
            messageObj.add("content", new com.google.gson.JsonArray());
            raw.add("message", messageObj);
            currentAssistantMessage.raw = raw;
        }
    }

    private void applyThinkingDeltaToRaw(String delta) {
        com.google.gson.JsonObject raw = currentAssistantMessage.raw;
        com.google.gson.JsonObject message = raw.has("message") && raw.get("message").isJsonObject()
                ? raw.getAsJsonObject("message")
                : new com.google.gson.JsonObject();
        com.google.gson.JsonArray content = message.has("content") && message.get("content").isJsonArray()
                ? message.getAsJsonArray("content")
                : new com.google.gson.JsonArray();

        com.google.gson.JsonObject target = null;
        if (content.size() > 0) {
            com.google.gson.JsonElement last = content.get(content.size() - 1);
            if (last.isJsonObject()) {
                com.google.gson.JsonObject block = last.getAsJsonObject();
                if (block.has("type") && "thinking".equals(block.get("type").getAsString())) {
                    target = block;
                }
            }
        }

        if (target == null) {
            target = new com.google.gson.JsonObject();
            target.addProperty("type", "thinking");
            target.addProperty("thinking", "");
            target.addProperty("text", "");
            content.add(target);
        }

        String existing = target.has("thinking") && !target.get("thinking").isJsonNull()
                ? target.get("thinking").getAsString()
                : "";
        String next = existing + delta;
        target.addProperty("thinking", next);
        target.addProperty("text", next);

        message.add("content", content);
        raw.add("message", message);
        currentAssistantMessage.raw = raw;
    }

    private void handleStreamStart() {
        isStreaming = true;
        streamEndedThisTurn = false;
        resetStreamingAccumulator();
        callbackHandler.notifyStreamStart();
    }

    private void handleStreamEnd() {
        if (!isStreaming && streamEndedThisTurn) {
            return;
        }

        isStreaming = false;
        streamEndedThisTurn = true;
        callbackHandler.notifyMessageUpdate(state.getMessages());
        callbackHandler.notifyStreamEnd();
        state.setBusy(false);
        state.setLoading(false);
        state.updateLastModifiedTime();
        resetStreamingAccumulator();
        callbackHandler.notifyStateChange(state.isBusy(), state.isLoading(), state.getError());
    }

    private void resetStreamingAccumulator() {
        assistantContent.setLength(0);
        currentAssistantMessage = null;
    }

    // ===== Parsing helpers =====

    private Message parseServerMessage(com.google.gson.JsonObject msg, Message.Type messageType) {
        if (msg.has("isMeta") && msg.get("isMeta").getAsBoolean()) {
            return null;
        }

        String content = extractMessageContent(msg);
        if (messageType == Message.Type.USER) {
            return buildUserMessage(msg, content);
        }

        Message result = new Message(messageType, content != null ? content : "");
        result.raw = msg;
        return result;
    }

    private Message buildUserMessage(com.google.gson.JsonObject msg, String content) {
        if (content != null && content.equals("[tool_result]")) {
            Message result = new Message(Message.Type.USER, "[tool_result]");
            result.raw = msg;
            return result;
        }
        if (content == null || content.trim().isEmpty()) {
            if (containsToolResult(msg)) {
                Message result = new Message(Message.Type.USER, "[tool_result]");
                result.raw = msg;
                return result;
            }
            return null;
        }
        Message result = new Message(Message.Type.USER, content);
        result.raw = msg;
        return result;
    }

    private String extractMessageContent(com.google.gson.JsonObject msg) {
        if (!msg.has("message")) {
            if (msg.has("content")) {
                return extractContentFromElement(msg.get("content"));
            }
            return "";
        }

        com.google.gson.JsonObject message = msg.getAsJsonObject("message");
        if (!message.has("content") || message.get("content").isJsonNull()) {
            return "";
        }
        return extractContentFromElement(message.get("content"));
    }

    private String extractContentFromElement(com.google.gson.JsonElement contentElement) {
        if (contentElement.isJsonPrimitive()) {
            return contentElement.getAsString();
        }

        if (contentElement.isJsonArray()) {
            com.google.gson.JsonArray contentArray = contentElement.getAsJsonArray();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < contentArray.size(); i++) {
                com.google.gson.JsonElement element = contentArray.get(i);
                if (element.isJsonObject()) {
                    com.google.gson.JsonObject block = element.getAsJsonObject();
                    String blockType = (block.has("type") && !block.get("type").isJsonNull())
                            ? block.get("type").getAsString() : null;
                    if (("text".equals(blockType) || "input_text".equals(blockType) || "output_text".equals(blockType))
                            && block.has("text") && !block.get("text").isJsonNull()) {
                        String text = block.get("text").getAsString();
                        if (sb.length() > 0) {
                            sb.append("\n");
                        }
                        sb.append(text);
                    }
                    // tool_use / tool_result / thinking / image blocks: no plain text
                } else if (element.isJsonPrimitive()) {
                    String text = element.getAsString();
                    if (text != null && !text.trim().isEmpty()) {
                        if (sb.length() > 0) {
                            sb.append("\n");
                        }
                        sb.append(text);
                    }
                }
            }
            return sb.toString();
        }

        if (contentElement.isJsonObject()) {
            com.google.gson.JsonObject contentObj = contentElement.getAsJsonObject();
            if (contentObj.has("text") && !contentObj.get("text").isJsonNull()) {
                return contentObj.get("text").getAsString();
            }
        }

        return "";
    }

    private boolean containsToolResult(com.google.gson.JsonObject msg) {
        com.google.gson.JsonElement contentElement = getMessageContentElement(msg);
        if (contentElement == null || !contentElement.isJsonArray()) {
            return false;
        }
        for (int i = 0; i < contentElement.getAsJsonArray().size(); i++) {
            com.google.gson.JsonElement element = contentElement.getAsJsonArray().get(i);
            if (element.isJsonObject()) {
                com.google.gson.JsonObject block = element.getAsJsonObject();
                if (block.has("type") && "tool_result".equals(block.get("type").getAsString())) {
                    return true;
                }
            }
        }
        return false;
    }

    private com.google.gson.JsonElement getMessageContentElement(com.google.gson.JsonObject msg) {
        if (msg.has("message") && msg.get("message").isJsonObject()) {
            com.google.gson.JsonObject message = msg.getAsJsonObject("message");
            if (message.has("content") && !message.get("content").isJsonNull()) {
                return message.get("content");
            }
        }
        if (msg.has("content") && !msg.get("content").isJsonNull()) {
            return msg.get("content");
        }
        return null;
    }

    private static String stringOrNull(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }
}
