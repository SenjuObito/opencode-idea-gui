package com.opencodebuddy.session;

import com.opencodebuddy.handler.provider.ModelProviderHandler;
import com.opencodebuddy.permission.PermissionRequest;
import com.opencodebuddy.provider.common.MessageCallback;
import com.opencodebuddy.provider.common.SDKResult;
import com.opencodebuddy.session.OpencodeSession.Message;
import com.opencodebuddy.util.UsageCostCalculator;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.util.HashMap;
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

    /**
     * Streaming segment activity tracking (session-scoped).
     * Tool calls act as segment boundaries: subsequent text or thinking deltas
     * will open a new block at the tail instead of merging backward into
     * blocks that preceded the tools.
     */
    private volatile boolean textSegmentActive = false;
    private volatile boolean thinkingSegmentActive = false;

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
            case "retry_status" -> handleRetryStatus(content);
            case "turn_error" -> handleTurnError(content);
            case "node_log" -> {
                if (content != null && !content.isBlank()) {
                    callbackHandler.notifyNodeLog(content);
                }
            }
            case "usage" -> handleUsage(content);
            case "message_start", "message_end", "block_reset", "tool_result" -> {
                resetStreamingAccumulator();
            }
            case "permission_request" -> handlePermissionRequest(content);
            case "permission_closed" -> handlePermissionClosed(content);
            case "question_request" -> handleQuestionRequest(content);
            case "question_closed" -> handleQuestionClosed(content);
            case "todo_updated" -> handleTodoUpdated(content);
            case "session_title" -> handleSessionTitle(content);
            case "revert_state" -> handleRevertState(content);
            case "message_removed" -> handleMessagesRemoved(content);
            case "turn_end" -> handleTurnEnd(content);
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

        // Always signal stream-end on error to guarantee streamCoalescer and frontend
        // isStreamingRef are cleared, allowing showLoading(false) to take effect immediately.
        callbackHandler.notifyStreamEnd();
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
        callbackHandler.notifyStateChange(state.isBusy(), state.isLoading(), state.getError());
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
                currentAssistantMessage.raw = mergedRaw;
                String fullText = extractMessageContent(mergedRaw);
                if (fullText != null && !fullText.isEmpty()) {
                    currentAssistantMessage.content = fullText;
                    assistantContent.setLength(0);
                    assistantContent.append(fullText);
                } else if (parsed.content != null && !parsed.content.isEmpty()) {
                    currentAssistantMessage.content = parsed.content;
                    assistantContent.setLength(0);
                    assistantContent.append(parsed.content);
                }
            } else {
                currentAssistantMessage = parsed;
                String fullText = extractMessageContent(parsed.raw);
                currentAssistantMessage.content = (fullText != null && !fullText.isEmpty())
                        ? fullText
                        : (parsed.content != null ? parsed.content : "");
                assistantContent.setLength(0);
                assistantContent.append(currentAssistantMessage.content);
                state.addMessage(parsed);
            }

            syncSegmentActivity(currentAssistantMessage.raw);

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

            if (!"[tool_result]".equals(parsed.content)) {
                resetStreamingAccumulator();
            } else {
                // tool_result closes any active text/thinking segment
                textSegmentActive = false;
                thinkingSegmentActive = false;
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
            turnUsage.addProperty("input_tokens", input);
            turnUsage.addProperty("cache_creation_input_tokens", cacheCreation);
            turnUsage.addProperty("cache_read_input_tokens", cacheRead);
            turnUsage.addProperty("output_tokens", output + readInt(usage, "reasoning_tokens"));

            int usedTokens = input + cacheRead + cacheCreation;
            // Provider-aware lookup: the single-argument overload would skip the
            // user's custom windows and the live model catalog.
            int maxTokens = ModelProviderHandler.getModelContextLimit(
                    state.getProvider(), state.getModel());
            callbackHandler.notifyUsageUpdate(usedTokens, maxTokens);

            boolean updated = attachUsageToLastAssistant(turnUsage, usage);
            if (updated) {
                callbackHandler.notifyMessageUpdate(state.getMessages());
            } else {
                LOG.debug("OpenCode usage received but no assistant message to attach");
            }
        } catch (Exception e) {
            LOG.debug("Failed to parse OpenCode usage: " + e.getMessage());
        }
    }

    private boolean attachUsageToLastAssistant(com.google.gson.JsonObject turnUsage, com.google.gson.JsonObject usage) {
        java.util.List<Message> messages = state.getMessagesReference();
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if (msg.type == Message.Type.ASSISTANT) {
                if (msg.raw == null) {
                    msg.raw = new com.google.gson.JsonObject();
                }
                msg.raw.add("turnUsage", turnUsage);
                if (usage != null && usage.has("cost") && !usage.get("cost").isJsonNull()) {
                    try {
                        double cost = usage.get("cost").getAsDouble();
                        if (cost > 0) {
                            msg.raw.addProperty("turnCostUsd", cost);
                        }
                    } catch (Exception ignored) {
                    }
                }
                if (!msg.raw.has("turnCostUsd")) {
                    Double turnCostUsd = UsageCostCalculator.calculateTurnCostUsd(
                            SessionProviderRouter.PROVIDER_ID, turnUsage, state.getModel());
                    if (turnCostUsd != null) {
                        msg.raw.addProperty("turnCostUsd", turnCostUsd);
                    }
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

            // opencode permission dialogs: permission ids (per_...) are consumed
            // by OpencodePermissionRegistry, keyed by the permissionId (channelId)
            // so the decision funnel in OpencodeSession can resolve the request.
            String channelId = permissionId != null ? permissionId : "";

            // The daemon maps opencode's {permission, patterns} onto
            // {tool: action, inputs: {command, patterns}}. The dialog's i18n
            // titles (permission.tools.*) use capitalized tool names like
            // "Read"/"Write", while opencode actions are lowercase like
            // "read"/"write". Without this mapping the title falls back to
            // "execute read" and the command box shows just the action word.
            Map<String, Object> dialogInputs = new HashMap<>(inputs);
            String dialogToolName = toolName;
            if (toolName != null) {
                String action = toolName.toLowerCase();
                if ("read".equals(action)) {
                    dialogToolName = "Read";
                } else if ("write".equals(action)) {
                    dialogToolName = "Write";
                } else if ("edit".equals(action)) {
                    dialogToolName = "Edit";
                } else if ("list".equals(action) || "glob".equals(action)) {
                    dialogToolName = "Glob";
                } else if ("grep".equals(action)) {
                    dialogToolName = "Grep";
                } else if ("bash".equals(action)) {
                    dialogToolName = "Bash";
                }
            }

            // The command box renders `command` (fallback: content/text); the
            // daemon put the action word there, which reads as "read". The
            // normalized payload's `description` is the joined resource
            // patterns (e.g. ".env" or a bash command) — the thing the user is
            // actually approving — so show that instead.
            String description = stringOrNull(payload, "description");
            if (description != null && !description.isBlank()) {
                dialogInputs.put("command", description);
            }

            // The session the prompt belongs to. Required: the dialog must be
            // shown for THIS session, never for whatever tab happens to be
            // active. Falls back to this handler's own session only because the
            // daemon may omit it in older payload shapes — and this handler is
            // already session-scoped, so that fallback cannot cross sessions.
            String payloadSessionId = stringOrNull(payload, "sessionId");
            String requestSessionId = (payloadSessionId != null && !payloadSessionId.isBlank())
                    ? payloadSessionId
                    : state.getSessionId();

            // Route through the shared permission pipeline: the callback adapter
            // forwards to PermissionHandler.showPermissionDialog, and the reply
            // is bridged back to opencode.replyPermission by the window.
            PermissionRequest request = new PermissionRequest(
                    channelId, requestSessionId, dialogToolName, dialogInputs, null, null);
            LOG.info("[OpenCode] permission requested: tool=" + toolName
                    + " permissionId=" + permissionId
                    + " session=" + (requestSessionId != null ? requestSessionId : "(none)"));

            // Capture the session/directory context for the reply — opencode's
            // reply endpoint is workspace-scoped, so the decision must be routed
            // with the directory that was active when the server asked.
            OpencodePermissionRegistry.register(
                    channelId,
                    requestSessionId,
                    state.getCwd());

            callbackHandler.notifyPermissionRequested(request);
        } catch (Exception e) {
            LOG.warn("Failed to parse permission request: " + e.getMessage());
        }
    }

    private void handlePermissionClosed(String jsonContent) {
        LOG.info("[OpenCode] permission closed: " + jsonContent);
        // Server resolved/aborted the request (timeout, session abort, another
        // client replied). Drop our registration so a late dialog decision
        // doesn't fire a reply at a dead request id.
        try {
            com.google.gson.Gson gson = new com.google.gson.Gson();
            JsonObject payload = gson.fromJson(jsonContent, JsonObject.class);
            if (payload != null) {
                OpencodePermissionRegistry.remove(stringOrNull(payload, "requestID"));
                OpencodePermissionRegistry.remove(stringOrNull(payload, "permissionId"));
            }
        } catch (Exception e) {
            LOG.warn("[OpenCode] permission closed parse failed: " + e.getMessage());
        }
        callbackHandler.notifyPromptClosed("permission", jsonContent);
    }

    private void handleQuestionRequest(String jsonContent) {
        LOG.info("[OpenCode] question requested (webview dialog): " + jsonContent);
        callbackHandler.notifyQuestionRequested(jsonContent);
    }

    private void handleQuestionClosed(String jsonContent) {
        LOG.info("[OpenCode] question closed: " + jsonContent);
        callbackHandler.notifyPromptClosed("question", jsonContent);
    }

    /**
     * The daemon's authoritative, session-scoped turn terminal ({@code [TURN_END]}).
     *
     * <p>Payload: {@code {"sessionId":"ses_…","turnId":1042,"reason":"…"}}.</p>
     *
     * <p>This is the signal that clears a session's running state, and it is
     * deliberately independent of "which session is on screen" and of whether a
     * {@code [STREAM_END]} ever arrived. Before it existed, the only guaranteed
     * terminal was the request's {@code done} ack — so an interrupted turn whose
     * server never replied left the spinner running for the full 180 s local
     * timeout, and a turn that ended while its tab was in the background could
     * not be cleared at all.</p>
     */
    private void handleTurnEnd(String jsonContent) {
        if (jsonContent == null || jsonContent.isBlank()) {
            return;
        }
        try {
            JsonObject payload = com.google.gson.JsonParser.parseString(jsonContent.trim()).getAsJsonObject();
            String sessionId = stringOrNull(payload, "sessionId");
            String reason = stringOrNull(payload, "reason");
            String ownSessionId = state.getSessionId();
            // This handler already belongs to one session (it is fed by that
            // session's stream router), but the directory-scoped daemon stream
            // can carry a sibling session's terminal — never clear this one for it.
            if (sessionId != null && ownSessionId != null && !sessionId.equals(ownSessionId)) {
                LOG.debug("[OpenCode] Ignoring turn_end for another session: " + sessionId);
                return;
            }
            LOG.info("[OpenCode] turn ended: session=" + (sessionId != null ? sessionId : "(own)")
                    + " reason=" + (reason != null ? reason : "unknown")
                    + " turnId=" + (payload.has("turnId") && !payload.get("turnId").isJsonNull()
                            ? payload.get("turnId").getAsString() : "-"));

            isStreaming = false;
            streamEndedThisTurn = true;
            state.setBusy(false);
            state.setLoading(false);
            state.updateLastModifiedTime();
            resetStreamingAccumulator();

            callbackHandler.notifyTurnEnd(jsonContent);
            callbackHandler.notifyStateChange(state.isBusy(), state.isLoading(), state.getError());
        } catch (Exception e) {
            LOG.warn("[OpenCode] Failed to parse turn_end payload: " + jsonContent, e);
        }
    }

    private void handleTodoUpdated(String jsonContent) {
        callbackHandler.notifyTodoUpdated(jsonContent);
    }

    private void handleSessionTitle(String jsonContent) {
        if (jsonContent == null || jsonContent.isBlank()) {
            return;
        }
        try {
            JsonObject obj = com.google.gson.JsonParser.parseString(jsonContent).getAsJsonObject();
            String sessionId = stringOrNull(obj, "sessionId");
            String title = stringOrNull(obj, "title");
            if (sessionId != null && !sessionId.isBlank() && title != null && !title.isBlank()) {
                callbackHandler.notifySessionTitleReceived(sessionId, title);
            }
        } catch (Exception e) {
            LOG.warn("OpenCodeMessageHandler: Failed to parse session_title payload: " + jsonContent, e);
        }
    }

    private void handleRevertState(String jsonContent) {
        LOG.debug("[OpenCode] revert state: " + jsonContent);
        if (jsonContent == null || jsonContent.isBlank()) {
            return;
        }
        try {
            JsonObject payload = parseJsonObjectLeniently(jsonContent.trim());
            if (payload == null) {
                return;
            }
            boolean hasRevert = payload.has("hasRevert") && !payload.get("hasRevert").isJsonNull()
                    && payload.get("hasRevert").getAsBoolean();
            // Carries the opencode message id the revert is anchored to. The webview
            // needs it to slice the transcript at the right boundary; without it the
            // placeholder bar falls back to "last user message" and mis-anchors.
            String messageId = stringOrNull(payload, "messageId");
            if (hasRevert) {
                state.setRevertState(new SessionState.RevertState(messageId != null ? messageId : ""));
            } else {
                state.setRevertState(null);
            }
            callbackHandler.notifyRevertStateUpdate(hasRevert, messageId);
            callbackHandler.notifyStateChange(state.isBusy(), state.isLoading(), state.getError());
        } catch (Exception e) {
            LOG.warn("[OpenCode] Failed to parse revert_state: " + jsonContent, e);
        }
    }

    /**
     * Handle an authoritative server-side message deletion.
     *
     * <p>opencode's revert only writes a "void from here on" pointer. The real
     * deletion happens at the start of the next prompt, where
     * {@code SessionRevert.cleanup} drops every message from the revert point
     * onward and publishes one {@code message.removed} per message.</p>
     *
     * <p>Mirroring that removal here is what keeps the host in sync with the
     * server. Without it the host keeps carrying the voided messages, and the
     * snapshot it pushes on the next send re-injects them into the webview — the
     * "reverted messages come back to life" symptom.</p>
     */
    private void handleMessagesRemoved(String jsonContent) {
        if (jsonContent == null || jsonContent.isBlank()) {
            return;
        }
        try {
            JsonObject payload = parseJsonObjectLeniently(jsonContent.trim());
            if (payload == null) {
                return;
            }
            // This daemon stream is scoped per directory, so a removal belonging to a
            // sibling session must not touch the active one.
            String sessionId = stringOrNull(payload, "sessionID");
            String currentSessionId = state.getSessionId();
            if (sessionId != null && currentSessionId != null && !sessionId.equals(currentSessionId)) {
                LOG.debug("[OpenCode] Ignoring message_removed for another session: " + sessionId);
                return;
            }
            String messageId = stringOrNull(payload, "messageID");
            if (messageId == null || messageId.isBlank()) {
                return;
            }
            java.util.List<String> ids = java.util.List.of(messageId);
            if (!state.removeMessagesByIds(ids)) {
                LOG.debug("[OpenCode] message_removed matched nothing in state: " + messageId);
                return;
            }
            LOG.info("[OpenCode] Removed reverted message from session state: " + messageId);
            // Order matters: tell the webview to drop it first, then push the
            // (already shorter) snapshot. The early removal keeps the webview's list
            // length in step with the snapshot so its shrink-protection does not
            // restore the voided tail.
            callbackHandler.notifyMessagesRemoved(ids);
            callbackHandler.notifyMessageUpdate(state.getMessages());
        } catch (Exception e) {
            LOG.warn("[OpenCode] Failed to parse message_removed: " + jsonContent, e);
        }
    }

    /**
     * Parse a marker payload that should be a JSON object, tolerating a legacy
     * double-encoded form (a JSON string literal wrapping the object). Returns
     * null when neither shape yields an object.
     */
    private static JsonObject parseJsonObjectLeniently(String raw) {
        try {
            return com.google.gson.JsonParser.parseString(raw).getAsJsonObject();
        } catch (Exception ignored) {
            // Fall through to the string-literal form.
        }
        try {
            String decoded = new com.google.gson.Gson().fromJson(raw, String.class);
            if (decoded == null || decoded.isBlank()) {
                return null;
            }
            return com.google.gson.JsonParser.parseString(decoded).getAsJsonObject();
        } catch (Exception ignored) {
            return null;
        }
    }

    private void handleSessionCompactResult(String jsonContent) {
        LOG.info("[OpenCode] session compact result: " + jsonContent);
    }

    /**
     * A rate-limit / quota retry reported by the server while a turn is still
     * running ({@code session.status} with {@code type=retry}).
     *
     * <p>This is the only signal that explains a turn that is "spinning" for
     * minutes: opencode is backing off because the free tier / account quota is
     * exhausted, and its {@code action} carries the human-readable reason plus a
     * subscribe/settings link. It used to be emitted by the daemon but never
     * parsed host-side, so the user only ever saw the generic local
     * timeout afterwards.</p>
     */
    private void handleRetryStatus(String jsonContent) {
        if (jsonContent == null || jsonContent.isBlank()) {
            return;
        }
        LOG.info("[OpenCode] retry status: " + jsonContent);
        callbackHandler.notifyRetryStatus(jsonContent);
    }

    /**
     * A turn failure that arrived after a local timeout already reported one.
     *
     * <p>The daemon's fallback deletes the active turn, so the server's
     * late {@code session.error} has nothing left to settle it. It carries the
     * real reason, so it is surfaced as a correction of the generic timeout
     * message instead of being dropped (which is what routing it through the
     * compaction-result channel effectively did).</p>
     */
    private void handleTurnError(String jsonContent) {
        if (jsonContent == null || jsonContent.isBlank()) {
            return;
        }
        String message = jsonContent;
        try {
            JsonObject payload = com.google.gson.JsonParser.parseString(jsonContent).getAsJsonObject();
            String extracted = stringOrNull(payload, "message");
            if (extracted != null && !extracted.isBlank()) {
                message = extracted;
            }
        } catch (Exception ignored) {
            // Not a JSON object — fall back to the raw payload as the message.
        }

        LOG.info("[OpenCode] turn error (late): " + message);
        state.setError(message);
        state.setBusy(false);
        state.setLoading(false);
        state.replaceLastErrorMessage(message);
        callbackHandler.notifyStreamEnd();
        callbackHandler.notifyMessageUpdate(state.getMessages());
        callbackHandler.notifyStateChange(state.isBusy(), state.isLoading(), state.getError());
    }

    // ===== Streaming =====

    private void handleContentDelta(String content) {
        if (content == null || content.isEmpty()) {
            return;
        }

        // Content output means thinking segment has ended
        thinkingSegmentActive = false;

        assistantContent.append(content);

        ensureCurrentAssistantMessageExists();
        currentAssistantMessage.content = assistantContent.toString();
        applyContentDeltaToRaw(content);

        callbackHandler.notifyContentDelta(content);
        callbackHandler.notifyMessageUpdate(state.getMessages());
    }

    private void handleThinkingDelta(String content) {
        if (content == null || content.isEmpty()) {
            return;
        }

        // Thinking delta means text segment has ended
        textSegmentActive = false;

        ensureCurrentAssistantMessageExists();
        applyThinkingDeltaToRaw(content);
        callbackHandler.notifyThinkingDelta(content);
        callbackHandler.notifyMessageUpdate(state.getMessages());
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
        if (thinkingSegmentActive) {
            for (int i = content.size() - 1; i >= 0; i--) {
                com.google.gson.JsonElement el = content.get(i);
                if (el.isJsonObject()) {
                    com.google.gson.JsonObject block = el.getAsJsonObject();
                    if (block.has("type") && "thinking".equals(block.get("type").getAsString())) {
                        target = block;
                        break;
                    }
                }
            }
        }

        if (target == null) {
            target = new com.google.gson.JsonObject();
            target.addProperty("type", "thinking");
            target.addProperty("thinking", "");
            target.addProperty("text", "");
            content.add(target);
            thinkingSegmentActive = true;
            textSegmentActive = false;
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

    private void applyContentDeltaToRaw(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        com.google.gson.JsonObject raw = currentAssistantMessage.raw;
        com.google.gson.JsonObject message = raw.has("message") && raw.get("message").isJsonObject()
                ? raw.getAsJsonObject("message")
                : new com.google.gson.JsonObject();
        com.google.gson.JsonArray content = message.has("content") && message.get("content").isJsonArray()
                ? message.getAsJsonArray("content")
                : new com.google.gson.JsonArray();

        com.google.gson.JsonObject target = null;
        if (textSegmentActive) {
            for (int i = content.size() - 1; i >= 0; i--) {
                com.google.gson.JsonElement el = content.get(i);
                if (el.isJsonObject()) {
                    com.google.gson.JsonObject block = el.getAsJsonObject();
                    if (block.has("type") && "text".equals(block.get("type").getAsString())) {
                        target = block;
                        break;
                    }
                }
            }
        }

        if (target == null) {
            target = new com.google.gson.JsonObject();
            target.addProperty("type", "text");
            target.addProperty("text", "");
            content.add(target);
            textSegmentActive = true;
            thinkingSegmentActive = false;
        }

        String existing = target.has("text") && !target.get("text").isJsonNull()
                ? target.get("text").getAsString()
                : "";
        target.addProperty("text", existing + delta);

        message.add("content", content);
        raw.add("message", message);
        currentAssistantMessage.raw = raw;
    }

    private void syncSegmentActivity(com.google.gson.JsonObject raw) {
        com.google.gson.JsonObject message = raw != null && raw.has("message") && raw.get("message").isJsonObject()
                ? raw.getAsJsonObject("message") : raw;
        if (message == null || !message.has("content") || !message.get("content").isJsonArray()) {
            textSegmentActive = false;
            thinkingSegmentActive = false;
            return;
        }

        com.google.gson.JsonArray contentArray = message.getAsJsonArray("content");
        for (int i = contentArray.size() - 1; i >= 0; i--) {
            if (!contentArray.get(i).isJsonObject()) {
                continue;
            }
            com.google.gson.JsonObject block = contentArray.get(i).getAsJsonObject();
            if (!block.has("type") || block.get("type").isJsonNull()) {
                continue;
            }
            String type = block.get("type").getAsString();
            textSegmentActive = "text".equals(type);
            thinkingSegmentActive = "thinking".equals(type);
            return;
        }
        textSegmentActive = false;
        thinkingSegmentActive = false;
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
        textSegmentActive = false;
        thinkingSegmentActive = false;
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
