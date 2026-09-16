package com.opencodebuddy.session;

import com.opencodebuddy.permission.PermissionManager;
import com.opencodebuddy.permission.PermissionRequest;
import com.opencodebuddy.provider.opencode.OpenCodeSDKBridge;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Session management for Claude conversations.
 * Maintains state and message history for a single chat session.
 */
public class ClaudeSession {

    private static final Logger LOG = Logger.getInstance(ClaudeSession.class);

    private final Gson gson = new Gson();
    private final Project project;
    /** Start time of the latest submitted turn, retained across Webview rebuilds. */
    private volatile long lastTurnStartedAtMillis;

    /**
     * Flag set when the user manually interrupts the current turn (clicks Stop).
     * Checked by {@link com.opencodebuddy.ui.toolwindow.ClaudeChatWindow#onStreamEnded()}
     * to suppress the task-completion notification sound for manual stops.
     * Reset to {@code false} at the start of each new {@link #send} call.
     */
    private volatile boolean manuallyInterrupted = false;

    // Session state manager
    private final com.opencodebuddy.session.SessionState state;

    // Message processors
    private final com.opencodebuddy.session.MessageParser messageParser;
    private final com.opencodebuddy.session.MessageMerger messageMerger;

    // Context collector
    private final com.opencodebuddy.session.EditorContextCollector contextCollector;
    private final SessionContextService contextService;
    private final SessionProviderRouter providerRouter;
    private final SessionSendService sendService;
    private final SessionMessageOrchestrator messageOrchestrator;

    // Callback facade
    private final SessionCallbackFacade callbackFacade;

    // SDK bridge
    private final OpenCodeSDKBridge openCodeSDKBridge;

    // Permission manager
    private final PermissionManager permissionManager = new PermissionManager();

    /**
     * Represents a single message in the conversation.
     */
    public static class Message {
        public enum Type {
            USER, ASSISTANT, SYSTEM, ERROR
        }

        public Type type;
        // The streaming handler thread reassigns these on every assistant update
        // (e.g. `raw = mergedRaw`, `content = builder.toString()`) while
        // StreamMessageCoalescer serializes the same Message off-EDT — enqueue only
        // shallow-copies the list, so elements are shared across threads. Without
        // volatile the serializer could read a stale reference and publish a snapshot
        // predating a just-reassigned tool_use block, which the frontend's structural
        // merge (it takes blocks from the new snapshot only) would then freeze as
        // missing.
        // This covers the reassignment race, the dominant mutation pattern. Note:
        // a few call sites still mutate the JsonObject in place (turnUsage / uuid /
        // usage stamps in ClaudeMessageHandler); those are a separate concern.
        public volatile String content;
        public long timestamp;
        public volatile JsonObject raw; // Raw message data from SDK

        public Message(Type type, String content) {
            this.type = type;
            this.content = content;
            this.timestamp = System.currentTimeMillis();
        }

        public Message(Type type, String content, JsonObject raw) {
            this(type, content);
            this.raw = raw;
        }
    }

    /**
     * Callback interface for session events.
     */
    public interface SessionCallback {
        void onMessageUpdate(List<Message> messages);

        void onStateChange(boolean busy, boolean loading, String error);

        default void onStatusMessage(String message) {
        }

        void onSessionIdReceived(String sessionId);

        void onPermissionRequested(PermissionRequest request);

        void onThinkingStatusChanged(boolean isThinking);

        void onSlashCommandsReceived(List<String> slashCommands);

        void onNodeLog(String log);

        void onSummaryReceived(String summary);

        // Streaming callback methods (with default implementations for backward compatibility)
        default void onStreamStart() {
        }

        default void onStreamEnd() {
        }

        default void onContentDelta(String delta) {
        }

        default void onThinkingDelta(String delta) {
        }

        /**
         * Called when a block reset signal is received during streaming.
         * This indicates a new assistant message has started within the stream
         * (e.g., after a tool_use loop iteration), and the frontend should
         * clear its streaming content refs to prevent cross-turn content merging.
         */
        default void onBlockReset() {
        }

        default void onUsageUpdate(int usedTokens, int maxTokens) {
        }

        default void onUserMessageUuidPatched(String content, String uuid) {
        }

