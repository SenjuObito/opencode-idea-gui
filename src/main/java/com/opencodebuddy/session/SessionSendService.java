package com.opencodebuddy.session;

import com.opencodebuddy.settings.CodemossSettingsService;
import com.opencodebuddy.notifications.OpencodeNotifier;
import com.opencodebuddy.provider.opencode.OpenCodeSDKBridge;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Owns message-send orchestration while OpencodeSession remains the public session facade.
 * OpenCode-only build: a single daemon-backed provider.
 */
public class SessionSendService {

    private static final Logger LOG = Logger.getInstance(SessionSendService.class);

    private final Project project;
    private final SessionState state;
    private final SessionCallbackFacade callbackFacade;
    private final SessionContextService contextService;
    private final OpenCodeSDKBridge bridge;

    public SessionSendService(
            Project project,
            SessionState state,
            SessionCallbackFacade callbackFacade,
            OpenCodeSDKBridge bridge,
            SessionContextService contextService
    ) {
        this.project = project;
        this.state = state;
        this.callbackFacade = callbackFacade;
        this.bridge = bridge;
        this.contextService = contextService;
    }

    public void prepareContextCollector(EditorContextCollector contextCollector) {
        contextCollector.setPsiContextEnabled(state.isPsiContextEnabled());
        contextCollector.setAutoOpenFileEnabled(readAutoOpenFileEnabled());
    }

    public void updateSessionStateForSend(OpencodeSession.Message userMessage, String normalizedInput) {
        // A pending revert is applied by the server at the start of this very
        // prompt (SessionRevert.cleanup drops everything from the revert point
        // onward). Trim the same range locally BEFORE publishing, so the snapshot
        // the webview receives never re-introduces the voided turn — otherwise
        // those messages reappear until the message.removed events land.
        if (state.trimMessagesFromRevertBoundary()) {
            LOG.info("[Revert] Trimmed session state from the revert boundary before send");
        }
        state.addMessage(userMessage);
        callbackFacade.notifyMessageUpdate(state.getMessages());

        if (state.getSummary() == null) {
            String baseSummary = (userMessage.content != null && !userMessage.content.isEmpty())
                    ? userMessage.content
                    : normalizedInput;
            String newSummary = baseSummary.length() > 45 ? baseSummary.substring(0, 45) + "..." : baseSummary;
            state.setSummary(newSummary);
            callbackFacade.notifySummaryReceived(newSummary);
        }

        state.updateLastModifiedTime();
        state.setError(null);
        state.setBusy(true);
        state.setLoading(true);
        OpencodeNotifier.setWaiting(project);
        callbackFacade.notifyStateChange(state.isBusy(), state.isLoading(), state.getError());
    }

    public CompletableFuture<Void> sendMessageToProvider(
            String channelId,
            String input,
            List<OpencodeSession.Attachment> attachments,
            JsonObject openedFilesJson,
            String externalAgentPrompt,
            List<String> fileTagPaths,
            String requestedPermissionMode,
            String requestedReasoningEffort,
            String requestedVariant,
            String requestedCommand
    ) {
        String agentPrompt = externalAgentPrompt;
        if (agentPrompt == null) {
            agentPrompt = getAgentPrompt();
            LOG.info("[Agent] Using agent from global setting (fallback)");
        } else {
            LOG.info("[Agent] Using agent from message (per-tab selection)");
        }

        String normalizedRequestedMode = normalizeRequestedPermissionMode(requestedPermissionMode);
        String effectivePermissionMode = resolveEffectivePermissionMode(
                normalizedRequestedMode,
                state.getPermissionMode()
        );

        LOG.info(
                "[ModeSync][Backend] provider=" + SessionProviderRouter.PROVIDER_ID
                        + ", requested=" + (normalizedRequestedMode != null ? normalizedRequestedMode : "(none)")
                        + ", session=" + (state.getPermissionMode() != null ? state.getPermissionMode() : "(none)")
                        + ", effective=" + effectivePermissionMode
        );

        String normalizedVariant = normalizeRequestedReasoningEffort(
                requestedReasoningEffort != null ? requestedReasoningEffort : requestedVariant);
        return sendToOpenCode(
                channelId,
                input,
                attachments,
                openedFilesJson,
                agentPrompt,
                fileTagPaths,
                effectivePermissionMode,
                normalizedVariant,
                requestedCommand
        );
    }

