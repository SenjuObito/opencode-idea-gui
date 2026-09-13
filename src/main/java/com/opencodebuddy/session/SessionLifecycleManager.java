package com.opencodebuddy.session;

import com.opencodebuddy.bridge.NodeDetector;
import com.opencodebuddy.model.SessionTemplate;
import com.opencodebuddy.settings.CodemossSettingsService;
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
import java.util.concurrent.CompletableFuture;

/**
 * Manages session lifecycle operations: creation, history loading,
 * working directory resolution, slash commands, and permission mode sync.
 */
public class SessionLifecycleManager {

    private static final Logger LOG = Logger.getInstance(SessionLifecycleManager.class);
    private static final String PERMISSION_MODE_PROPERTY_KEY = "claude.code.permission.mode";

    /**
     * Host interface providing access to window-level dependencies.
     */
    public interface SessionHost {
        Project getProject();

        OpenCodeSDKBridge getOpenCodeSDKBridge();

        ClaudeSession getSession();

        void setSession(ClaudeSession session);

        HandlerContext getHandlerContext();

        StreamMessageCoalescer getStreamCoalescer();

        void clearPendingPermissionRequests();

        void clearPermissionDecisionMemory();

        void callJavaScript(String functionName, String... args);

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
     * Create a new session, interrupting the old one first.
     */
    public void createNewSession() {
        LOG.info("Creating new session...");

        ClaudeSession oldSession = host.getSession();
        ClaudeSession defaultSession = createDefaultSession();
        String previousPermissionMode = (oldSession != null) ? oldSession.getPermissionMode() : defaultSession.getPermissionMode();
        String previousProvider = (oldSession != null) ? oldSession.getProvider() : defaultSession.getProvider();
        String previousModel = (oldSession != null) ? oldSession.getModel() : defaultSession.getModel();
        LOG.info("Preserving session state: mode=" + previousPermissionMode
                         + ", provider=" + previousProvider + ", model=" + previousModel);

        host.invalidateSessionCallbacks();
        long clearBarrierSeq = host.getStreamCoalescer().resetStreamState();
        host.callJavaScript("clearMessages", String.valueOf(clearBarrierSeq));

        CompletableFuture<Void> interruptFuture = oldSession != null
                                                          ? oldSession.interrupt()
                                                          : CompletableFuture.completedFuture(null);

        interruptFuture.thenRun(() -> {
            if (oldSession != null) {
                String oldEpoch = oldSession.getRuntimeSessionEpoch();
                LOG.info("[Lifecycle] Requested daemon runtime reset for old epoch=" + oldEpoch);
            }
            LOG.info("Old session interrupted, creating new session");

            ApplicationManager.getApplication().invokeLater(() -> {
                host.callJavaScript("onStreamEnd");
                host.callJavaScript("showLoading", "false");
            });

            ClaudeSession newSession = createDefaultSession();
            newSession.setPermissionMode(previousPermissionMode);
            newSession.setProvider(previousProvider);
            newSession.setModel(previousModel);
            LOG.info("Restored session state to new session: mode=" + previousPermissionMode
                             + ", provider=" + previousProvider + ", model=" + previousModel);

            completeNewSessionBootstrap(newSession, determineWorkingDirectory(),
                    "New session created successfully, working directory: ");
        }).exceptionally(ex -> {
            LOG.error("Failed to create new session: " + ex.getMessage(), ex);
            ApplicationManager.getApplication().invokeLater(() -> {
                host.callJavaScript("historyLoadComplete");
                host.callJavaScript("updateStatus",
                        JsUtils.escapeJs("Failed to create new session: " + ex.getMessage()));
            });
            return null;
        });
    }