        /**
         * Called when a session title update is received from the backend/SDK.
         *
         * @param sessionId the target session ID
         * @param title the updated session title
         */
        default void onSessionTitleReceived(String sessionId, String title) {
        }

        /**
         * Called when a Claude Code task_* SDK system event is received
         * (task_started / task_progress / task_notification).
         *
         * <p>Async subagents (Agent/Task tool invoked with run_in_background:true) run
         * in a background sidechain whose detailed
         * messages never enter the main SDK stream. The main stream only carries these
         * lightweight system events, which carry the agent's lifecycle signals: launch,
         * per-tool progress, and terminal completion (with result + usage). Forwarding
         * them to the frontend lets the subagent list reflect real running/completed
         * state instead of being stuck on the launch summary.</p>
         */
        default void onTaskEvent(String eventJson) {
        }

        default void onQuestionRequested(String jsonContent) {
        }

        default void onPromptClosed(String kind, String jsonContent) {
        }

        default void onRevertStateUpdate(boolean hasRevert, String messageId) {
        }

        default void onRevertStateUpdate(boolean hasRevert) {
            onRevertStateUpdate(hasRevert, null);
        }

        /**
         * The server authoritatively removed these messages from the session.
         *
         * <p>A revert is only a "void from here on" marker; the real deletion happens
         * server-side on the next prompt (SessionRevert.cleanup), which publishes
         * {@code message.removed} per message. The frontend must drop those ids from
         * its local list as soon as the signal arrives — the follow-up snapshot is
         * shorter by exactly these messages, and without the early removal the
         * shrink-protection path would restore the voided tail instead of letting it
         * disappear.</p>
         */
        default void onMessagesRemoved(List<String> messageIds) {
        }
    }

    public ClaudeSession(
            Project project,
            OpenCodeSDKBridge openCodeSDKBridge
    ) {
        this.project = project;
        this.openCodeSDKBridge = openCodeSDKBridge;

        // Initialize managers
        this.state = new com.opencodebuddy.session.SessionState();
        this.messageParser = new com.opencodebuddy.session.MessageParser();
        this.messageMerger = new com.opencodebuddy.session.MessageMerger();
        this.contextCollector = new com.opencodebuddy.session.EditorContextCollector(project);
        this.callbackFacade = new SessionCallbackFacade(project);
        this.contextService = new SessionContextService(project);
        this.providerRouter = new SessionProviderRouter(openCodeSDKBridge);
        this.sendService = new SessionSendService(
                project,
                state,
                callbackFacade,
                openCodeSDKBridge,
                contextService);
        this.messageOrchestrator = new SessionMessageOrchestrator(
                project,
                state,
                messageParser,
                callbackFacade,
                new SessionMessageOrchestrator.SessionHistoryAccess() {
                    @Override
                    public List<JsonObject> getProviderSessionMessages(String provider, String sessionId, String cwd) {
                        return providerRouter.getSessionMessages(provider, sessionId, cwd);
                    }

                    @Override
                    public JsonObject getLatestClaudeUserMessage(String sessionId, String cwd) {
                        // opencode has no Claude-format user-message file; uuid sync
                        // is a no-op and optimistic bubbles settle by content match.
                        return null;
                    }
                }
        );

        // Set up permission manager callback
        permissionManager.setOnPermissionRequestedCallback(request -> {
            callbackFacade.notifyPermissionRequested(request);
        });
    }

    public void setCallback(SessionCallback callback) {
        callbackFacade.setCallback(callback);
    }

    public com.opencodebuddy.session.EditorContextCollector getContextCollector() {
        return contextCollector;
    }

    // Getters - delegated to SessionState
    public String getSessionId() {
        return state.getSessionId();
    }

    public String getChannelId() {
        return state.getChannelId();
    }

    public boolean isBusy() {
        return state.isBusy();
    }

    public boolean isLoading() {
        return state.isLoading();
    }

    public String getError() {
        return state.getError();
    }

    /**
     * Returns whether the current (or most recent) turn was manually interrupted
     * by the user clicking Stop. Used to suppress the task-completion sound.
     *
     * @return {@code true} if the user manually interrupted the current turn
     */
    public boolean isManuallyInterrupted() {
        return manuallyInterrupted;
    }

    public List<Message> getMessages() {
        return state.getMessages();
    }

