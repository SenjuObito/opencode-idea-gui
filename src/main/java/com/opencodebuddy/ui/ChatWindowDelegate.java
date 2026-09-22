package com.opencodebuddy.ui;

import com.opencodebuddy.i18n.OpenCodeBuddyBundle;
import com.opencodebuddy.session.OpencodeSession;
import com.opencodebuddy.settings.OpenCodeBuddySettingsService;
import com.opencodebuddy.handler.AgentHandler;
import com.opencodebuddy.handler.ClipboardHandler;
import com.opencodebuddy.handler.ContextHandler;
import com.opencodebuddy.handler.CliModelsHandler;
import com.opencodebuddy.handler.CliStatusHandler;
import com.opencodebuddy.handler.DaemonStatusHandler;
import com.opencodebuddy.handler.DiffHandler;
import com.opencodebuddy.handler.core.HandlerContext;
import com.opencodebuddy.provider.opencode.OpenCodeSDKBridge;
import com.opencodebuddy.handler.history.HistoryHandler;
import com.opencodebuddy.handler.McpServerHandler;
import com.opencodebuddy.handler.marketplace.McpMarketplaceHandler;
import com.opencodebuddy.handler.importer.McpServerImportHandler;
import com.opencodebuddy.handler.core.MessageDispatcher;
import com.opencodebuddy.handler.NodeProcessHandler;
import com.opencodebuddy.handler.PermissionHandler;
import com.opencodebuddy.handler.CommandsHandler;
import com.opencodebuddy.handler.RewindHandler;
import com.opencodebuddy.handler.SessionHandler;
import com.opencodebuddy.handler.SettingsHandler;
import com.opencodebuddy.handler.provider.ModelProviderHandler;
import com.opencodebuddy.handler.SkillHandler;
import com.opencodebuddy.handler.TabHandler;
import com.opencodebuddy.handler.UsagePushService;
import com.opencodebuddy.handler.WindowEventHandler;
import com.opencodebuddy.handler.file.FileExportHandler;
import com.opencodebuddy.handler.file.FileHandler;
import com.opencodebuddy.handler.file.OpenClassHandler;
import com.opencodebuddy.handler.file.UndoFileHandler;
import com.opencodebuddy.permission.PermissionService;
import com.opencodebuddy.session.SessionLifecycleManager;
import com.opencodebuddy.session.StreamMessageCoalescer;
import com.opencodebuddy.util.JsUtils;
import com.opencodebuddy.util.MessageJsonConverter;
import com.opencodebuddy.utils.PluginFileLogger;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.ui.content.Content;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.util.concurrency.AppExecutorUtil;

import javax.swing.*;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Delegates for initialization setup and runtime operations:
 * handler registration, permission setup, tab status, and frontend ready handling.
 */
public class ChatWindowDelegate {

    private static final Logger LOG = Logger.getInstance(ChatWindowDelegate.class);
    private static final String NODE_PATH_PROPERTY_KEY = "opencodebuddy.node.path";
    private static final String PERMISSION_MODE_PROPERTY_KEY = "opencodebuddy.permission.mode";
    private static final int STATUS_RESET_DELAY_SECONDS = 5;

    public enum TabAnswerStatus {
        IDLE,
        ANSWERING,
        COMPLETED
    }

    public interface DelegateHost {
        Project getProject();
        OpenCodeSDKBridge getOpenCodeSDKBridge();
        OpencodeSession getSession();
        OpenCodeBuddySettingsService getSettingsService();
        JPanel getMainPanel();
        JBCefBrowser getBrowser();
        boolean isDisposed();
        void callJavaScript(String fn, String... args);
        Content getParentContent();
        String getOriginalTabName();
        void setOriginalTabName(String name);
        String getSessionId();
        boolean isActiveContent();
        void activateContent();
        HandlerContext getHandlerContext();
        void setHandlerContext(HandlerContext ctx);
        void setMessageDispatcher(MessageDispatcher d);
        void setPermissionHandler(PermissionHandler h);
        void setHistoryHandler(HistoryHandler h);
        SessionLifecycleManager getSessionLifecycleManager();
        StreamMessageCoalescer getStreamCoalescer();
        WebviewWatchdog getWebviewWatchdog();
        PermissionHandler getPermissionHandler();
        void interruptDueToPermissionDenial();
        boolean isFrontendReady();
        boolean isRuntimeRecoveryPage();
        void setFrontendReady(boolean ready);
        void onHistoryRenderComplete(long commitEpoch);
        void onSurfaceDamageApplied(String token, String phase, boolean applied);
        void setSlashCommandsFetched(boolean fetched);
        void setFetchedSlashCommandsCount(int count);
        void persistTabSessionState();
        void updateTabTitle(String title);

