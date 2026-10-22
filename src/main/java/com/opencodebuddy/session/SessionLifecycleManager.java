package com.opencodebuddy.session;

import com.opencodebuddy.bridge.NodeDetector;
import com.opencodebuddy.settings.OpenCodeBuddySettingsService;
import com.opencodebuddy.handler.UsagePushService;
import com.opencodebuddy.handler.core.HandlerContext;
import com.opencodebuddy.provider.opencode.OpenCodeSDKBridge;
import com.opencodebuddy.skill.SlashCommandRegistry;
import com.opencodebuddy.util.JsUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.ui.jcef.JBCefBrowser;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages session lifecycle operations: creation, history loading,
 * working directory resolution, slash commands, and permission mode sync.
 */
public class SessionLifecycleManager {

    private static final Logger LOG = Logger.getInstance(SessionLifecycleManager.class);
    private static final String PERMISSION_MODE_PROPERTY_KEY = "opencodebuddy.permission.mode";

    /**
     * Host interface providing access to window-level dependencies.
     */
    public interface SessionHost {
        Project getProject();

        OpenCodeSDKBridge getOpenCodeSDKBridge();

        OpencodeSession getSession();

        default OpencodeSession getSession(String sessionId) {
            return getSession();
        }

        void setSession(OpencodeSession session);

        default void registerSession(String sessionId, OpencodeSession session, SessionCallbackAdapter adapter, StreamMessageCoalescer coalescer) {
            setSession(session);
        }

        /**
         * Bind a history session to the tab that requested it. Implementations
         * should update the tab registry and the active host pointer without
         * hydrating the transcript (the loader owns that handshake).
         */
        default void bindHistorySession(
                String tabId,
                String sessionId,
                OpencodeSession session,
                SessionCallbackAdapter adapter,
                StreamMessageCoalescer coalescer
        ) {
            registerSession(sessionId, session, adapter, coalescer);
        }

        default void closeSession(String sessionId) {}

        /** Mark the tab owning this session as fully loaded/idle. */
        default void markHistoryLoaded(String sessionId) {}

        default StreamMessageCoalescer getStreamCoalescer(String sessionId) {
            return getStreamCoalescer();
        }

        default SessionCallbackAdapter createCallbackAdapter(String sessionId, StreamMessageCoalescer coalescer) {
            return null;
        }

        default StreamMessageCoalescer createStreamCoalescer(String sessionId) {
            return null;
        }

        HandlerContext getHandlerContext();

        StreamMessageCoalescer getStreamCoalescer();

        void clearPendingPermissionRequests();

        void callJavaScript(String functionName, String... args);

        /** Publish a named event to the webview. */
        void publishTabEvent(com.opencodebuddy.tab.TabEvent event);

        /** The tab that owns a canonical session id (null when unknown). */
        String findTabIdForSession(String sessionId);

        boolean isDisposed();

        JBCefBrowser getBrowser();

        void setupSessionCallbacks();

        void invalidateSessionCallbacks();

        void setSlashCommandsFetched(boolean fetched);

        void setFetchedSlashCommandsCount(int count);
    }

    private final SessionHost host;

    public SessionLifecycleManager(SessionHost host) {
        this.host = host;
    }

    /**
     * Load a history session by ID.
     */
    public void loadHistorySession(String sessionId, String projectPath) {
        loadHistorySession(sessionId, projectPath, null, null);
    }

    /**
     * Load a history session by ID and provider.
     */
    public void loadHistorySession(String sessionId, String projectPath, String provider) {
        loadHistorySession(sessionId, projectPath, provider, null);
    }

    /**
     * Load a history session by ID, provider, and optional model from the history row.
     *
     * @param model when non-blank, restores that model instead of keeping the previous UI selection
     */
    public void loadHistorySession(String sessionId, String projectPath, String provider, String model) {
        loadHistorySession(sessionId, projectPath, provider, model, null);
    }