    /**
     * 提供底层会话状态访问，用于历史恢复等需要直接重建会话内存态的场景。
     */
    public SessionState getState() {
        return state;
    }

    public String getSummary() {
        return state.getSummary();
    }

    public long getLastModifiedTime() {
        return state.getLastModifiedTime();
    }

    /**
     * Set session ID and working directory (used for session restoration).
     */
    public void setSessionInfo(String sessionId, String cwd) {
        state.setSessionId(sessionId);
        if (sessionId != null && !sessionId.trim().isEmpty()) {
            callbackFacade.notifySessionIdReceived(sessionId);
        }
        if (cwd != null) {
            setCwd(cwd);
        } else {
            state.setCwd(null);
        }
    }

    /**
     * Get the current working directory.
     */
    public String getCwd() {
        return state.getCwd();
    }

    /**
     * Set the working directory.
     */
    public void setCwd(String cwd) {
        state.setCwd(cwd);
        LOG.info("Working directory updated to: " + cwd);
    }

    /**
     * Launch Claude agent.
     * Reuses existing channelId if available, otherwise creates a new one.
     */
    public CompletableFuture<String> launchClaude() {
        if (state.getChannelId() != null) {
            return CompletableFuture.completedFuture(state.getChannelId());
        }

        state.setError(null);
        state.setChannelId(UUID.randomUUID().toString());

        return CompletableFuture.supplyAsync(() -> {
                    try {
                        // Validate and clean invalid sessionId (e.g., path instead of UUID)
                        String currentSessionId = state.getSessionId();
                        if (currentSessionId != null && (currentSessionId.contains("/") || currentSessionId.contains("\\"))) {
                            LOG.warn("sessionId looks like a path, resetting: " + currentSessionId);
                            state.setSessionId(null);
                            currentSessionId = null;
                        }

                        // Select SDK based on provider
                        String currentProvider = state.getProvider();
                        String currentChannelId = state.getChannelId();
                        String currentCwd = state.getCwd();
                        JsonObject result = providerRouter.launchChannel(
                                currentProvider,
                                currentChannelId,
                                currentSessionId,
                                currentCwd
                        );

                        // Check if sessionId exists and is not null
                        if (result.has("sessionId") && !result.get("sessionId").isJsonNull()) {
                            String newSessionId = result.get("sessionId").getAsString();
                            // Validate sessionId format (should be UUID format)
                            if (!newSessionId.contains("/") && !newSessionId.contains("\\")) {
                                state.setSessionId(newSessionId);
                                callbackFacade.notifySessionIdReceived(newSessionId);
                            } else {
                                LOG.warn("Ignoring invalid sessionId: " + newSessionId);
                            }
                        }

                        return currentChannelId;
                    } catch (Exception e) {
                        state.setError(e.getMessage());
                        state.setChannelId(null);
                        callbackFacade.notifyStateChange(state.isBusy(), state.isLoading(), state.getError());
                        throw new RuntimeException("Failed to launch: " + e.getMessage(), e);
                    }
                }).orTimeout(com.opencodebuddy.config.TimeoutConfig.QUICK_OPERATION_TIMEOUT,
                        com.opencodebuddy.config.TimeoutConfig.QUICK_OPERATION_UNIT)
                .exceptionally(ex -> {
                    if (ex instanceof java.util.concurrent.TimeoutException) {
                        String timeoutMsg = "Channel launch timed out (" +
                                com.opencodebuddy.config.TimeoutConfig.QUICK_OPERATION_TIMEOUT + "s), please retry";
                        LOG.warn(timeoutMsg);
                        state.setError(timeoutMsg);
                        state.setChannelId(null);
                        callbackFacade.notifyStateChange(state.isBusy(), state.isLoading(), state.getError());
                        throw new RuntimeException(timeoutMsg);
                    }
                    throw new RuntimeException(ex.getCause());
                });
    }

    /**
     * Send a message using global agent settings.
     *
     * @deprecated Use {@link #send(String, String)} with explicit agent prompt instead.
     */
    @Deprecated
    public CompletableFuture<Void> send(String input) {
        return send(input, (List<Attachment>) null, null);
    }

    /**
     * Send a message with a specific agent prompt.
     * Used for per-tab independent agent selection.
     */
    public CompletableFuture<Void> send(String input, String agentPrompt) {
        return send(input, null, agentPrompt, null, null);
    }