    public static String normalizeRequestedReasoningEffort(String effort) {
        if (effort == null) {
            return null;
        }
        String trimmed = effort.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (SessionState.isValidReasoningEffort(trimmed)) {
            return trimmed;
        }
        LOG.warn("[ReasoningEffort][Backend] Invalid requested reasoningEffort ignored: " + effort);
        return null;
    }

    public static String normalizeRequestedPermissionMode(String mode) {
        if (mode == null) {
            return null;
        }
        String trimmed = mode.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (SessionState.isValidPermissionMode(trimmed)) {
            return trimmed;
        }
        LOG.warn("[ModeSync][Backend] Invalid requested permissionMode ignored: " + mode);
        return null;
    }

    public static String resolveEffectivePermissionMode(String requestedMode, String sessionMode) {
        String resolvedMode = requestedMode;
        if (resolvedMode == null) {
            resolvedMode = normalizeRequestedPermissionMode(sessionMode);
        }
        if (resolvedMode == null) {
            resolvedMode = "default";
        }
        return resolvedMode;
    }

    private CompletableFuture<Void> sendToOpenCode(
            String channelId,
            String input,
            List<OpencodeSession.Attachment> attachments,
            JsonObject openedFilesJson,
            String agentPrompt,
            List<String> fileTagPaths,
            String effectivePermissionMode,
            String variant,
            String command
    ) {
        OpenCodeMessageHandler handler = new OpenCodeMessageHandler(
                state,
                callbackFacade.getCallbackHandler()
        );

        // opencode native slash commands: "/name args..." → /session/{id}/command.
        // Context injection and agent prompts are skipped for commands.
        String[] slash = parseSlashCommand(input);
        String finalInput;
        String commandName;
        String commandArguments;
        if (slash != null) {
            commandName = slash[0];
            commandArguments = slash[1];
            finalInput = input;
        } else {
            commandName = null;
            commandArguments = null;
            String contextAppend = contextService.buildSessionContextAppend(openedFilesJson, fileTagPaths);
            finalInput = (input != null ? input : "") + contextAppend;
            if (agentPrompt != null && !agentPrompt.isEmpty()) {
                finalInput = finalInput + "\n\n## Agent Role and Instructions\n\n" + agentPrompt;
                LOG.info("[Agent] Appending agentPrompt to user message (length: " + agentPrompt.length() + " chars)");
            }
        }

        String modelForSend = normalizeModelForSend(state.getModel());
        String agentForSend = normalizeAgentForSend(effectivePermissionMode);
        String effortForSend = variant != null ? variant : state.getReasoningEffort();
        int attachmentCount = attachments != null ? attachments.size() : 0;

        LOG.info("[Lifecycle] sendToOpenCode channelId=" + channelId
                + " sessionId=" + (state.getSessionId() != null ? state.getSessionId() : "(new)")
                + ", cwd=" + state.getCwd()
                + ", modelRaw=" + state.getModel()
                + ", model=" + (modelForSend != null ? modelForSend : "(config-default)")
                + ", agent=" + (agentForSend != null ? agentForSend : "(default)")
                + ", variant=" + (effortForSend != null ? effortForSend : "(default)")
                + ", permissionMode=" + effectivePermissionMode
                + ", command=" + (commandName != null ? commandName : "-")
                + ", attachments=" + attachmentCount);

        return bridge.sendMessage(
                finalInput,
                state.getSessionId(),
                state.getCwd(),
                modelForSend != null ? modelForSend : "",
                effortForSend,
                attachments,
                effectivePermissionMode,
                agentForSend,
                commandName,
                commandArguments,
                handler
        ).thenApply(result -> null);
    }