    /**
     * Load a history session into a specific tab.
     *
     * <p>The webview owns the stable tab id; the host must bind the loaded
     * session to that tab before hydrating so late deltas route to the right
     * transcript even when another tab is visible.</p>
     */
    public void loadHistorySession(String sessionId, String projectPath, String provider, String model, String tabId) {
        LOG.info("Loading history session: " + sessionId + " from project: " + projectPath
                + (tabId != null ? ", tab: " + tabId : ""));

        OpencodeSession existingSession = host.getSession(sessionId);
        if (existingSession != null && sessionId.equals(existingSession.getSessionId())) {
            LOG.info("Session " + sessionId + " already exists in pool, restoring to frontend: " + sessionId);
            if (model != null && !model.trim().isEmpty()) {
                existingSession.setModel(model.trim());
            }
            host.bindHistorySession(tabId, sessionId, existingSession, null, null);

            StreamMessageCoalescer coalescer = host.getStreamCoalescer(sessionId);
            if (coalescer == null) {
                coalescer = host.getStreamCoalescer();
            }
            long clearBarrierSeq = (coalescer != null) ? coalescer.resetStreamState() : 0L;
            host.publishTabEvent(com.opencodebuddy.tab.TabEvent.of("session.cleared")
                    .session(sessionId)
                    .tab(host.findTabIdForSession(sessionId))
                    .field("sequence", clearBarrierSeq)
                    .build());

            final StreamMessageCoalescer coalescerToFlush = coalescer;
            existingSession.loadFromServer().thenRun(() -> ApplicationManager.getApplication().invokeLater(() -> {
                completeHistoryLoadAfterCoalescerFlush(sessionId, existingSession, coalescerToFlush);
            })).exceptionally(ex -> {
                ApplicationManager.getApplication().invokeLater(() -> {
                    host.publishTabEvent(com.opencodebuddy.tab.TabEvent.of("session.loaded")
                            .session(sessionId)
                            .tab(host.findTabIdForSession(sessionId))
                            .build());
                    host.callJavaScript("addErrorMessage", sessionId,
                            JsUtils.escapeJs("Failed to load session: " + ex.getMessage()));
                });
                return null;
            });
            return;
        }

        OpencodeSession oldSession = host.getSession();
        String previousPermissionMode;
        String previousProvider;
        String previousModel;

        if (oldSession != null) {
            previousPermissionMode = oldSession.getPermissionMode();
            previousProvider = oldSession.getProvider();
            previousModel = oldSession.getModel();
        } else {
            PropertiesComponent props = PropertiesComponent.getInstance();
            String savedMode = props.getValue(PERMISSION_MODE_PROPERTY_KEY);
            OpencodeSession defaultSession = createDefaultSession();
            previousPermissionMode = (savedMode != null && !savedMode.trim().isEmpty())
                                             ? savedMode.trim() : defaultSession.getPermissionMode();
            previousProvider = defaultSession.getProvider();
            previousModel = defaultSession.getModel();
        }
        String modelToRestore = (model != null && !model.trim().isEmpty()) ? model.trim() : previousModel;
        LOG.info("Preserving session state when loading history: mode=" + previousPermissionMode
                         + ", provider=" + previousProvider + ", model=" + modelToRestore
                         + (model != null && !model.trim().isEmpty() ? " (from history)" : " (previous)"));

        OpencodeSession newSession = createDefaultSession();
        newSession.setPermissionMode(previousPermissionMode);
        newSession.setProvider(provider != null && !provider.trim().isEmpty() ? provider : previousProvider);
        newSession.setModel(modelToRestore);
        LOG.info("Restored session state to loaded session: mode=" + previousPermissionMode
                         + ", provider=" + newSession.getProvider() + ", model=" + modelToRestore);

        StreamMessageCoalescer newCoalescer = host.createStreamCoalescer(sessionId);
        if (newCoalescer == null) {
            newCoalescer = host.getStreamCoalescer();
        }
        SessionCallbackAdapter newAdapter = host.createCallbackAdapter(sessionId, newCoalescer);
        if (newAdapter != null) {
            newSession.setCallback(newAdapter);
        }

        String effectiveDir = determineWorkingDirectory();
        String nodePath = NodeDetector.getInstance().getCachedNodePath();
        String workingDir = (projectPath != null && !projectPath.isEmpty())
                                ? projectPath
                                : (NodeDetector.isWslPath(nodePath) ? NodeDetector.convertToWslPath(effectiveDir) : effectiveDir);
        newSession.setSessionInfo(sessionId, workingDir);

        host.bindHistorySession(tabId, sessionId, newSession, newAdapter, newCoalescer);
        if (host.getHandlerContext() != null) {
            host.getHandlerContext().registerSession(sessionId, newSession);
        }

        long clearBarrierSeq = (newCoalescer != null) ? newCoalescer.resetStreamState() : 0L;
        host.publishTabEvent(com.opencodebuddy.tab.TabEvent.of("session.cleared")
                .session(sessionId)
                .tab(host.findTabIdForSession(sessionId))
                .field("sequence", clearBarrierSeq)
                .build());

        // Prewarm the opencode daemon for the historical session so the
        // first message is fast (serve + SSE subscription + session resolve).
        host.getOpenCodeSDKBridge().preconnect(sessionId, workingDir);

        final StreamMessageCoalescer coalescerToFlush = newCoalescer;
        newSession.loadFromServer().thenRun(() -> ApplicationManager.getApplication().invokeLater(() -> {
            completeHistoryLoadAfterCoalescerFlush(sessionId, newSession, coalescerToFlush);
        })).exceptionally(ex -> {
            ApplicationManager.getApplication().invokeLater(() -> {
                host.publishTabEvent(com.opencodebuddy.tab.TabEvent.of("session.loaded")
                        .session(sessionId)
                        .tab(host.findTabIdForSession(sessionId))
                        .build());
                host.callJavaScript("addErrorMessage", sessionId,
                        JsUtils.escapeJs("Failed to load session: " + ex.getMessage()));
            });
            return null;
        });
    }