    /**
     * Send a message with a specific agent prompt and file tags.
     * Used for Codex context injection.
     */
    public CompletableFuture<Void> send(String input, String agentPrompt, List<String> fileTagPaths) {
        return send(input, null, agentPrompt, fileTagPaths, null);
    }

    /**
     * Send a message with a specific agent prompt, file tags and requested permission mode.
     * requestedPermissionMode priority: payload > sessionMode > default.
     */
    public CompletableFuture<Void> send(String input, String agentPrompt, List<String> fileTagPaths, String requestedPermissionMode) {
        return send(input, null, agentPrompt, fileTagPaths, requestedPermissionMode, null, null);
    }

    /**
     * Send a message with a specific agent prompt, file tags, requested permission mode,
     * and requested reasoning effort.
     */
    public CompletableFuture<Void> send(
            String input,
            String agentPrompt,
            List<String> fileTagPaths,
            String requestedPermissionMode,
            String requestedReasoningEffort
    ) {
        return send(input, null, agentPrompt, fileTagPaths, requestedPermissionMode, requestedReasoningEffort, null);
    }

    /**
     * Send a message with a specific agent prompt, file tags, requested permission mode,
     * requested reasoning effort, and Codex fast mode.
     * The Codex fast mode maps to the official service tier used by Codex CLI /fast.
     */
    public CompletableFuture<Void> send(
            String input,
            String agentPrompt,
            List<String> fileTagPaths,
            String requestedPermissionMode,
            String requestedReasoningEffort,
            String requestedCodexFastMode
    ) {
        return send(input, null, agentPrompt, fileTagPaths, requestedPermissionMode,
                requestedReasoningEffort, requestedCodexFastMode, null);
    }

    /**
     * Send a message with an optional DSH agent preset.
     */
    public CompletableFuture<Void> send(
            String input,
            String agentPrompt,
            List<String> fileTagPaths,
            String requestedPermissionMode,
            String requestedReasoningEffort,
            String requestedCodexFastMode,
            String requestedDshPreset
    ) {
        return send(input, null, agentPrompt, fileTagPaths, requestedPermissionMode,
                requestedReasoningEffort, requestedCodexFastMode, requestedDshPreset);
    }

    /**
     * Send a message with attachments using global agent settings.
     *
     * @deprecated Use {@link #send(String, List, String)} with explicit agent prompt instead.
     */
    @Deprecated
    public CompletableFuture<Void> send(String input, List<Attachment> attachments) {
        return send(input, attachments, null, null, null);
    }

    /**
     * Send a message with attachments and a specific agent prompt.
     * Used for per-tab independent agent selection.
     *
     * @param input       User input text
     * @param attachments List of attachments (nullable)
     * @param agentPrompt Agent prompt (falls back to global setting if null)
     */
    public CompletableFuture<Void> send(String input, List<Attachment> attachments, String agentPrompt) {
        return send(input, attachments, agentPrompt, null, null);
    }

    /**
     * Send a message with attachments, agent prompt, and file tags.
     * Used for Codex context injection.
     *
     * @param input        User input text
     * @param attachments  List of attachments (nullable)
     * @param agentPrompt  Agent prompt (falls back to global setting if null)
     * @param fileTagPaths File tag paths for Codex context injection
     */
    public CompletableFuture<Void> send(String input, List<Attachment> attachments, String agentPrompt, List<String> fileTagPaths) {
        return send(input, attachments, agentPrompt, fileTagPaths, null);
    }

    /**
     * Send a message with attachments, agent prompt, file tags, and a requested permission mode.
     * The effective mode is resolved with priority:
     * Priority: requestedPermissionMode > sessionMode > default.
     */
    public CompletableFuture<Void> send(
            String input,
            List<Attachment> attachments,
            String agentPrompt,
            List<String> fileTagPaths,
            String requestedPermissionMode
    ) {
        return send(input, attachments, agentPrompt, fileTagPaths, requestedPermissionMode, null, null);
    }