    /**
     * Create a new session from a template, interrupting the old one first.
     */
    public void createNewSessionFromTemplate(SessionTemplate template) {
        LOG.info("Creating new session from template: " + template.getName());

        ClaudeSession oldSession = host.getSession();

        host.invalidateSessionCallbacks();
        long clearBarrierSeq = host.getStreamCoalescer().resetStreamState();
        host.callJavaScript("clearMessages", String.valueOf(clearBarrierSeq));

        CompletableFuture<Void> interruptFuture = oldSession != null
                ? oldSession.interrupt()
                : CompletableFuture.completedFuture(null);

        interruptFuture.thenRun(() -> {
            if (oldSession != null) {
                String oldEpoch = oldSession.getRuntimeSessionEpoch();
                LOG.info("[Lifecycle] Requested daemon runtime reset for old epoch=" + oldEpoch);
            }
            LOG.info("Old session interrupted, creating new session from template");

            ApplicationManager.getApplication().invokeLater(() -> {
                host.callJavaScript("onStreamEnd");
                host.callJavaScript("showLoading", "false");
            });

            ClaudeSession newSession = createDefaultSession();

            // Apply template settings
            if (template.getPermissionMode() != null) {
                newSession.setPermissionMode(template.getPermissionMode());
            }
            if (template.getProvider() != null) {
                newSession.setProvider(template.getProvider());
            }
            if (template.getModel() != null) {
                newSession.setModel(template.getModel());
            }
            if (template.getReasoningEffort() != null) {
                newSession.setReasoningEffort(template.getReasoningEffort());
            }
            newSession.getState().setPsiContextEnabled(template.isPsiContextEnabled());

            LOG.info("Applied template settings to new session: provider=" + template.getProvider()
                    + ", model=" + template.getModel() + ", mode=" + template.getPermissionMode());

            String workingDirectory = template.getCwd() != null && !template.getCwd().trim().isEmpty()
                    ? template.getCwd() : determineWorkingDirectory();
            completeNewSessionBootstrap(newSession, workingDirectory,
                    "New session created from template successfully, working directory: ");
        }).exceptionally(ex -> {
            LOG.error("Failed to create new session from template: " + ex.getMessage(), ex);
            ApplicationManager.getApplication().invokeLater(() -> {
                host.callJavaScript("historyLoadComplete");
                host.callJavaScript("updateStatus",
                        JsUtils.escapeJs("Failed to create new session from template: " + ex.getMessage()));
            });
            return null;
        });
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
        LOG.info("Loading history session: " + sessionId + " from project: " + projectPath);

        ClaudeSession oldSession = host.getSession();
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
            ClaudeSession defaultSession = createDefaultSession();
            previousPermissionMode = (savedMode != null && !savedMode.trim().isEmpty())
                                             ? savedMode.trim() : defaultSession.getPermissionMode();
            previousProvider = defaultSession.getProvider();
            previousModel = defaultSession.getModel();
        }
        String modelToRestore = (model != null && !model.trim().isEmpty()) ? model.trim() : previousModel;
        LOG.info("Preserving session state when loading history: mode=" + previousPermissionMode
                         + ", provider=" + previousProvider + ", model=" + modelToRestore
                         + (model != null && !model.trim().isEmpty() ? " (from history)" : " (previous)"));

        host.invalidateSessionCallbacks();
        long clearBarrierSeq = host.getStreamCoalescer().resetStreamState();
        host.callJavaScript("clearMessages", String.valueOf(clearBarrierSeq));
        host.clearPendingPermissionRequests();
        host.clearPermissionDecisionMemory();

        CompletableFuture<Void> interruptFuture = oldSession != null
                ? oldSession.interrupt()
                : CompletableFuture.completedFuture(null);

