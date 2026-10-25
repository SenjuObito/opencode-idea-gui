package com.opencodebuddy.handler;

import com.opencodebuddy.handler.core.BaseMessageHandler;
import com.opencodebuddy.handler.core.HandlerContext;
import com.opencodebuddy.handler.core.RequestIdLedger;

import com.opencodebuddy.session.OpencodeSession;
import com.opencodebuddy.bridge.NodeDetector;
import com.opencodebuddy.model.NodeDetectionResult;
import com.opencodebuddy.notifications.OpencodeNotifier;
import com.opencodebuddy.session.SessionState;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.vfs.VirtualFile;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Session management message handler.
 * Handles sending messages, interrupting, restarting, and creating new sessions.
 */
public class SessionHandler extends BaseMessageHandler {

    private static final Logger LOG = Logger.getInstance(SessionHandler.class);

    private static final String[] SUPPORTED_TYPES = {
            "session.send",
            "session.interrupt",
            "session.restart"
    };

    /** Replayed commands must not send the same prompt twice. */
    private final RequestIdLedger requestIds = new RequestIdLedger();

    public SessionHandler(HandlerContext context) {
        super(context);
    }

    @Override
    public String[] getSupportedTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public boolean handle(String type, String content) {
        switch (type) {
            case "session.send":
                LOG.debug("[SessionHandler] 处理: session.send");
                handleHostSessionSend(content);
                return true;
            case "session.interrupt":
                LOG.debug("[SessionHandler] 处理: session.interrupt");
                handleInterruptSession(content);
                return true;
            case "session.restart":
                LOG.debug("[SessionHandler] 处理: session.restart");
                handleRestartSession(content);
                return true;
            default:
                return false;
        }
    }

    /**
     * Resolves the cached Node.js version, attempting recovery when the cache is stale.
     * If the version is absent but a cached path exists, re-verifies the path to restore
     * the detection result (e.g. after a new window resets the cache via setNodeExecutable).
     *
     * @return the Node.js version string, or null if detection fails entirely
     */
    private String resolveNodeVersion() {
        String nodeVersion = NodeDetector.getInstance().getCachedNodeVersion();
        if (nodeVersion != null) {
            return nodeVersion;
        }
        // Version absent — try to recover using the cached path (path may still be valid).
        String cachedPath = NodeDetector.getInstance().getCachedNodePath();
        if (cachedPath == null || cachedPath.isEmpty()) {
            return null;
        }
        LOG.info("[SessionHandler] Node version cache miss, re-verifying path: " + cachedPath);
        NodeDetectionResult recovery = NodeDetector.getInstance().verifyAndCacheNodePath(cachedPath);
        if (recovery != null && recovery.isFound()) {
            return recovery.getNodeVersion();
        }
        return null;
    }

    /**
     * Run on the EDT when an IDE is present.
     *
     * <p>The handler is also exercised by unit tests, where no application
     * instance exists; a missing EDT must not turn a rejected command into a
     * NullPointerException.</p>
     */
    private static void runOnEdt(Runnable action) {
        if (ApplicationManager.getApplication() == null) {
            action.run();
            return;
        }
        ApplicationManager.getApplication().invokeLater(action);
    }

    private void recordSendFailure(OpencodeSession session, String targetSessionId, String prompt, String errorMessage) {
        if (session != null && prompt != null && !prompt.isBlank()) {
            session.getState().addMessage(new OpencodeSession.Message(OpencodeSession.Message.Type.USER, prompt));
            session.getState().addMessage(new OpencodeSession.Message(OpencodeSession.Message.Type.ERROR, errorMessage));
            session.getState().setBusy(false);
            session.getState().setLoading(false);
            session.getCallbackFacade().notifyMessageUpdate(session.getState().getMessages());
            session.getCallbackFacade().notifyStateChange(false, false, errorMessage);
        }
        runOnEdt(() -> {
            callJavaScript("addErrorMessage", targetSessionId != null ? targetSessionId : "", escapeJs(errorMessage));
        });
    }

    /**
     * Apply the model carried by a send payload to the session that will run it.
     *
     * <p>A tab that has not bound a session yet cannot target {@code set_model}
     * at anything, so the selection has to travel with the message that creates
     * the session. Writing the state directly deliberately skips the
     * "user changed model" side effects (usage reset, notification): those belong
     * to an explicit model change, not to every send.</p>
     */
    static void applyRequestedModel(OpencodeSession session, String model) {
        if (session == null || model == null) {
            return;
        }
        String trimmed = model.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        session.setModel(trimmed);
        LOG.info("[SessionHandler] Applied message-scoped model: " + trimmed
                + " (session=" + session.getSessionId() + ")");
    }