    /**
     * Send a message with attachments, agent prompt, file tags, requested permission mode,
     * requested reasoning effort, and Codex fast mode.
     * The effective mode is resolved with priority:
     * Priority: requestedPermissionMode > sessionMode > default.
     * The Codex fast mode maps to the official service tier used by Codex CLI /fast.
     */
    public CompletableFuture<Void> send(
            String input,
            List<Attachment> attachments,
            String agentPrompt,
            List<String> fileTagPaths,
            String requestedPermissionMode,
            String requestedReasoningEffort,
            String requestedCodexFastMode
    ) {
        return send(input, attachments, agentPrompt, fileTagPaths, requestedPermissionMode,
                requestedReasoningEffort, requestedCodexFastMode, null);
    }

    /**
     * Send a message with attachments, agent prompt, file tags, requested permission
     * mode, and requested reasoning effort (model variant).
     *
     * @param requestedCodexFastMode legacy multi-engine parameter, ignored
     * @param requestedDshPreset     legacy multi-engine parameter, ignored
     */
    public CompletableFuture<Void> send(
            String input,
            List<Attachment> attachments,
            String agentPrompt,
            List<String> fileTagPaths,
            String requestedPermissionMode,
            String requestedReasoningEffort,
            String requestedCodexFastMode,
            String requestedDshPreset
    ) {
        lastTurnStartedAtMillis = System.currentTimeMillis();
        // Reset the manual-interrupt flag at the start of a new turn so that
        // a fresh send is not mistaken for a user-initiated stop.
        manuallyInterrupted = false;
        String normalizedInput = (input != null) ? input.trim() : "";
        Message userMessage = contextService.buildUserMessage(normalizedInput, attachments);
        sendService.updateSessionStateForSend(userMessage, normalizedInput);

        final String finalAgentPrompt = agentPrompt;
        final List<String> finalFileTagPaths = fileTagPaths;
        final String finalRequestedPermissionMode = requestedPermissionMode;
        final String finalRequestedReasoningEffort = requestedReasoningEffort;

        return launchClaude().thenCompose(chId -> {
            sendService.prepareContextCollector(contextCollector);

            return contextCollector.collectContext().thenCompose(openedFilesJson ->
                    sendService.sendMessageToProvider(
                            chId,
                            userMessage.content,
                            attachments,
                            openedFilesJson,
                            finalAgentPrompt,
                            finalFileTagPaths,
                            finalRequestedPermissionMode,
                            finalRequestedReasoningEffort,
                            null,
                            null
                    )
            ).thenCompose(v -> syncUserMessageUuidsAfterSend());
        }).exceptionally(ex -> {
            state.setError(ex.getMessage());
            state.setBusy(false);
            state.setLoading(false);
            callbackFacade.notifyStateChange(state.isBusy(), state.isLoading(), state.getError());
            return null;
        });
    }