    /**
     * Push any pending coalesced message snapshot to the webview, then signal
     * {@code historyLoadComplete} with the message count. Ensures the transcript
     * is not lost when the frontend holds {@code __sessionTransitioning} until complete.
     */
    private void completeHistoryLoadAfterCoalescerFlush(String sessionId, OpencodeSession loadedSession, StreamMessageCoalescer coalescer) {
        if (host.isDisposed()) {
            return;
        }
        int messageCount = loadedSession != null ? loadedSession.getMessages().size() : 0;
        if (coalescer == null) {
            host.publishTabEvent(com.opencodebuddy.tab.TabEvent.of("session.loaded")
                    .session(sessionId)
                    .tab(host.findTabIdForSession(sessionId))
                    .field("messageCount", messageCount)
                    .build());
            syncRevertState(sessionId, loadedSession);
            host.markHistoryLoaded(sessionId);
            return;
        }
        coalescer.flush(seq -> {
            if (!host.isDisposed()) {
                host.publishTabEvent(com.opencodebuddy.tab.TabEvent.of("session.loaded")
                        .session(sessionId)
                        .tab(host.findTabIdForSession(sessionId))
                        .field("messageCount", messageCount)
                        .build());
                syncRevertState(sessionId, loadedSession);
                host.markHistoryLoaded(sessionId);
            }
        });
    }

    private void syncRevertState(String sessionId, OpencodeSession session) {
        if (session == null) {
            return;
        }
        SessionState.RevertState rs = session.getRevertState();
        com.google.gson.JsonObject revertPayload = new com.google.gson.JsonObject();
        revertPayload.addProperty("hasRevert", rs != null);
        if (rs != null && rs.messageId != null && !rs.messageId.isBlank()) {
            revertPayload.addProperty("messageId", rs.messageId);
        } else {
            revertPayload.add("messageId", com.google.gson.JsonNull.INSTANCE);
        }
        host.publishTabEvent(com.opencodebuddy.tab.TabEvent.of("revert.changed")
                .session(sessionId)
                .tab(host.findTabIdForSession(sessionId))
                .json("state", revertPayload.toString())
                .build());
    }

    /**
     * Determine the working directory for the session.
     */
    public String determineWorkingDirectory() {
        String projectPath = host.getProject().getBasePath();

        if (projectPath == null || !new File(projectPath).exists()) {
            String userHome = NodeDetector.resolveHomeForFileOps();
            LOG.warn("Using user home directory as fallback: " + userHome);
            return userHome;
        }

        try {
            OpenCodeBuddySettingsService settingsService = new OpenCodeBuddySettingsService();
            // Normalized effective working directory (custom dir if valid, else the
            // project path). Collapsing relative segments here keeps the launched cwd
            // consistent with the directory history is read from.
            String resolvedPath = settingsService.getEffectiveWorkingDirectory(projectPath);
            if (resolvedPath != null && !resolvedPath.isEmpty()) {
                LOG.info("Using working directory: " + resolvedPath);
                return resolvedPath;
            }
        } catch (Exception e) {
            LOG.warn("Failed to resolve working directory: " + e.getMessage());
        }

        return projectPath;
    }

    /**
     * Fetches slash commands on startup or refresh.
     * Merges built-in commands with local and dynamic OpenCode daemon commands.
     */
    public void fetchSlashCommandsOnStartup() {
        fetchSlashCommandsOnStartup(null);
    }