    /**
     * Send message to Claude
     * [FIX] Now parses JSON format to extract text, agent info and file tags
     */
    /**
     * v2 command entry point: the envelope owns identity, the payload owns
     * content.
     *
     * <p>The outer envelope was already verified at the gateway
     * ({@link com.opencodebuddy.ui.toolwindow.OpencodeBuddyChatWindow}), with
     * {@code tabId}, {@code sessionId} and {@code requestId} merged into the payload JSON.
     * We only enforce session consistency here before dispatching.</p>
     */
    private void handleHostSessionSend(String content) {
        if (content == null || content.isBlank()) {
            rejectCommand(null, null, "BAD_ENVELOPE", "empty payload");
            return;
        }

        JsonObject payloadJson;
        try {
            payloadJson = com.google.gson.JsonParser.parseString(content).getAsJsonObject();
        } catch (RuntimeException e) {
            rejectCommand(null, null, "BAD_ENVELOPE", "the payload is not a JSON object");
            return;
        }

        String requestId = payloadJson.has("requestId") && !payloadJson.get("requestId").isJsonNull()
                ? payloadJson.get("requestId").getAsString() : null;
        String tabId = payloadJson.has("tabId") && !payloadJson.get("tabId").isJsonNull()
                ? payloadJson.get("tabId").getAsString() : null;
        String sessionId = payloadJson.has("sessionId") && !payloadJson.get("sessionId").isJsonNull()
                ? payloadJson.get("sessionId").getAsString() : null;

        if (tabId == null || tabId.isBlank()) {
            rejectCommand(null, requestId, "MISSING_TAB_ID", "the command must name the tab it belongs to");
            return;
        }
        if (requestId == null || requestId.isBlank()) {
            rejectCommand(tabId, null, "MISSING_REQUEST_ID", "the command request id is required");
            return;
        }

        if (!requestIds.remember(requestId)) {
            LOG.debug("[SessionHandler] replayed session.send ignored: " + requestId);
            return;
        }

        String mismatch = sessionMismatch(tabId, sessionId);
        if (mismatch != null) {
            requestIds.forget(requestId);
            rejectCommand(tabId, requestId, "SESSION_MISMATCH", mismatch);
            return;
        }

        // One command, two shapes: the attachments are part of the payload.
        if (hasAttachments(content)) {
            handleSendMessageWithAttachments(content);
            return;
        }
        handleSendMessage(content);
    }