        /**
         * Soft-reload the currently active session's transcript without interrupting
         * any in-flight turn.
         * <p>Invoked when the user re-opens the session that is already active —
         * refreshes the transcript from the server instead of tearing the session
         * down (interrupt + recreate). Safe to call while a turn is streaming: the
         * reload is deferred to stream end.</p>
         */
        void reloadActiveSessionMessages();
    }

    private final DelegateHost host;
    private TabAnswerStatus currentTabStatus = TabAnswerStatus.IDLE;
    private ScheduledFuture<?> statusResetTask;
    // Reference to the SettingsHandler for clean theme-callback unregistration on dispose.
    private com.opencodebuddy.handler.SettingsHandler settingsHandler;
    // Pushes {alive, serveReady} to the webview (check_daemon_status, frontend_ready, daemon lifecycle).
    private DaemonStatusHandler daemonStatusHandler;

    public ChatWindowDelegate(DelegateHost host) {
        this.host = host;
    }

    private void applyNodePathToBridges(String path) {
        // OpenCode daemon receives the node path at launch; per-bridge
        // overrides no longer apply.
    }

    private void applySessionIdToBridges(String sessionId) {
        // Session routing is per-request in the daemon; nothing to apply.
    }

    public void loadNodePathFromSettings() {
        try {
            String savedNodePath = new com.opencodebuddy.settings.OpenCodeBuddySettingsService().getNodePath();
            if (savedNodePath != null && !savedNodePath.trim().isEmpty()) {
                LOG.info("Using manually configured Node.js path: " + savedNodePath.trim());
            } else {
                LOG.info("No saved Node.js path found; daemon will auto-detect Node.js");
            }
        } catch (Exception e) {
            LOG.warn("Failed to load Node.js path: " + e.getMessage());
        }
    }