    public CompletableFuture<Void> sendShellToOpenCode(String channelId, String command) {
        OpenCodeMessageHandler handler = new OpenCodeMessageHandler(
                state,
                callbackFacade.getCallbackHandler()
        );

        String modelForSend = normalizeModelForSend(state.getModel());
        String effectivePermissionMode = resolveEffectivePermissionMode(
                normalizeRequestedPermissionMode(null),
                state.getPermissionMode()
        );
        String agentForSend = normalizeAgentForSend(effectivePermissionMode);

        LOG.info("[Lifecycle] sendShellToOpenCode channelId=" + channelId
                + " sessionId=" + (state.getSessionId() != null ? state.getSessionId() : "(new)")
                + ", cwd=" + state.getCwd()
                + ", model=" + (modelForSend != null ? modelForSend : "(config-default)")
                + ", agent=" + (agentForSend != null ? agentForSend : "build")
                + ", command=" + command);

        return bridge.shell(
                state.getSessionId(),
                state.getCwd(),
                command,
                modelForSend != null ? modelForSend : "",
                agentForSend != null ? agentForSend : "build",
                handler
        ).thenApply(result -> null);
    }

    /**
     * "/name args..." → [name, args], or null when the input is not a recognized command.
     */
    static String[] parseSlashCommand(String text) {
        if (text == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^/([a-zA-Z0-9_-]+)(?:\\s+([\\s\\S]*))?$")
                .matcher(text.trim());
        if (!m.matches() || m.group(1) == null || m.group(1).isEmpty()) {
            return null;
        }
        String commandName = m.group(1);
        if (!KnownCommands.isKnownSlashCommand(commandName)) {
            return null;
        }
        return new String[]{commandName, m.group(2) != null ? m.group(2).trim() : ""};
    }

    /**
     * Map UI model selection to a "provider/model" id. Returns null to omit
     * (opencode uses its own configured default).
     */
    static String normalizeModelForSend(String model) {
        if (model == null) {
            return null;
        }
        String trimmed = model.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String lower = trimmed.toLowerCase();
        if ("__config_default__".equals(lower)
                || "auto".equals(lower)
                || "default".equals(lower)
                || "(default)".equals(lower)
                || "config-default".equals(lower)
                || "config_default".equals(lower)
                || "opencode default".equals(lower)
                || "opencode-default".equals(lower)) {
            return null;
        }
        return trimmed;
    }

    /**
     * Map the UI permission-mode selection to the opencode agent id:
     * legacy modes ("default", "acceptEdits", etc.) → "build",
     * explicit agents ("plan", "build", "general", custom) → forwarded as-is.
     */
    static String normalizeAgentForSend(String permissionMode) {
        if (permissionMode == null) {
            return "build";
        }
        String trimmed = permissionMode.trim();
        if (trimmed.isEmpty()) {
            return "build";
        }
        return switch (trimmed) {
            case "default", "acceptEdits", "bypassPermissions", "dontAsk", "autoEdit" -> "build";
            default -> trimmed;
        };
    }

    private boolean readAutoOpenFileEnabled() {
        try {
            String projectPath = project.getBasePath();
            if (projectPath != null) {
                CodemossSettingsService settingsService = new CodemossSettingsService();
                boolean autoOpenFileEnabled = settingsService.getAutoOpenFileEnabled(projectPath);
                LOG.info("[EditorContext] Auto open file enabled: " + autoOpenFileEnabled);
                return autoOpenFileEnabled;
            }
        } catch (Exception e) {
            LOG.warn("[EditorContext] Failed to read autoOpenFileEnabled setting: " + e.getMessage());
        }
        return false;
    }

    private String getAgentPrompt() {
        try {
            CodemossSettingsService settingsService = new CodemossSettingsService();
            String selectedAgentId = settingsService.getSelectedAgentId();
            LOG.info("[Agent] Checking selected agent ID: " + (selectedAgentId != null ? selectedAgentId : "null"));

            if (selectedAgentId != null && !selectedAgentId.isEmpty()) {
                JsonObject agent = settingsService.getAgent(selectedAgentId);
                if (agent != null && agent.has("prompt") && !agent.get("prompt").isJsonNull()) {
                    String agentPrompt = agent.get("prompt").getAsString();
                    String agentName = agent.has("name") ? agent.get("name").getAsString() : "Unknown";
                    LOG.info("[Agent] Found agent: " + agentName
                            + ", prompt length: " + agentPrompt.length() + " chars");
                    return agentPrompt;
                }
                LOG.info("[Agent] Agent found but no prompt configured");
            } else {
                LOG.info("[Agent] No agent selected");
            }
        } catch (Exception e) {
            LOG.warn("[Agent] Failed to get agent prompt: " + e.getMessage());
        }
        return null;
    }
}