    /** Does this send carry attachments? */
    private static boolean hasAttachments(String payload) {
        try {
            JsonObject json = com.google.gson.JsonParser.parseString(payload).getAsJsonObject();
            if (!json.has("attachments") || json.get("attachments").isJsonNull()) {
                return false;
            }
            JsonElement attachments = json.get("attachments");
            return attachments.isJsonArray() && !attachments.getAsJsonArray().isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * @return a human readable reason when the named session is not the one the
     *         tab is bound to, or {@code null} when the command is consistent
     */
    private String sessionMismatch(String tabId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        OpencodeSession session = context.getSession(tabId);
        if (session == null) {
            // The tab has not initialized a session yet; let handleSendMessage
            // create it via context.createSession(tabId).
            return null;
        }
        String bound = session.getSessionId();
        if (bound == null || bound.isBlank()) {
            // The binding is still in flight: the tab owns the send, the
            // canonical id is simply not known yet.
            return null;
        }
        return bound.equals(sessionId) ? null : "tab " + tabId + " is bound to " + bound;
    }

    private void rejectCommand(String tabId, String requestId, String code, String message) {
        LOG.warn("[SessionHandler] command rejected (" + code + "): " + message
                + (tabId != null ? " tab=" + tabId : ""));
        context.publishEvent(com.opencodebuddy.tab.TabEvent.of("command.rejected")
                .tab(tabId)
                .field("tabId", tabId != null ? tabId : "")
                .field("requestId", requestId != null ? requestId : "")
                .field("code", code)
                .field("message", message)
                .build());
    }

    private void handleSendMessage(String content) {
        // [FIX] Parse JSON format to extract text, agent info and file tags
        String prompt;
        String agentPrompt = null;
        java.util.List<String> fileTagPaths = null;
        String requestedPermissionMode = null;
        String requestedReasoningEffort = null;
        String requestedModel = null;
        String requestedCodexFastMode = null;
        String requestedDshPreset = null;
        Boolean requestedAutoOpenFile = null;
        String targetSessionId = null;
        String tabId = null;
        try {
            Gson gson = new Gson();
            JsonObject payload = gson.fromJson(content, JsonObject.class);
            prompt = payload != null && payload.has("text") && !payload.get("text").isJsonNull()
                             ? payload.get("text").getAsString()
                             : content; // Fallback to raw content if not JSON

            if (payload != null && payload.has("sessionId") && !payload.get("sessionId").isJsonNull()) {
                targetSessionId = payload.get("sessionId").getAsString();
            }
            if (payload != null && payload.has("tabId") && !payload.get("tabId").isJsonNull()) {
                tabId = payload.get("tabId").getAsString();
            }

            // Extract agent prompt from the message
            if (payload != null && payload.has("agent") && !payload.get("agent").isJsonNull()) {
                JsonObject agent = payload.getAsJsonObject("agent");
                if (agent.has("prompt") && !agent.get("prompt").isJsonNull()) {
                    agentPrompt = agent.get("prompt").getAsString();
                    String agentName = agent.has("name") ? agent.get("name").getAsString() : "Unknown";
                    LOG.info("[SessionHandler] Using agent from message: " + agentName);
                }
            }

            // [FIX] Extract file tags from the message (for Codex context injection)
            if (payload != null && payload.has("fileTags") && payload.get("fileTags").isJsonArray()) {
                JsonArray fileTagsArray = payload.getAsJsonArray("fileTags");
                fileTagPaths = new java.util.ArrayList<>();
                for (int i = 0; i < fileTagsArray.size(); i++) {
                    JsonObject fileTag = fileTagsArray.get(i).getAsJsonObject();
                    if (fileTag.has("absolutePath") && !fileTag.get("absolutePath").isJsonNull()) {
                        fileTagPaths.add(fileTag.get("absolutePath").getAsString());
                    }
                }
                if (!fileTagPaths.isEmpty()) {
                    LOG.info("[SessionHandler] Extracted " + fileTagPaths.size() + " file tags for context injection");
                }
            }

            // Extract requested permission mode from payload (optional, backward compatible)
            if (payload != null && payload.has("permissionMode") && !payload.get("permissionMode").isJsonNull()) {
                String mode = payload.get("permissionMode").getAsString();
                if (SessionState.isValidPermissionMode(mode)) {
                    requestedPermissionMode = mode;
                } else {
                    LOG.warn("[SessionHandler] Ignoring invalid permissionMode from payload: " + mode);
                }
            }

            requestedReasoningEffort = extractReasoningEffort(payload);

            if (payload != null && payload.has("model") && !payload.get("model").isJsonNull()) {
                requestedModel = payload.get("model").getAsString();
            }

            if (payload != null && payload.has("codexFastMode") && !payload.get("codexFastMode").isJsonNull()) {
                requestedCodexFastMode = payload.get("codexFastMode").getAsString();
            }
            requestedDshPreset = extractDshPreset(payload);
            if (payload != null && payload.has("autoOpenFileEnabled") && !payload.get("autoOpenFileEnabled").isJsonNull()) {
                requestedAutoOpenFile = payload.get("autoOpenFileEnabled").getAsBoolean();
            }
        } catch (Exception e) {
            // If parsing fails, treat content as plain text (backward compatibility)
            LOG.debug("[SessionHandler] Message is plain text, not JSON: " + e.getMessage());
            prompt = content;
        }

        OpencodeSession sessionObj = null;
        if (targetSessionId != null && !targetSessionId.isBlank()) {
            sessionObj = context.getSession(targetSessionId);
        }
        if (sessionObj == null && tabId != null && !tabId.isBlank()) {
            sessionObj = context.getSession(tabId);
            if (sessionObj == null) {
                sessionObj = context.createSession(tabId);
            }
        }
        if (sessionObj == null) {
            // No tab identity: refuse instead of sending to whichever session
            // happens to be active. The rejection names the missing identity so
            // the failure is visible instead of silent.
            rejectCommand(tabId, null, "MISSING_TAB_ID",
                    "the command must name the tab it belongs to");
            recordSendFailure(null, null, prompt, "发送失败：命令缺少 tab 身份");
            return;
        }
        final OpencodeSession targetSession = sessionObj;
        final String resolvedSessionId = targetSessionId != null ? targetSessionId
                : (tabId != null ? tabId : (targetSession != null ? targetSession.getSessionId() : ""));
        applyRequestedModel(targetSession, requestedModel);

        String nodeVersion = this.resolveNodeVersion();
        if (nodeVersion == null) {
            recordSendFailure(targetSession, resolvedSessionId, prompt, "未检测到有效的 Node.js 版本，请在设置中配置或重新打开工具窗口。");
            return;
        }
        if (!NodeDetector.isVersionSupported(nodeVersion)) {
            int minVersion = NodeDetector.MIN_NODE_MAJOR_VERSION;
            recordSendFailure(targetSession, resolvedSessionId, prompt, "Node.js 版本过低 (" + nodeVersion + ")，插件需要 v" + minVersion + " 或更高版本才能正常运行。请在设置中配置正确的 Node.js 路径。");
            return;
        }

        final String finalPrompt = prompt;
        final String finalAgentPrompt = agentPrompt;
        final java.util.List<String> finalFileTagPaths = fileTagPaths;
        final String finalRequestedPermissionMode = requestedPermissionMode;
        final String finalRequestedReasoningEffort = requestedReasoningEffort;
        final String finalRequestedCodexFastMode = requestedCodexFastMode;
        final String finalRequestedDshPreset = requestedDshPreset;
        final Boolean finalRequestedAutoOpenFile = requestedAutoOpenFile;

        if (finalPrompt != null && finalPrompt.startsWith("!") && finalPrompt.trim().length() > 1) {
            final String command = finalPrompt.substring(1).trim();
            CompletableFuture.runAsync(() -> {
                String currentWorkingDir = determineWorkingDirectory();
                String previousCwd = targetSession != null ? targetSession.getCwd() : null;

                if (!currentWorkingDir.equals(previousCwd) && targetSession != null) {
                    targetSession.setCwd(currentWorkingDir);
                    LOG.info("[SessionHandler] Updated working directory: " + currentWorkingDir);
                }

                var project = context.getProject();
                if (project != null) {
                    OpencodeNotifier.setWaiting(project);
                }

                if (targetSession != null) {
                    targetSession.sendShell(finalPrompt, command)
                            .exceptionally(ex -> {
                                LOG.error("Failed to execute shell command", ex);
                                if (project != null) {
                                    OpencodeNotifier.showError(project, "Shell failed: " + ex.getMessage());
                                }
                                ApplicationManager.getApplication().invokeLater(() -> {
                                    callJavaScript("addErrorMessage", resolvedSessionId, escapeJs("Shell 执行失败: " + ex.getMessage()));
                                });
                                return null;
                            });
                }
            });
            return;
        }

        CompletableFuture.runAsync(() -> {
            if (targetSession == null) {
                return;
            }
            String currentWorkingDir = determineWorkingDirectory();
            String previousCwd = targetSession.getCwd();

            if (!currentWorkingDir.equals(previousCwd)) {
                targetSession.setCwd(currentWorkingDir);
                LOG.info("[SessionHandler] Updated working directory: " + currentWorkingDir);
            }

            // Capture project for use in async callbacks
            var project = context.getProject();
            if (project != null) {
                OpencodeNotifier.setWaiting(project);
            }

            // [FIX] Pass agent prompt and file tags directly to session
            targetSession.send(finalPrompt, finalAgentPrompt, finalFileTagPaths,
                            finalRequestedPermissionMode, finalRequestedReasoningEffort,
                            finalRequestedCodexFastMode, finalRequestedDshPreset,
                            finalRequestedAutoOpenFile)
                .thenRun(() -> {
                    // Claude now triggers success on actual stream_end callback.
                    // Codex has no stream_end event, keep success trigger at completion.
                    if (project != null
                            && "codex".equals(targetSession.getProvider())
                            && !targetSession.isManuallyInterrupted()) {
                        OpencodeNotifier.showSuccess(
                            project,
                            OpencodeNotifier.buildTitleFromSession(targetSession),
                            OpencodeNotifier.buildPreviewFromSession(targetSession, "Task completed"));
                    }
                })
                .exceptionally(ex -> {
                    LOG.error("Failed to send message", ex);
                    if (project != null) {
                        OpencodeNotifier.showError(project, "Task failed: " + ex.getMessage());
                    }
                    targetSession.getState().addMessage(new OpencodeSession.Message(OpencodeSession.Message.Type.ERROR, "发送失败: " + ex.getMessage()));
                    targetSession.getState().setBusy(false);
                    targetSession.getState().setLoading(false);
                    targetSession.getCallbackFacade().notifyMessageUpdate(targetSession.getState().getMessages());
                    targetSession.getCallbackFacade().notifyStateChange(false, false, ex.getMessage());
                    ApplicationManager.getApplication().invokeLater(() -> {
                        callJavaScript("addErrorMessage", resolvedSessionId, escapeJs("发送失败: " + ex.getMessage()));
                    });
                    return null;
                });
        });
    }

    /**
     * Send message with attachments.
     * [FIX] Now extracts agent info and file tags from payload.
     */
    private void handleSendMessageWithAttachments(String content) {
        try {
            Gson gson = new Gson();
            JsonObject payload = gson.fromJson(content, JsonObject.class);
            String text = payload != null && payload.has("text") && !payload.get("text").isJsonNull()
                                  ? payload.get("text").getAsString()
                                  : "";

            // The identity is read first: the attachments belong to this tab.
            String targetSessionId = null;
            if (payload != null && payload.has("sessionId") && !payload.get("sessionId").isJsonNull()) {
                targetSessionId = payload.get("sessionId").getAsString();
            }
            String tabId = null;
            if (payload != null && payload.has("tabId") && !payload.get("tabId").isJsonNull()) {
                tabId = payload.get("tabId").getAsString();
            }

            java.util.List<OpencodeSession.Attachment> atts = new java.util.ArrayList<>();
            java.util.List<String> missingAttachments = new java.util.ArrayList<>();
            if (payload != null && payload.has("attachments") && payload.get("attachments").isJsonArray()) {
                JsonArray arr = payload.getAsJsonArray("attachments");
                for (int i = 0; i < arr.size(); i++) {
                    JsonObject a = arr.get(i).getAsJsonObject();
                    String fileName = a.has("fileName") && !a.get("fileName").isJsonNull()
                                              ? a.get("fileName").getAsString()
                                              : ("attachment-" + System.currentTimeMillis());
                    String mediaType = a.has("mediaType") && !a.get("mediaType").isJsonNull()
                                               ? a.get("mediaType").getAsString()
                                               : "";
                    String data = a.has("data") && !a.get("data").isJsonNull()
                                          ? a.get("data").getAsString()
                                          : "";
                    // Chunked uploads name the staged file by attachmentId;
                    // older inline payloads named it by id.
                    String attachmentId;
                    if (a.has("attachmentId") && !a.get("attachmentId").isJsonNull()) {
                        attachmentId = a.get("attachmentId").getAsString();
                    } else if (a.has("id") && !a.get("id").isJsonNull()) {
                        attachmentId = a.get("id").getAsString();
                    } else {
                        attachmentId = fileName + "-" + i;
                    }
                    // The attachment belongs to the tab that picked it. A
                    // chunked upload already wrote the bytes into that tab's
                    // directory, so the send only names it; an inline payload
                    // (small attachment, older caller) is staged here.
                    String staged;
                    if (data.isEmpty()) {
                        staged = context.readStagedAttachment(tabId, attachmentId);
                        if (staged == null) {
                            // Sending the message without the attachment would
                            // silently lose what the user attached.
                            missingAttachments.add(fileName);
                            continue;
                        }
                    } else {
                        staged = context.stageAttachment(
                                tabId, attachmentId, fileName, mediaType, data);
                    }
                    atts.add(new OpencodeSession.Attachment(fileName, mediaType, staged));
                }
            }

            if (!missingAttachments.isEmpty()) {
                String requestId = payload != null && payload.has("requestId")
                        && !payload.get("requestId").isJsonNull()
                        ? payload.get("requestId").getAsString() : null;
                rejectCommand(tabId, requestId, "ATTACHMENT_MISSING",
                        "the upload did not finish: " + String.join(", ", missingAttachments));
                return;
            }

            // [FIX] Extract agent prompt from the payload for per-tab agent selection
            String agentPrompt = null;
            String requestedPermissionMode = null;
            String requestedReasoningEffort = null;
            String requestedModel = null;
            String requestedCodexFastMode = null;
            String requestedDshPreset = null;
            if (payload != null && payload.has("agent") && !payload.get("agent").isJsonNull()) {
                JsonObject agent = payload.getAsJsonObject("agent");
                if (agent.has("prompt") && !agent.get("prompt").isJsonNull()) {
                    agentPrompt = agent.get("prompt").getAsString();
                    String agentName = agent.has("name") ? agent.get("name").getAsString() : "Unknown";
                    LOG.info("[SessionHandler] Using agent from attachment message: " + agentName);
                }
            }

            // [FIX] Extract file tags from the payload (for Codex context injection)
            java.util.List<String> fileTagPaths = null;
            if (payload != null && payload.has("fileTags") && payload.get("fileTags").isJsonArray()) {
                JsonArray fileTagsArray = payload.getAsJsonArray("fileTags");
                fileTagPaths = new java.util.ArrayList<>();
                for (int i = 0; i < fileTagsArray.size(); i++) {
                    JsonObject fileTag = fileTagsArray.get(i).getAsJsonObject();
                    if (fileTag.has("absolutePath") && !fileTag.get("absolutePath").isJsonNull()) {
                        fileTagPaths.add(fileTag.get("absolutePath").getAsString());
                    }
                }
                if (!fileTagPaths.isEmpty()) {
                    LOG.info("[SessionHandler] Extracted " + fileTagPaths.size() + " file tags for attachment message");
                }
            }

            if (payload != null && payload.has("permissionMode") && !payload.get("permissionMode").isJsonNull()) {
                String mode = payload.get("permissionMode").getAsString();
                if (SessionState.isValidPermissionMode(mode)) {
                    requestedPermissionMode = mode;
                } else {
                    LOG.warn("[SessionHandler] Ignoring invalid permissionMode from attachment payload: " + mode);
                }
            }

            requestedReasoningEffort = extractReasoningEffort(payload);

            if (payload != null && payload.has("model") && !payload.get("model").isJsonNull()) {
                requestedModel = payload.get("model").getAsString();
            }

            if (payload != null && payload.has("codexFastMode") && !payload.get("codexFastMode").isJsonNull()) {
                requestedCodexFastMode = payload.get("codexFastMode").getAsString();
            }
            requestedDshPreset = extractDshPreset(payload);
            Boolean requestedAutoOpenFile = null;
            if (payload != null && payload.has("autoOpenFileEnabled") && !payload.get("autoOpenFileEnabled").isJsonNull()) {
                requestedAutoOpenFile = payload.get("autoOpenFileEnabled").getAsBoolean();
            }


            sendMessageWithAttachments(targetSessionId, tabId, text, atts, agentPrompt, fileTagPaths, requestedPermissionMode,
                    requestedReasoningEffort, requestedModel, requestedCodexFastMode, requestedDshPreset, requestedAutoOpenFile);
        } catch (Exception e) {
            LOG.error("[SessionHandler] 解析附件负载失败: " + e.getMessage(), e);
            handleSendMessage(content);
        }
    }

    /**
     * Send message with attachments to Claude
     * [FIX] Now accepts agent prompt and file tags parameters
     */
    private void sendMessageWithAttachments(
        String targetSessionId,
        String tabId,
        String prompt,
        List<OpencodeSession.Attachment> attachments,
        String agentPrompt,
        java.util.List<String> fileTagPaths,
        String requestedPermissionMode,
        String requestedReasoningEffort,
        String requestedModel,
        String requestedCodexFastMode,
        String requestedDshPreset,
        Boolean requestedAutoOpenFile
    ) {
        OpencodeSession sessionObj = null;
        if (targetSessionId != null && !targetSessionId.isBlank()) {
            sessionObj = context.getSession(targetSessionId);
        }
        if (sessionObj == null && tabId != null && !tabId.isBlank()) {
            sessionObj = context.getSession(tabId);
            if (sessionObj == null) {
                sessionObj = context.createSession(tabId);
            }
        }
        if (sessionObj == null) {
            // No tab identity: refuse instead of sending to whichever session
            // happens to be active.
            rejectCommand(tabId, null, "MISSING_TAB_ID",
                    "the command must name the tab it belongs to");
            recordSendFailure(null, null, prompt, "发送失败：命令缺少 tab 身份");
            return;
        }
        final OpencodeSession targetSession = sessionObj;
        final String resolvedSessionId = targetSessionId != null ? targetSessionId
                : (tabId != null ? tabId : (targetSession != null ? targetSession.getSessionId() : ""));
        applyRequestedModel(targetSession, requestedModel);

        // Version check (consistent with handleSendMessage)
        String nodeVersion = this.resolveNodeVersion();
        if (nodeVersion == null) {
            recordSendFailure(targetSession, resolvedSessionId, prompt, "未检测到有效的 Node.js 版本，请在设置中配置或重新打开工具窗口。");
            return;
        }
        if (!NodeDetector.isVersionSupported(nodeVersion)) {
            int minVersion = NodeDetector.MIN_NODE_MAJOR_VERSION;
            recordSendFailure(targetSession, resolvedSessionId, prompt, "Node.js 版本过低 (" + nodeVersion + ")，插件需要 v" + minVersion + " 或更高版本才能正常运行。请在设置中配置正确的 Node.js 路径。");
            return;
        }

        final String finalAgentPrompt = agentPrompt;
        final java.util.List<String> finalFileTagPaths = fileTagPaths;
        final String finalRequestedPermissionMode = requestedPermissionMode;
        final String finalRequestedReasoningEffort = requestedReasoningEffort;
        final String finalRequestedCodexFastMode = requestedCodexFastMode;
        final String finalRequestedDshPreset = requestedDshPreset;
        final Boolean finalRequestedAutoOpenFile = requestedAutoOpenFile;

        final String attachmentTabId = tabId;
        CompletableFuture.runAsync(() -> {
            if (targetSession == null) {
                return;
            }
            try {
            String currentWorkingDir = determineWorkingDirectory();
            String previousCwd = targetSession.getCwd();
            if (!currentWorkingDir.equals(previousCwd)) {
                targetSession.setCwd(currentWorkingDir);
                LOG.info("[SessionHandler] Updated working directory: " + currentWorkingDir);
            }

            // Capture project for use in async callbacks
            var project = context.getProject();
            if (project != null) {
                OpencodeNotifier.setWaiting(project);
            }

            // [FIX] Pass agent prompt and file tags directly to session
            targetSession.send(prompt, attachments, finalAgentPrompt, finalFileTagPaths,
                            finalRequestedPermissionMode, finalRequestedReasoningEffort,
                            finalRequestedCodexFastMode, finalRequestedDshPreset,
                            finalRequestedAutoOpenFile)
                .thenRun(() -> {
                    // Claude now triggers success on actual stream_end callback.
                    // Codex has no stream_end event, keep success trigger at completion.
                    if (project != null
                            && "codex".equals(targetSession.getProvider())
                            && !targetSession.isManuallyInterrupted()) {
                        OpencodeNotifier.showSuccess(
                            project,
                            OpencodeNotifier.buildTitleFromSession(targetSession),
                            OpencodeNotifier.buildPreviewFromSession(targetSession, "Task completed"));
                    }
                })
                .exceptionally(ex -> {
                    LOG.error("Failed to send message with attachments", ex);
                    if (project != null) {
                        OpencodeNotifier.showError(project, "Task failed: " + ex.getMessage());
                    }
                    targetSession.getState().addMessage(new OpencodeSession.Message(OpencodeSession.Message.Type.ERROR, "发送失败: " + ex.getMessage()));
                    targetSession.getState().setBusy(false);
                    targetSession.getState().setLoading(false);
                    targetSession.getCallbackFacade().notifyMessageUpdate(targetSession.getState().getMessages());
                    targetSession.getCallbackFacade().notifyStateChange(false, false, ex.getMessage());
                    ApplicationManager.getApplication().invokeLater(() -> {
                        callJavaScript("addErrorMessage", resolvedSessionId, escapeJs("发送失败: " + ex.getMessage()));
                    });
                    return null;
                });
            } finally {
                // The staged bytes lived only for this send; the tab keeps no
                // copy of what was already delivered.
                context.releaseTabAttachments(attachmentTabId);
            }
        });
    }

    /**
     * Interrupt the specified or current session.
     */
    private void handleInterruptSession(String content) {
        String targetSessionId = null;
        String tabId = null;
        try {
            if (content != null && content.trim().startsWith("{")) {
                JsonObject payload = new Gson().fromJson(content, JsonObject.class);
                if (payload != null && payload.has("sessionId") && !payload.get("sessionId").isJsonNull()) {
                    targetSessionId = payload.get("sessionId").getAsString();
                }
                if (payload != null && payload.has("tabId") && !payload.get("tabId").isJsonNull()) {
                    tabId = payload.get("tabId").getAsString();
                }
            }
        } catch (Exception ignored) {}

        OpencodeSession targetSession = null;
        if (targetSessionId != null && !targetSessionId.isBlank()) {
            targetSession = context.getSession(targetSessionId);
        } else if (tabId != null && !tabId.isBlank()) {
            targetSession = context.getSession(tabId);
        }

        if (targetSession == null && (targetSessionId == null || targetSessionId.isBlank())) {
            // No identity at all: the command does not say what it stops, so it
            // is refused. Stopping "whatever is running" is how one tab's stop
            // button used to kill another tab's turn.
            rejectCommand(null, null, "MISSING_TAB_ID",
                    "an interrupt must name the session or tab it stops");
            return;
        }

        final String resolvedSessionId = targetSessionId != null ? targetSessionId : tabId;

        if (targetSession == null) {
            // We cannot interrupt what we cannot find — but we must still release
            // the frontend, otherwise the tab spins forever.
            LOG.warn("[SessionHandler] interrupt: no session found for sessionId=" + targetSessionId
                    + " tabId=" + tabId + "; releasing frontend state only");
            if (resolvedSessionId != null && !resolvedSessionId.isBlank()) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    context.publishEvent(com.opencodebuddy.tab.TabEvent.of("turn.ended")
                            .session(resolvedSessionId)
                            .tab(context.getTabIdForSession(resolvedSessionId))
                            .build());
                    context.publishEvent(com.opencodebuddy.tab.TabEvent.of("session.loading")
                            .session(resolvedSessionId)
                            .tab(context.getTabIdForSession(resolvedSessionId))
                            .field("loading", false)
                            .build());
                });
            }
            return;
        }