    public void loadPermissionModeFromSettings() {
        try {
            PropertiesComponent props = PropertiesComponent.getInstance();
            String savedMode = props.getValue(PERMISSION_MODE_PROPERTY_KEY);
            if (savedMode != null && !savedMode.trim().isEmpty()) {
                String mode = savedMode.trim();
                OpencodeSession session = host.getSession();
                if (session != null) {
                    session.setPermissionMode(mode);
                    host.persistTabSessionState();
                    LOG.info("Loaded permission mode from settings: " + mode);
                    com.opencodebuddy.notifications.OpencodeNotifier.setMode(host.getProject(), mode);
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to load permission mode: " + e.getMessage());
        }
    }

    public void savePermissionModeToSettings(String mode) {
        try {
            PropertiesComponent props = PropertiesComponent.getInstance();
            props.setValue(PERMISSION_MODE_PROPERTY_KEY, mode);
            LOG.info("Saved permission mode to settings: " + mode);
        } catch (Exception e) {
            LOG.warn("Failed to save permission mode: " + e.getMessage());
        }
    }

    /**
     * Intentionally a no-op for startup. OpenCode-only build: provider
     * configuration is owned by the opencode CLI itself; the plugin never
     * writes provider settings on the user's behalf.
     */
    public void syncActiveProvider() {
    }

    public String setupPermissionService() {
        Project project = host.getProject();
        String sessionId = java.util.UUID.randomUUID().toString();

        LOG.info("PermissionService routing key: " + sessionId);

        PermissionService permissionService = PermissionService.getInstance(project, sessionId);
        permissionService.start();
        permissionService.registerDialogShower(project, (toolName, inputs) ->
            host.getPermissionHandler().showFrontendPermissionDialog(toolName, inputs));
        permissionService.registerAskUserQuestionDialogShower(project, (requestId, questionsData) ->
            host.getPermissionHandler().showAskUserQuestionDialog(requestId, questionsData));
        permissionService.registerPlanApprovalDialogShower(project, (requestId, planData) ->
            host.getPermissionHandler().showPlanApprovalDialog(requestId, planData));
        LOG.info("Started permission service with frontend dialog, AskUserQuestion dialog, and PlanApproval dialog for project: " + project.getName());
        return sessionId;
    }

    public void initializeHandlers() {
        Project project = host.getProject();
        OpenCodeSDKBridge openCodeSDKBridge = host.getOpenCodeSDKBridge();
        OpenCodeBuddySettingsService settingsService = host.getSettingsService();

        HandlerContext.JsCallback jsCallback = new HandlerContext.JsCallback() {
            @Override
            public void callJavaScript(String functionName, String... args) {
                host.callJavaScript(functionName, args);
            }
            @Override
            public String escapeJs(String str) {
                return JsUtils.escapeJs(str);
            }
        };

        HandlerContext handlerContext = new HandlerContext(
                project,
                openCodeSDKBridge,
                settingsService,
                jsCallback,
                host::isActiveContent,
                () -> {
                    String originalTabName = host.getOriginalTabName();
                    if (originalTabName != null && !originalTabName.isBlank()) {
                        return originalTabName;
                    }
                    Content content = host.getParentContent();
                    return content == null ? null : content.getDisplayName();
                });
        handlerContext.setContentActivator(host::activateContent);
        handlerContext.setSession(host.getSession());
        host.setHandlerContext(handlerContext);

        MessageDispatcher messageDispatcher = new MessageDispatcher();
        host.setMessageDispatcher(messageDispatcher);

        messageDispatcher.registerHandler(new McpServerHandler(handlerContext));
        messageDispatcher.registerHandler(new McpMarketplaceHandler(handlerContext));
        messageDispatcher.registerHandler(new McpServerImportHandler(handlerContext));
        messageDispatcher.registerHandler(new SkillHandler(handlerContext));
        messageDispatcher.registerHandler(new FileHandler(handlerContext));
        this.settingsHandler = new SettingsHandler(handlerContext);
        messageDispatcher.registerHandler(this.settingsHandler);
        messageDispatcher.registerHandler(new SessionHandler(handlerContext));
        messageDispatcher.registerHandler(new ContextHandler(handlerContext));
        messageDispatcher.registerHandler(new FileExportHandler(handlerContext));
        messageDispatcher.registerHandler(new DiffHandler(handlerContext));
        messageDispatcher.registerHandler(new AgentHandler(handlerContext));
        messageDispatcher.registerHandler(new CommandsHandler(handlerContext));
        messageDispatcher.registerHandler(new TabHandler(handlerContext, host::updateTabTitle));
        messageDispatcher.registerHandler(new RewindHandler(handlerContext));
        messageDispatcher.registerHandler(new UndoFileHandler(handlerContext));
        messageDispatcher.registerHandler(new CliModelsHandler(handlerContext));
        this.daemonStatusHandler = new DaemonStatusHandler(handlerContext);
        messageDispatcher.registerHandler(this.daemonStatusHandler);
        // Daemon death/restart flips the webview between the loading and
        // "not running / retry" states (window.updateDaemonStatus).
        openCodeSDKBridge.setDaemonLifecycleListener(new com.opencodebuddy.provider.common.DaemonBridge.DaemonLifecycleListener() {
            @Override
            public void onDaemonReady() {
                daemonStatusHandler.onDaemonReady();
            }

            @Override
            public void onDaemonDied() {
                daemonStatusHandler.onDaemonDied();
            }
        });
        messageDispatcher.registerHandler(new CliStatusHandler(handlerContext));
        messageDispatcher.registerHandler(new ClipboardHandler(handlerContext));
        messageDispatcher.registerHandler(new NodeProcessHandler(handlerContext));

        messageDispatcher.registerHandler(new WindowEventHandler(handlerContext, new WindowEventHandler.Callback() {
            @Override public void onHeartbeat(String content) { host.getWebviewWatchdog().handleHeartbeat(content); }
            @Override public void onTabLoadingChanged(boolean loading) { updateTabLoadingState(loading); }
            @Override public void onTabStatusChanged(String statusStr) {
                TabAnswerStatus status;
                switch (statusStr) {
                    case "answering":
                        status = TabAnswerStatus.ANSWERING;
                        break;
                    case "completed":
                        status = TabAnswerStatus.COMPLETED;
                        break;
                    default:
                        status = TabAnswerStatus.IDLE;
                        break;
                }
                updateTabStatus(status);
            }
            @Override public void onCreateNewSession() {
                host.getSessionLifecycleManager().createNewSession();
            }
            @Override public void onFrontendReady() { handleFrontendReady(); }
            @Override public void onHistoryRenderComplete(long commitEpoch) {
                host.onHistoryRenderComplete(commitEpoch);
            }
            @Override public void onSurfaceDamageApplied(
                    String token,
                    String phase,
                    boolean applied
            ) {
                host.onSurfaceDamageApplied(token, phase, applied);
            }
            @Override public void onRefreshSlashCommands() {
                host.getSessionLifecycleManager().fetchSlashCommandsOnStartup();
            }
        }));

        PermissionHandler permissionHandler = new PermissionHandler(handlerContext);
        permissionHandler.setPermissionDeniedCallback(host::interruptDueToPermissionDenial);
        host.setPermissionHandler(permissionHandler);
        messageDispatcher.registerHandler(permissionHandler);

        HistoryHandler historyHandler = new HistoryHandler(handlerContext);
        historyHandler.setSessionLoadCallback((sessionId, projectPath, provider, model) -> {
            OpencodeSession current = host.getSession();
            boolean sameSession = current != null
                    && sessionId != null
                    && sessionId.equals(current.getSessionId())
                    && (provider == null || provider.trim().isEmpty()
                                || provider.equals(current.getProvider()));
            if (sameSession) {
                // Re-opening the very session already active: soft-reload its transcript
                // instead of interrupting the in-flight turn.
                LOG.info("[HistoryHandler] Same-session resume, soft-reloading transcript: " + sessionId);
                if (model != null && !model.trim().isEmpty()) {
                    current.setModel(model.trim());
                }
                host.reloadActiveSessionMessages();
            } else {
                host.getSessionLifecycleManager().loadHistorySession(sessionId, projectPath, provider, model);
            }
        });
        host.setHistoryHandler(historyHandler);
        messageDispatcher.registerHandler(historyHandler);

        LOG.info("Registered " + messageDispatcher.getHandlerCount() + " message handlers");
    }

    public void initializeStatusBar() {
        ApplicationManager.getApplication().invokeLater(() -> {
            Project project = host.getProject();
            if (project == null || host.isDisposed()) { return; }

            OpencodeSession session = host.getSession();
            String mode = session != null ? session.getPermissionMode() : "default";
            com.opencodebuddy.notifications.OpencodeNotifier.setMode(project, mode);

            String model = session != null ? session.getModel() : "claude-sonnet-5";
            com.opencodebuddy.notifications.OpencodeNotifier.setModel(project, model);

            try {
                OpenCodeBuddySettingsService settingsService = host.getSettingsService();
                String selectedId = settingsService.getSelectedAgentId();
                if (selectedId != null) {
                    JsonObject agent = settingsService.getAgent(selectedId);
                    if (agent != null) {
                        String agentName = agent.has("name") ? agent.get("name").getAsString() : "Agent";
                        com.opencodebuddy.notifications.OpencodeNotifier.setAgent(project, agentName);
                    }
                }
            } catch (Exception e) {
                LOG.warn("Failed to set initial agent in status bar: " + e.getMessage());
            }
        });
    }

    public void updateTabStatus(TabAnswerStatus status) {
        Content parentContent = host.getParentContent();
        String originalTabName = host.getOriginalTabName();
        if (parentContent == null || originalTabName == null) {
            LOG.warn("[TabStatus] Cannot update - parentContent or originalTabName is null");
            return;
        }

        if (status == currentTabStatus) {
            LOG.debug("[TabStatus] Skipping redundant update for tab: " + originalTabName);
            return;
        }

        currentTabStatus = status;

        if (statusResetTask != null && !statusResetTask.isDone()) {
            statusResetTask.cancel(false);
            statusResetTask = null;
        }

        ApplicationManager.getApplication().invokeLater(() -> {
            String tabName = originalTabName;
            String currentDisplayName = parentContent.getDisplayName();
            if (currentDisplayName != null && !currentDisplayName.startsWith(tabName)) {
                tabName = currentDisplayName.endsWith("...")
                    ? currentDisplayName.substring(0, currentDisplayName.length() - 3)
                    : currentDisplayName;
                host.setOriginalTabName(tabName);
                LOG.debug("[TabStatus] Detected external rename, updated originalTabName to: " + tabName);
            }

            String displayName;
            switch (status) {
                case ANSWERING:
                    displayName = tabName + "...";
                    LOG.debug("[TabStatus] Set answering state for tab: " + displayName);
                    break;
                case COMPLETED:
                    String completedText = OpenCodeBuddyBundle.message("tab.status.completed");
                    displayName = tabName + " (" + completedText + ")";
                    LOG.debug("[TabStatus] Set completed state for tab: " + displayName);

                    statusResetTask = AppExecutorUtil.getAppScheduledExecutorService().schedule(() -> {
                        ApplicationManager.getApplication().invokeLater(() -> {
                            updateTabStatus(TabAnswerStatus.IDLE);
                        });
                    }, STATUS_RESET_DELAY_SECONDS, TimeUnit.SECONDS);
                    break;
                case IDLE:
                default:
                    displayName = tabName;
                    LOG.debug("[TabStatus] Restored idle state for tab: " + displayName);
                    break;
            }
            parentContent.setDisplayName(displayName);
        });
    }

    @Deprecated
    public void updateTabLoadingState(boolean loading) {
        updateTabStatus(loading ? TabAnswerStatus.ANSWERING : TabAnswerStatus.IDLE);
    }

    public void handleFrontendReady() {
        LOG.info("Received frontend_ready signal, frontend is now ready to receive data");
        PluginFileLogger.info("LIFECYCLE", "frontend_ready received");
        boolean runtimeRecovery = host.isRuntimeRecoveryPage();
        host.setFrontendReady(true);
        host.getWebviewWatchdog().markFrontendReady();

        host.callJavaScript(
            "window.updateLinkifyCapabilities",
            JsUtils.escapeJs(OpenClassHandler.buildCapabilitiesJson())
        );
        // Daemon/serve status: clears the webview's "starting OpenCode service"
        // spinner as soon as the state is known (serveReady:true) or shows the
        // retryable "not running" row (alive:false).
        if (daemonStatusHandler != null) {
            daemonStatusHandler.checkAndPush();
        }
        if (runtimeRecovery) {
            pushCurrentTabStateToFrontend();
        }
        host.getSessionLifecycleManager().sendCurrentPermissionMode();
        replayCurrentSessionStateToFrontend();
        if (runtimeRecovery) {
            refreshFrontendDerivedState();
        }
        host.persistTabSessionState();

        host.getStreamCoalescer().flush(null);
    }

    /**
     * Pushes the current Java session configuration before replaying its transcript.
     * A native JCEF reload reuses the tab's original HTML snapshot, so Java remains
     * authoritative for provider and model selection during watchdog recovery.
     */
    private void pushCurrentTabStateToFrontend() {
        OpencodeSession session = host.getSession();
        if (session == null || host.isDisposed()) {
            return;
        }

        String payload = buildBackendTabStateJson(
                session.getProvider(),
                session.getModel(),
                session.getPermissionMode(),
                session.getReasoningEffort(),
                session.getCodexServiceTier()
        );
        host.callJavaScript("window.applyBackendTabState", JsUtils.escapeJs(payload));
    }

    /**
     * Builds the authoritative tab-state snapshot consumed by the frontend recovery callback.
     * Package-private visibility keeps serialization independently testable without JCEF.
     */
    static String buildBackendTabStateJson(
            String provider,
            String model,
            String permissionMode,
            String reasoningEffort,
            String codexServiceTier
    ) {
        JsonObject state = new JsonObject();
        state.addProperty("provider", provider);
        state.addProperty("model", model);
        state.addProperty("permissionMode", permissionMode);
        state.addProperty("reasoningEffort", reasoningEffort);
        state.addProperty("codexFastMode", "fast".equals(codexServiceTier) ? "fast" : "normal");
        return state.toString();
    }

    /**
     * Replays the non-mutating UI refreshes formerly triggered by boot-time provider/model sync.
     * Usage is restored only from an existing provider snapshot. Empty sessions may still be
     * loading history and must not overwrite a valid frontend value with a synthetic zero.
     */
    private void refreshFrontendDerivedState() {
        OpencodeSession session = host.getSession();
        HandlerContext context = host.getHandlerContext();
        if (session == null || context == null || host.isDisposed()) {
            return;
        }

        UsagePushService usagePushService = new UsagePushService(context);
        usagePushService.pushCurrentUsageIfAvailable(resolveModelContextLimitForRecovery(
                session.getProvider(),
                session.getModel(),
                context.getSettingsService()
        ));
        usagePushService.refreshContextBar();
    }

    /**
     * Resolves the context window limit for the current provider/model.
     */
    static int resolveModelContextLimitForRecovery(
            String provider,
            String model,
            OpenCodeBuddySettingsService settingsService
    ) {
        return ModelProviderHandler.getModelContextLimit(provider, model);
    }

    private void replayCurrentSessionStateToFrontend() {
        OpencodeSession session = host.getSession();
        if (session == null || host.isDisposed()) {
            return;
        }

        try {
            String sessionId = session.getSessionId();
            if (sessionId != null && !sessionId.trim().isEmpty()) {
                host.callJavaScript("setSessionId", JsUtils.escapeJs(sessionId));
            }

            List<OpencodeSession.Message> messages = session.getMessages();
            if (!messages.isEmpty()) {
                String messagesJson = MessageJsonConverter.convertMessagesToJson(messages);
                host.callJavaScript("updateMessages", JsUtils.escapeJs(messagesJson));
            }

            host.callJavaScript("showLoading", String.valueOf(session.isLoading()));
            host.callJavaScript("showThinkingStatus", String.valueOf(false));

            String summary = session.getSummary();
            if (summary != null && !summary.trim().isEmpty()) {
                host.callJavaScript("showSummary", JsUtils.escapeJs(summary));
            }

            // FIX: Restore streaming state after webview reload.
            // When the watchdog reloads the webview during active streaming, the frontend's
            // isStreamingRef is reset to false, causing all onContentDelta callbacks to be
            // silently dropped.  Re-sending onStreamStart ensures the frontend accepts
            // subsequent streaming deltas and the stall watchdog is properly initialized.
            boolean streamActive = host.getStreamCoalescer().isStreamActive();
            if (streamActive) {
                LOG.debug("Replaying streaming state to frontend (session was actively streaming during reload)");
                host.callJavaScript("onStreamStart", "replay");
            }

            LOG.info("Replayed current session state to frontend: sessionId="
                    + (sessionId != null ? sessionId : "(none)")
                    + ", messages=" + messages.size()
                    + ", loading=" + session.isLoading()
                    + ", streaming=" + streamActive);
        } catch (Exception e) {
            LOG.warn("Failed to replay current session state to frontend: " + e.getMessage(), e);
        }
    }

    public void dispose() {
        if (statusResetTask != null && !statusResetTask.isDone()) {
            statusResetTask.cancel(false);
            statusResetTask = null;
            LOG.debug("[TabStatus] Cancelled pending status reset task");
        }
        // Unregister the theme-change callback to prevent notifications to the disposed webview.
        // This fixes the "Cannot call JS function window.onIdeThemeChanged: disposed=true" warning
        // and ensures stale sessions don't interfere with theme updates for remaining windows.
        if (settingsHandler != null) {
            settingsHandler.dispose();
            settingsHandler = null;
        }
    }
}
