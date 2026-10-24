package com.opencodebuddy.bridge;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The semantic event catalog.
 *
 * <p>The bridge used to name an event after the JavaScript function it called
 * ({@code updateMessages}, {@code onContentDelta}, …). That made the wire
 * protocol a mirror of the webview's internals: a renamed handler was a
 * protocol change, and the catalog of "what can happen" was spread over
 * thousands of lines of callbacks.</p>
 *
 * <p>Every host event now has a stable, semantic name — {@code content.delta},
 * {@code message.snapshot}, {@code turn.ended} — and the legacy function name
 * travels next to it as {@code fn}. The webview resolves the semantic name
 * first and falls back to the legacy name, so the protocol can be completed
 * step by step without breaking the running plugin.</p>
 */
public final class EventCatalog {

    private EventCatalog() {
    }

    /** semantic type -> legacy function name */
    private static final Map<String, String> FUNCTION_BY_TYPE;
    /** legacy function name -> semantic type */
    private static final Map<String, String> TYPE_BY_FUNCTION;

    static {
        Map<String, String> byType = new LinkedHashMap<>();

        // ── lifecycle ──────────────────────────────────────────────────────
        byType.put("tab.resync", "onTabResync");
        byType.put("session.bound", "setSessionId");
        byType.put("session.loaded", "historyLoadComplete");
        byType.put("session.cleared", "clearMessages");
        byType.put("command.rejected", "commandRejected");
        byType.put("attachment.failed", "onAttachmentFailed");

        // ── turn & message ─────────────────────────────────────────────────
        byType.put("turn.started", "onStreamStart");
        byType.put("turn.ended", "onStreamEnd");
        byType.put("turn.settled", "onTurnEnd");
        byType.put("turn.retry", "updateRetryStatus");
        byType.put("turn.heartbeat", "onStreamingHeartbeat");
        byType.put("content.delta", "onContentDelta");
        byType.put("thinking.delta", "onThinkingDelta");
        byType.put("thinking.status", "showThinkingStatus");
        byType.put("message.snapshot", "updateMessages");
        byType.put("message.tail", "updateMessageTail");
        byType.put("message.prepended", "updateMessagesPrepend");
        byType.put("message.added", "addUserMessage");
        byType.put("message.history", "addHistoryMessage");
        byType.put("message.removed", "onMessagesRemoved");
        byType.put("message.uuid.patched", "patchMessageUuid");
        byType.put("message.block.reset", "onBlockReset");

        // ── status & usage ─────────────────────────────────────────────────
        byType.put("status.updated", "updateStatus");
        byType.put("session.loading", "showLoading");
        byType.put("session.summary", "showSummary");
        byType.put("usage.updated", "onUsageUpdate");

        // ── tab & settings ─────────────────────────────────────────────────
        byType.put("tab.title.changed", "updateSessionTitle");
        byType.put("settings.mode", "onModeChanged");

        // ── prompts ────────────────────────────────────────────────────────
        byType.put("permission.requested", "showPermissionDialog");
        byType.put("question.requested", "showAskUserQuestionDialog");
        byType.put("question.answered", "onQuestionAnswered");
        byType.put("question.invalidated", "invalidateQuestionCard");
        byType.put("plan.requested", "showPlanApprovalDialog");
        byType.put("prompt.closed", "forceClosePermissionDialog");
        byType.put("prompt.question.closed", "forceCloseAskUserQuestionDialog");
        byType.put("prompt.plan.closed", "forceClosePlanApprovalDialog");

        // ── status panel ───────────────────────────────────────────────────
        byType.put("todos.updated", "onTodoUpdated");
        byType.put("task.event", "onTaskEvent");
        byType.put("subagent.history", "onSubagentHistoryLoaded");
        byType.put("revert.changed", "onRevertStateUpdate");

        // ── webview 回调事件（A3 全量迁移） ─────────────────────────────
        byType.put("agents.import.preview", "agentImportPreviewResult");
        byType.put("agents.import.result", "agentImportResult");
        byType.put("agents.operation", "agentOperationResult");
        byType.put("tab.state", "applyBackendTabState");
        byType.put("font.idea", "applyIdeaFontConfig");
        byType.put("settings.pinned.models", "applyPinnedModels");
        byType.put("settings.ui.preferences", "applyUiPreferences");
        byType.put("mcp.toggled", "mcpServerToggled");
        byType.put("node.process.killed", "nodeProcessKillResult");
        byType.put("clipboard.read", "onClipboardRead");
        byType.put("commands.deleted", "onCommandsDeleted");
        byType.put("commands.listed", "onCommandsList");
        byType.put("commands.detail", "onCommandsRead");
        byType.put("commands.saved", "onCommandsSaved");
        byType.put("session.compact.failed", "onCompactError");
        byType.put("session.compact.done", "onCompactSuccess");
        byType.put("context.usage.error", "onContextUsageError");
        byType.put("clipboard.copied", "onCopyToClipboardResult");
        byType.put("font.editor.received", "onEditorFontConfigReceived");
        byType.put("font.ui.received", "onUiFontConfigReceived");
        byType.put("font.code.received", "onCodeFontConfigReceived");
        byType.put("font.ui.apply", "applyUiFontConfig");
        byType.put("font.code.apply", "applyCodeFontConfig");
        byType.put("language.apply", "applyIdeaLanguageConfig");
        byType.put("settings.auto.open.file", "updateAutoOpenFileEnabled");
        byType.put("settings.permission.timeout", "updatePermissionDialogTimeout");
        byType.put("settings.send.shortcut", "updateSendShortcut");
        byType.put("settings.streaming", "updateStreamingEnabled");
        byType.put("settings.commit.generation", "updateCommitGenerationEnabled");
        byType.put("settings.ai.title.generation", "updateAiTitleGenerationEnabled");
        byType.put("settings.statusbar.widget", "updateStatusBarWidgetEnabled");
        byType.put("settings.commit.prompt", "updateCommitPrompt");
        byType.put("settings.project.commit.prompt", "updateProjectCommitPrompt");
        byType.put("settings.prompt.enhancer", "updatePromptEnhancerConfig");
        byType.put("settings.commit.ai", "updateCommitAiConfig");
        byType.put("settings.task.completion", "updateTaskCompletionNotificationEnabled");
        byType.put("settings.question.notification", "updateAskUserQuestionNotificationEnabled");
        byType.put("settings.system.notification", "updateSystemNotificationOnlyWhenUnfocused");
        byType.put("settings.question.sound", "updateAskUserQuestionSoundNotificationEnabled");
        byType.put("files.listed", "onFileListResult");
        byType.put("files.path.resolved", "onFilePathResolved");
        byType.put("session.fork.bound", "onForkBound");
        byType.put("session.fork.failed", "onForkError");
        byType.put("session.fork.done", "onForkSuccess");
        byType.put("theme.changed", "onIdeThemeChanged");
        byType.put("theme.received", "onIdeThemeReceived");
        byType.put("settings.mode.received", "onModeReceived");
        byType.put("settings.model.confirmed", "onModelConfirmed");
        byType.put("permission.denied", "onPermissionDenied");
        byType.put("session.revert.failed", "onRevertError");
        byType.put("session.hydrated", "onSessionHydrated");
        byType.put("session.share.failed", "onShareError");
        byType.put("session.share.done", "onShareSuccess");
        byType.put("font.system.list", "onSystemFontListReceived");
        byType.put("tab.activated", "onTabActivated");
        byType.put("tokentracker.response", "onTokenTrackerResponse");
        byType.put("files.undo.all", "onUndoAllFileResult");
        byType.put("files.undo", "onUndoFileResult");
        byType.put("session.unshare.failed", "onUnshareError");
        byType.put("session.unshare.done", "onUnshareSuccess");
        byType.put("skills.delete", "skillDeleteResult");
        byType.put("skills.import", "skillImportResult");
        byType.put("skills.toggle", "skillToggleResult");
        byType.put("agents.updated", "updateAgents");
        byType.put("cli.status", "updateCliStatus");
        byType.put("copilot.import.preview", "updateCopilotImportPreview");
        byType.put("daemon.status", "updateDaemonStatus");
        byType.put("linkify.capabilities", "updateLinkifyCapabilities");
        byType.put("mcp.marketplace.entries", "updateMcpMarketplaceEntries");
        byType.put("mcp.marketplace.sources", "updateMcpMarketplaceSources");
        byType.put("mcp.status", "updateMcpServerStatus");
        byType.put("mcp.tools", "updateMcpServerTools");
        byType.put("mcp.servers", "updateMcpServers");
        byType.put("node.path", "updateNodePath");
        byType.put("node.processes", "updateNodeProcesses");
        byType.put("cli.path", "updateOpencodeCliPath");
        byType.put("skills.updated", "updateSkills");
        byType.put("slash.commands", "updateSlashCommands");
        byType.put("settings.sound", "updateSoundNotificationConfig");
        byType.put("project.working.directory", "updateWorkingDirectory");
        byType.put("selection.info", "addSelectionInfo");
        byType.put("selection.snippet", "addCodeSnippet");
        byType.put("selection.clear", "clearSelectionInfo");
        byType.put("message.error", "addErrorMessage");
        byType.put("toast.shown", "addToast");
        byType.put("toast.error", "showToast");
        byType.put("toast.operation.error", "showError");
        byType.put("toast.operation.success", "showSuccess");
        byType.put("toast.operation.success.i18n", "showSuccessI18n");
        byType.put("toast.switch.success", "showSwitchSuccess");
        byType.put("context.usage.dialog", "showContextUsageDialog");
        byType.put("cli.models", "setCliModels");
        byType.put("history.data", "setHistoryData");
        byType.put("chat.focus", "focusChatInput");
        byType.put("files.path.from.ide", "handleFilePathFromJava");
        byType.put("files.diff.result", "handleDiffResult");
        byType.put("files.edits.remove", "handleRemoveFileFromEdits");
        byType.put("tab.create.requested", "createSessionTab");

        Map<String, String> byFunction = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : byType.entrySet()) {
            // The first mapping wins: a function may serve several semantic
            // types (the prompt close signals share one implementation).
            byFunction.putIfAbsent(entry.getValue(), entry.getKey());
        }

        FUNCTION_BY_TYPE = Collections.unmodifiableMap(byType);
        TYPE_BY_FUNCTION = Collections.unmodifiableMap(byFunction);
    }

    /**
     * The semantic type of a host event.
     *
     * @return the semantic type, or the function name itself when the event is
     *         not (yet) part of the catalog
     */
    public static String semanticType(String functionName) {
        if (functionName == null || functionName.isBlank()) {
            return functionName;
        }
        String bare = functionName.startsWith("window.")
                ? functionName.substring("window.".length())
                : functionName;
        String type = TYPE_BY_FUNCTION.get(bare);
        return type != null ? type : bare;
    }

    /**
     * The legacy function name of a semantic type.
     *
     * @return the function name, or {@code null} when the type is unknown
     */
    public static String functionName(String semanticType) {
        if (semanticType == null) {
            return null;
        }
        return FUNCTION_BY_TYPE.get(semanticType);
    }

    public static Map<String, String> catalog() {
        return FUNCTION_BY_TYPE;
    }

    public static int size() {
        return FUNCTION_BY_TYPE.size();
    }
}