        targetSession.interrupt().thenRun(() -> {
            ApplicationManager.getApplication().invokeLater(() -> {
                // Authoritative terminal for this session: no empty-string identity,
                // so the frontend can never mis-attribute it to the active tab.
                context.publishEvent(com.opencodebuddy.tab.TabEvent.of("turn.ended")
                        .session(resolvedSessionId)
                        .tab(context.getTabIdForSession(resolvedSessionId))
                        .build());
                context.publishEvent(com.opencodebuddy.tab.TabEvent.of("session.loading")
                        .session(resolvedSessionId)
                        .tab(context.getTabIdForSession(resolvedSessionId))
                        .field("loading", false)
                        .build());
            });
        });
    }

    /**
     * Restart the session.
     */
    private void handleRestartSession(String content) {
        String tabId = null;
        try {
            if (content != null && content.trim().startsWith("{")) {
                JsonObject payload = new Gson().fromJson(content, JsonObject.class);
                if (payload != null && payload.has("tabId") && !payload.get("tabId").isJsonNull()) {
                    tabId = payload.get("tabId").getAsString();
                }
            }
        } catch (Exception ignored) {
        }
        if (tabId == null || tabId.isBlank()) {
            rejectCommand(null, null, "MISSING_TAB_ID", "a restart must name the tab it restarts");
            return;
        }
        OpencodeSession target = context.getSession(tabId);
        if (target == null) {
            rejectCommand(tabId, null, "TAB_NOT_FOUND", "unknown tab " + tabId);
            return;
        }
        target.restart().thenRun(() -> {
            ApplicationManager.getApplication().invokeLater(() -> {});
        });
    }

    /**
     * Determine the appropriate working directory.
     */
    private String determineWorkingDirectory() {
        String projectPath = context.getProject().getBasePath();

        // Prefer the user-configured working directory first, normalized so that
        // relative segments (e.g. "..") are collapsed. This must match the directory
        // history is stored under (see WorkingDirectoryManager#resolveEffectiveWorkingDirectory).
        if (projectPath != null && new File(projectPath).exists()) {
            try {
                com.opencodebuddy.settings.OpenCodeBuddySettingsService settingsService =
                        new com.opencodebuddy.settings.OpenCodeBuddySettingsService();
                String resolvedPath = settingsService.getEffectiveWorkingDirectory(projectPath);
                if (resolvedPath != null && !resolvedPath.isEmpty()) {
                    LOG.info("[SessionHandler] Using working directory: " + resolvedPath);
                    return resolvedPath;
                }
            } catch (Exception e) {
                LOG.warn("[SessionHandler] Failed to resolve working directory: " + e.getMessage());
            }
        }

        // When projectPath is invalid (null or missing), try the active file's
        // parent directory first — typical case: single-file temporary project
        // (projectPath in /tmp) while the actual file is under the user's home.
        if (projectPath == null || !new File(projectPath).exists()) {
            String activeFileDir = resolveWorkingDirectoryFromActiveFile(projectPath);
            if (activeFileDir != null && !activeFileDir.isEmpty()) {
                return activeFileDir;
            }
            String userHome = NodeDetector.resolveHomeForFileOps();
            LOG.warn("[SessionHandler] Using user home directory as fallback: " + userHome);
            return userHome;
        }

        // Use project root as the default working directory.
        return projectPath;
    }

    private String extractReasoningEffort(JsonObject payload) {
        if (payload == null || !payload.has("reasoningEffort") || payload.get("reasoningEffort").isJsonNull()) {
            return null;
        }
        return payload.get("reasoningEffort").getAsString();
    }

    private String extractDshPreset(JsonObject payload) {
        if (payload == null || !payload.has("dshPreset") || payload.get("dshPreset").isJsonNull()) {
            return null;
        }
        String preset = payload.get("dshPreset").getAsString();
        return SessionState.isValidDshPreset(preset) ? preset.trim() : null;
    }

    /**
     * Tries to infer a working directory from the currently active file.
     * Returns the parent directory only when the file is outside project root;
     * otherwise returns null.
     */
    private String resolveWorkingDirectoryFromActiveFile(String projectPath) {
        try {
            VirtualFile[] selectedFiles = ApplicationManager.getApplication().runReadAction(
                    (com.intellij.openapi.util.Computable<VirtualFile[]>) () ->
                            FileEditorManager.getInstance(context.getProject()).getSelectedFiles()
            );
            if (selectedFiles == null || selectedFiles.length == 0) {
                return null;
            }

            for (VirtualFile selectedFile : selectedFiles) {
                if (selectedFile == null || !selectedFile.isInLocalFileSystem()) {
                    continue;
                }

                String selectedPath = selectedFile.getPath();
                if (selectedPath == null || selectedPath.isEmpty()) {
                    continue;
                }

                File localFile = new File(selectedPath);
                if (!localFile.exists()) {
                    continue;
                }

                String filePath = localFile.getAbsolutePath();
                String candidateDir = localFile.isDirectory()
                        ? filePath
                        : localFile.getParent();
                if (candidateDir == null || candidateDir.isEmpty()) {
                    continue;
                }

                if (projectPath != null && !projectPath.isEmpty() && isPathWithin(filePath, projectPath)) {
                    continue;
                }

                LOG.info("[SessionHandler] Active file is outside project root, using its parent as working directory: "
                        + candidateDir + " (activeFile=" + filePath + ", projectPath=" + projectPath + ")");
                return candidateDir;
            }
        } catch (Exception e) {
            LOG.debug("[SessionHandler] Failed to resolve working directory from active file: " + e.getMessage());
        }

        return null;
    }

    /**
     * Checks whether childPath is inside basePath (including equality).
     */
    private boolean isPathWithin(String childPath, String basePath) {
        if (childPath == null || basePath == null) {
            return false;
        }

        try {
            Path child = Paths.get(childPath).toAbsolutePath().normalize();
            Path base = Paths.get(basePath).toAbsolutePath().normalize();
            return child.startsWith(base);
        } catch (Exception ignored) {
            return childPath.startsWith(basePath);
        }
    }
}