    /**
     * Refresh the command palette for one tab. The tab may be unbound (no
     * session yet), in which case the active session or project path supplies
     * the cwd.
     */
    public void fetchSlashCommandsOnStartup(String tabId) {
        OpencodeSession currentSession = tabId != null ? host.getSession(tabId) : null;
        if (currentSession == null) {
            currentSession = host.getSession();
        }
        String cwd = currentSession != null ? currentSession.getCwd() : null;
        if (cwd == null) {
            cwd = host.getProject().getBasePath();
        }

        LOG.info("Fetching slash commands locally, cwd=" + cwd);

        String currentFilePath = getCurrentEditorFilePath();
        var commands = SlashCommandRegistry.getCommands(cwd, currentFilePath);
        String commandsJson = SlashCommandRegistry.toJson(commands);

        host.setFetchedSlashCommandsCount(commands.size());
        host.setSlashCommandsFetched(true);
        LOG.info("Slash commands resolved locally: " + commands.size() + " commands");

        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                host.callJavaScript("updateSlashCommands", JsUtils.escapeJs(commandsJson));
            } catch (Exception e) {
                LOG.warn("Failed to send slash commands to frontend: " + e.getMessage(), e);
            }
        });

        // Asynchronously enhance with dynamic commands from OpenCode daemon (opencode.json, server commands, etc.)
        if (host.getOpenCodeSDKBridge() != null) {
            final String targetCwd = cwd;
            final List<SlashCommandRegistry.SlashCommand> baselineCommands = new ArrayList<>(commands);
            host.getOpenCodeSDKBridge().listCommands(targetCwd).thenAccept(jsonElement -> {
                if (jsonElement == null) {
                    return;
                }
                try {
                    JsonArray rawCommands = null;
                    if (jsonElement.isJsonObject()) {
                        JsonObject obj = jsonElement.getAsJsonObject();
                        if (obj.has("commands") && obj.get("commands").isJsonArray()) {
                            rawCommands = obj.getAsJsonArray("commands");
                        }
                    } else if (jsonElement.isJsonArray()) {
                        rawCommands = jsonElement.getAsJsonArray();
                    }

                    if (rawCommands != null && !rawCommands.isEmpty()) {
                        Map<String, SlashCommandRegistry.SlashCommand> merged = new LinkedHashMap<>();
                        for (SlashCommandRegistry.SlashCommand cmd : baselineCommands) {
                            String key = cmd.name().trim().toLowerCase();
                            if (!key.startsWith("/")) {
                                key = "/" + key;
                            }
                            merged.put(key, cmd);
                        }

                        for (JsonElement item : rawCommands) {
                            if (!item.isJsonObject()) {
                                continue;
                            }
                            JsonObject cmdObj = item.getAsJsonObject();
                            if (!cmdObj.has("name")) {
                                continue;
                            }
                            String rawName = cmdObj.get("name").getAsString().trim();
                            if (rawName.isEmpty()) {
                                continue;
                            }
                            String label = rawName.startsWith("/") ? rawName : "/" + rawName;
                            String key = label.toLowerCase();
                            String desc = cmdObj.has("description") && !cmdObj.get("description").isJsonNull()
                                    ? cmdObj.get("description").getAsString() : "";
                            String source = cmdObj.has("source") && !cmdObj.get("source").isJsonNull()
                                    ? cmdObj.get("source").getAsString() : "opencode";
                            merged.put(key, new SlashCommandRegistry.SlashCommand(label, desc, source));
                        }

                        List<SlashCommandRegistry.SlashCommand> mergedList = new ArrayList<>(merged.values());
                        String mergedJson = SlashCommandRegistry.toJson(mergedList);
                        host.setFetchedSlashCommandsCount(mergedList.size());
                        ApplicationManager.getApplication().invokeLater(() -> {
                            try {
                                host.callJavaScript("updateSlashCommands", JsUtils.escapeJs(mergedJson));
                                LOG.info("Slash commands refreshed from OpenCode daemon: " + mergedList.size() + " commands");
                            } catch (Exception e) {
                                LOG.debug("Failed to send refreshed slash commands to frontend: " + e.getMessage());
                            }
                        });
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to parse dynamic slash commands from OpenCode daemon: " + e.getMessage(), e);
                }
            }).exceptionally(ex -> {
                LOG.debug("OpenCode daemon listCommands request not available or failed: " + ex.getMessage());
                return null;
            });
        }
    }

    /**
     * Send current permission mode to the frontend.
     */
    public void sendCurrentPermissionMode() {
        try {
            String currentMode = "default";

            OpencodeSession currentSession = host.getSession();
            if (currentSession != null) {
                String sessionMode = currentSession.getPermissionMode();
                if (sessionMode != null && !sessionMode.trim().isEmpty()) {
                    currentMode = sessionMode;
                }
            }

            final String modeToSend = currentMode;

            ApplicationManager.getApplication().invokeLater(() -> {
                if (!host.isDisposed() && host.getBrowser() != null) {
                    host.callJavaScript("window.onModeReceived", JsUtils.escapeJs(modeToSend));
                }
            });
        } catch (Exception e) {
            LOG.error("Failed to send current permission mode: " + e.getMessage(), e);
        }
    }

    /**
     * Clear transient context usage after creating a new session. The new provider has
     * not reported a trusted token count yet, so used/max values remain unknown.
     */
    private void resetTokenUsage() {
        new UsagePushService(host.getHandlerContext()).clearUsageDisplay();
    }

    private String getCurrentEditorFilePath() {
        return com.opencodebuddy.util.EditorFileUtils.getCurrentEditorFilePath(this.host.getProject());
    }

    private OpencodeSession createDefaultSession() {
        return new OpencodeSession(
                host.getProject(),
                host.getOpenCodeSDKBridge());
    }
}