    /**
     * Send a shell command in the session context (opencode `!` semantics).
     */
    public CompletableFuture<Void> sendShell(String command) {
        lastTurnStartedAtMillis = System.currentTimeMillis();
        manuallyInterrupted = false;
        String normalizedCommand = (command != null) ? command.trim() : "";
        if (normalizedCommand.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        Message userMessage = new Message(Message.Type.USER, "!" + normalizedCommand);
        sendService.updateSessionStateForSend(userMessage, "!" + normalizedCommand);

        return launchClaude().thenCompose(chId ->
                sendService.sendShellToOpenCode(chId, normalizedCommand)
        ).thenCompose(v -> syncUserMessageUuidsAfterSend()).exceptionally(ex -> {
            state.setError(ex.getMessage());
            state.setBusy(false);
            state.setLoading(false);
            callbackFacade.notifyStateChange(state.isBusy(), state.isLoading(), state.getError());
            return null;
        });
    }

    private CompletableFuture<Void> syncUserMessageUuidsAfterSend() {
        return messageOrchestrator.syncUserMessageUuidsAfterSend();
    }

    /**
     * Interrupt the current execution.
     */
    public CompletableFuture<Void> interrupt() {
        // Mark this turn as manually interrupted so the stream-end handler
        // suppresses the task-completion notification sound.
        manuallyInterrupted = true;

        String provider = state.getProvider();
        String channelId = state.getChannelId();
        if (channelId == null) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            try {
                providerRouter.interruptChannel(provider, channelId);
                if (!isCurrentChannel(provider, channelId)) {
                    return;
                }
                state.setError(null);  // Clear previous error state
                state.setBusy(false);
                state.setLoading(false);  // Also reset loading state

                // Note: We intentionally don't call notifyStreamEnd() here because:
                // 1. The frontend's interruptSession() already cleans up streaming state directly
                // 2. Calling notifyStreamEnd() would trigger flushStreamMessageUpdates(),
                //    which might restore previous messages via lastMessagesSnapshot, interfering with clearMessages
                // 3. State reset is notified via callbackFacade.notifyStateChange()

                callbackFacade.notifyStateChange(state.isBusy(), state.isLoading(), state.getError());
            } catch (Exception e) {
                if (isCurrentChannel(provider, channelId)) {
                    state.setError(e.getMessage());
                    state.setLoading(false);  // Also reset loading on error
                    callbackFacade.notifyStateChange(state.isBusy(), state.isLoading(), state.getError());
                }
                throw new CompletionException(e);
            }
        });
    }

    private boolean isCurrentChannel(String provider, String channelId) {
        return Objects.equals(provider, state.getProvider())
                && Objects.equals(channelId, state.getChannelId());
    }

    /**
     * Restart the Claude agent.
     */
    public CompletableFuture<Void> restart() {
        return interrupt().thenCompose(v -> {
            state.setChannelId(null);
            state.setBusy(false);
            callbackFacade.notifyStateChange(state.isBusy(), state.isLoading(), state.getError());
            return launchClaude().thenApply(chId -> null);
        });
    }

    /**
     * Load message history from the server.
     */
    public CompletableFuture<Void> loadFromServer() {
        return messageOrchestrator.loadFromServer();
    }

    /**
     * Represents a file attachment (e.g., image).
     */
    public static class Attachment {
        public String fileName;
        public String mediaType;
        public String data; // Base64 encoded data

        public Attachment(String fileName, String mediaType, String data) {
            this.fileName = fileName;
            this.mediaType = mediaType;
            this.data = data;
        }
    }

    /**
     * Get the permission manager.
     */
    public PermissionManager getPermissionManager() {
        return permissionManager;
    }

    /**
     * Set the permission mode.
     * Maps frontend permission mode strings to PermissionManager enum values.
     */
    public void setPermissionMode(String mode) {
        state.setPermissionMode(mode);

        // Sync PermissionManager mode with frontend mode:
        // - "default" -> DEFAULT (ask every time)
        // - "acceptEdits"/"autoEdit" -> ACCEPT_EDITS (agent mode, auto-accept file edits)
        // - "bypassPermissions" -> ALLOW_ALL (auto mode, bypass all permission checks)
        // - "plan" -> DENY_ALL (plan mode, not yet supported)
        PermissionManager.PermissionMode pmMode;
        if ("bypassPermissions".equals(mode)) {
            pmMode = PermissionManager.PermissionMode.ALLOW_ALL;
            LOG.info("Permission mode set to ALLOW_ALL for mode: " + mode);
        } else if ("acceptEdits".equals(mode) || "autoEdit".equals(mode)) {
            pmMode = PermissionManager.PermissionMode.ACCEPT_EDITS;
            LOG.info("Permission mode set to ACCEPT_EDITS for mode: " + mode);
        } else if ("plan".equals(mode)) {
            pmMode = PermissionManager.PermissionMode.DENY_ALL;
            LOG.info("Permission mode set to DENY_ALL for mode: " + mode);
        } else {
            // "default" or other unknown modes
            pmMode = PermissionManager.PermissionMode.DEFAULT;
            LOG.info("Permission mode set to DEFAULT for mode: " + mode);
        }

        permissionManager.setPermissionMode(pmMode);
    }

    /**
     * Get the permission mode.
     */
    public String getPermissionMode() {
        return state.getPermissionMode();
    }

    /**
     * Set the model.
     */
    public void setModel(String model) {
        state.setModel(model);
        LOG.info("Model updated to: " + model);
    }

    /**
     * Get the model.
     */
    public String getModel() {
        return state.getModel();
    }

    /**
     * Returns the start time of the latest submitted turn, or {@code 0} when
     * no turn has been submitted yet.
     */
    public long getLastTurnStartedAtMillis() {
        return lastTurnStartedAtMillis;
    }

    /**
     * Set the AI provider.
     */
    public void setProvider(String provider) {
        state.setProvider(provider);
        LOG.info("Provider updated to: " + provider);
    }

    /**
     * Get the AI provider.
     */
    public String getProvider() {
        return state.getProvider();
    }

    /**
     * Get the current runtime session epoch.
     */
    public String getRuntimeSessionEpoch() {
        return state.getRuntimeSessionEpoch();
    }

    /**
     * Rotate the runtime session epoch.
     */
    public String rotateRuntimeSessionEpoch() {
        String epoch = state.rotateRuntimeSessionEpoch();
        LOG.info("[Lifecycle] Rotated runtime session epoch to: " + epoch);
        return epoch;
    }

    /**
     * Set the reasoning effort level.
     */
    public void setReasoningEffort(String effort) {
        state.setReasoningEffort(effort);
        LOG.info("Reasoning effort updated to: " + effort);
    }

    /**
     * Get the reasoning effort level.
     */
    public String getReasoningEffort() {
        return state.getReasoningEffort();
    }

    /**
     * Set the Codex service tier. Null means use Codex defaults; "fast" matches Codex CLI /fast.
     */
    public void setCodexServiceTier(String serviceTier) {
        state.setCodexServiceTier(serviceTier);
        LOG.info("Codex service tier updated to: " + (serviceTier != null ? serviceTier : "standard"));
    }

    /**
     * Get the Codex service tier.
     */
    public String getCodexServiceTier() {
        return state.getCodexServiceTier();
    }

    /**
     * Get the list of available slash commands.
     */
    public List<String> getSlashCommands() {
        return state.getSlashCommands();
    }


    /**
     * Create a permission request (called by the SDK).
     */
    public PermissionRequest createPermissionRequest(String toolName, Map<String, Object> inputs, JsonObject suggestions, Project project) {
        return permissionManager.createRequest(state.getChannelId(), toolName, inputs, suggestions, project);
    }

    /**
     * Handle a permission decision.
     */
    public void handlePermissionDecision(String channelId, boolean allow, boolean remember, String rejectMessage) {
        if (OpencodePermissionRegistry.isOpencodePermissionId(channelId)) {
            replyOpencodePermission(channelId, allow, remember, rejectMessage);
            return;
        }
        permissionManager.handlePermissionDecision(channelId, allow, remember, rejectMessage);
    }

    /**
     * Handle an "always allow" permission decision.
     */
    public void handlePermissionDecisionAlways(String channelId, boolean allow) {
        if (OpencodePermissionRegistry.isOpencodePermissionId(channelId)) {
            replyOpencodePermission(channelId, allow, true, null);
            return;
        }
        permissionManager.handlePermissionDecisionAlways(channelId, allow);
    }

    /**
     * Forward an opencode permission-dialog decision to the server.
     *
     * <p>opencode permission requests ({@code per_...} ids) bypass the local
     * PermissionManager: the server holds the pending request and the turn
     * blocks until it is answered. Without this reply the model stream spins
     * forever ("asking permission, clicked allow, still running"). The reply
     * goes through the daemon's {@code opencode.replyPermission} command with
     * the session/directory captured when the request arrived, since the
     * server's reply endpoint is workspace-scoped.</p>
     */
    private void replyOpencodePermission(String permissionId, boolean allow, boolean remember, String rejectMessage) {
        OpencodePermissionRegistry.PendingPermission pending =
                OpencodePermissionRegistry.consume(permissionId);
        String sessionId = pending != null && pending.sessionId != null
                ? pending.sessionId : state.getSessionId();
        String directory = pending != null && pending.directory != null
                ? pending.directory : state.getCwd();
        // Daemon vocabulary: allow/allowAlways/deny → SDK once/always/reject.
        String reply = allow ? (remember ? "allowAlways" : "allow") : "deny";
        String message = allow ? null : (rejectMessage != null ? rejectMessage : null);
        openCodeSDKBridge.replyPermission(sessionId, permissionId, reply, message, directory)
                .whenComplete((ok, error) -> {
                    if (error != null || !Boolean.TRUE.equals(ok)) {
                        LOG.warn("[OpenCode] permission reply failed: id=" + permissionId
                                + " error=" + (error != null ? error.getMessage() : "daemon rejected"));
                    }
                });
    }

    public SessionState.RevertState getRevertState() {
        return state.getRevertState();
    }

    public void setRevertState(SessionState.RevertState revertState) {
        state.setRevertState(revertState);
    }
}