        interruptFuture.thenRun(() -> {
            if (oldSession != null) {
                String oldEpoch = oldSession.getRuntimeSessionEpoch();
                LOG.info("[Lifecycle] Requested daemon runtime reset before history load for old epoch="
                        + oldEpoch);
            }

            ClaudeSession newSession = createDefaultSession();
            newSession.setPermissionMode(previousPermissionMode);
            newSession.setProvider(provider != null && !provider.trim().isEmpty() ? provider : previousProvider);
            newSession.setModel(modelToRestore);
            LOG.info("Restored session state to loaded session: mode=" + previousPermissionMode
                             + ", provider=" + newSession.getProvider() + ", model=" + modelToRestore);

            host.setSession(newSession);
            host.getHandlerContext().setSession(newSession);
            host.setupSessionCallbacks();

            String workingDir = (projectPath != null && !projectPath.isEmpty())
                                    ? projectPath : NodeDetector.convertToWslPath(determineWorkingDirectory());
            newSession.setSessionInfo(sessionId, workingDir);

            // Prewarm the opencode daemon for the historical session so the
            // first message is fast (serve + SSE subscription + session resolve).
            host.getOpenCodeSDKBridge().preconnect(sessionId, workingDir);

            newSession.loadFromServer().thenRun(() -> ApplicationManager.getApplication().invokeLater(() -> {
                // loadFromServer only enqueues updateMessages through the coalescer; if we
                // call historyLoadComplete immediately the frontend releases the transition
                // guard before the snapshot arrives (or a reordered clearMessages can wipe a
                // stashed snapshot). Flush the coalescer first so messages land before complete.
                completeHistoryLoadAfterCoalescerFlush(newSession);
            })).exceptionally(ex -> {
                ApplicationManager.getApplication().invokeLater(() -> {
                    // Release transition guard so the frontend is not permanently stuck
                    host.callJavaScript("historyLoadComplete");
                    host.callJavaScript("addErrorMessage",
                            JsUtils.escapeJs("Failed to load session: " + ex.getMessage()));
                });
                return null;
            });
        }).exceptionally(ex -> {
            LOG.error("Failed to load history session: " + ex.getMessage(), ex);
            ApplicationManager.getApplication().invokeLater(() -> {
                host.callJavaScript("historyLoadComplete");
                host.callJavaScript("addErrorMessage",
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
    private void completeHistoryLoadAfterCoalescerFlush(ClaudeSession loadedSession) {
        if (host.isDisposed()) {
            return;
        }
        int messageCount = loadedSession != null ? loadedSession.getMessages().size() : 0;
        StreamMessageCoalescer coalescer = host.getStreamCoalescer();
        if (coalescer == null) {
            host.callJavaScript("historyLoadComplete", String.valueOf(messageCount));
            syncRevertState(loadedSession);
            return;
        }
        coalescer.flush(seq -> {
            if (!host.isDisposed()) {
                host.callJavaScript("historyLoadComplete", String.valueOf(messageCount));
                syncRevertState(loadedSession);
            }
        });
    }

    private void syncRevertState(ClaudeSession session) {
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
        host.callJavaScript("onRevertStateUpdate", revertPayload.toString());
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
            CodemossSettingsService settingsService = new CodemossSettingsService();
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
        ClaudeSession currentSession = host.getSession();
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

            ClaudeSession currentSession = host.getSession();
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

    private ClaudeSession createDefaultSession() {
        return new ClaudeSession(
                host.getProject(),
                host.getOpenCodeSDKBridge());
    }

    private void completeNewSessionBootstrap(ClaudeSession newSession, String workingDirectory, String successLogPrefix) {
        host.clearPendingPermissionRequests();
        host.clearPermissionDecisionMemory();
        host.setSession(newSession);
        host.getHandlerContext().setSession(newSession);
        host.setupSessionCallbacks();

        newSession.setSessionInfo(null, workingDirectory);
        LOG.info(successLogPrefix + workingDirectory + ", epoch=" + newSession.getRuntimeSessionEpoch());
        host.getOpenCodeSDKBridge().preconnect(null, workingDirectory);
        fetchSlashCommandsOnStartup();

        ApplicationManager.getApplication().invokeLater(() -> {
            // Release the frontend session transition guard so updateMessages works again.
            // Must come BEFORE updateStatus to ensure the guard is lifted before any
            // subsequent message updates arrive.
            host.callJavaScript("historyLoadComplete");
            host.callJavaScript("updateStatus",
                    JsUtils.escapeJs("toast.newSessionCreatedReady"));
            resetTokenUsage();
        });
    }
}
