package com.opencodebuddy.handler.core;

import com.opencodebuddy.provider.opencode.OpenCodeSDKBridge;
import com.opencodebuddy.session.OpencodeSession;
import com.opencodebuddy.settings.OpenCodeBuddySettingsService;
import com.opencodebuddy.ui.toolwindow.OpencodeBuddyToolWindow;
import com.intellij.openapi.project.Project;
import com.intellij.ui.jcef.JBCefBrowser;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Handler context.
 * Provides all shared resources and callbacks needed by handlers.
 */
public class HandlerContext {

    public static final String DEFAULT_PROVIDER = "opencode";

    private final Project project;
    private final OpenCodeSDKBridge openCodeSDKBridge;
    private final OpenCodeBuddySettingsService settingsService;
    private final JsCallback jsCallback;
    private final BooleanSupplier activeContentSupplier;
    private final Supplier<String> contentTitleSupplier;
    private volatile Runnable contentActivator = () -> { };

    // Mutable state accessed via getters/setters — volatile for thread safety
    private volatile OpencodeSession session;
    private volatile JBCefBrowser browser;
    private volatile String currentModel;
    private volatile String currentProvider = DEFAULT_PROVIDER;
    private volatile boolean disposed = false;

    public interface JsCallback {
        void callJavaScript(String functionName, String... args);
        String escapeJs(String str);

        default void executeJavaScript(String jsCode) {
        }

        /**
         * The tab that owns a canonical session id.
         *
         * <p>Prompts and other per-tab events need the tab, not only the
         * session: the tab is the identity the webview routes by. A host that
         * does not know the mapping returns {@code null} and the caller keeps
         * the session-level behaviour.</p>
         */
        default String getTabIdForSession(String sessionId) {
            return null;
        }
    }

    public HandlerContext(
            Project project,
            OpenCodeSDKBridge openCodeSDKBridge,
            OpenCodeBuddySettingsService settingsService,
            JsCallback jsCallback
    ) {
        this(project, openCodeSDKBridge, settingsService, jsCallback, () -> true, () -> null);
    }

    public HandlerContext(
            Project project,
            OpenCodeSDKBridge openCodeSDKBridge,
            OpenCodeBuddySettingsService settingsService,
            JsCallback jsCallback,
            BooleanSupplier activeContentSupplier,
            Supplier<String> contentTitleSupplier
    ) {
        this.project = project;
        this.openCodeSDKBridge = openCodeSDKBridge;
        this.settingsService = settingsService;
        this.jsCallback = jsCallback;
        this.activeContentSupplier = activeContentSupplier == null ? () -> true : activeContentSupplier;
        this.contentTitleSupplier = contentTitleSupplier == null ? () -> null : contentTitleSupplier;
    }

    // Getters
    public Project getProject() {
        return project;
    }

    public OpenCodeSDKBridge getOpenCodeSDKBridge() {
        return openCodeSDKBridge;
    }

    public OpenCodeBuddySettingsService getSettingsService() {
        return settingsService;
    }

    /**
     * Resolve the normalized effective working directory for the current project —
     * the custom working directory when configured and valid, otherwise the project
     * base path. This is the directory opencode runs in and the key history is
     * stored under, so history readers must use this instead of the raw base path.
     *
     * <p>Null-safe: returns the raw base path when no settings service is wired.
     */
    public String resolveEffectiveWorkingDirectory() {
        String basePath = project != null ? project.getBasePath() : null;
        if (settingsService == null) {
            return basePath;
        }
        return settingsService.getEffectiveWorkingDirectory(basePath);
    }

    private final java.util.Map<String, OpencodeSession> sessionPool = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile java.util.function.Function<String, OpencodeSession> sessionLookup;

    /**
     * The session of the <em>active tab</em>.
     *
     * <p>Window-level actions (the title bar, the status bar, the editor
     * context) legitimately need the session the user is looking at. They must
     * ask for it by this name, so that "the session a command targets" and "the
     * session on screen" can never be confused: everything that routes a
     * command uses {@link #getSession(String)} with an explicit tab or session
     * id instead.</p>
     */
    public OpencodeSession getActiveTabSession() {
        return session;
    }

    /**
     * @deprecated use {@link #getActiveTabSession()}; a bare "current session"
     *     accessor is exactly the ambiguity the multi-tab cutover removes.
     */
    @Deprecated
    public OpencodeSession getSession() {
        return getActiveTabSession();
    }

    /**
     * The tab that owns a canonical session id, or {@code null} when the host
     * cannot resolve it.
     */
    public String getTabIdForSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return jsCallback.getTabIdForSession(sessionId);
    }

    /**
     * Resolve a session by tab id or canonical session id.
     */
    public OpencodeSession getSession(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            if (sessionLookup != null) {
                OpencodeSession found = sessionLookup.apply(sessionId);
                if (found != null) {
                    return found;
                }
            }
            OpencodeSession pooled = sessionPool.get(sessionId);
            if (pooled != null) {
                return pooled;
            }
        }
        return null;
    }

    public void registerSession(String sessionId, OpencodeSession session) {
        if (sessionId != null && session != null) {
            sessionPool.put(sessionId, session);
        }
    }

    public void removeSession(String sessionId) {
        if (sessionId != null) {
            sessionPool.remove(sessionId);
        }
    }

    public void setSessionLookup(java.util.function.Function<String, OpencodeSession> lookup) {
        this.sessionLookup = lookup;
    }

    private volatile java.util.function.Function<String, OpencodeSession> sessionCreator;

    public void setSessionCreator(java.util.function.Function<String, OpencodeSession> creator) {
        this.sessionCreator = creator;
    }

    public OpencodeSession createSession(String tabOrSessionId) {
        if (sessionCreator != null) {
            return sessionCreator.apply(tabOrSessionId);
        }
        return null;
    }

    public JBCefBrowser getBrowser() {
        return browser;
    }

    public String getCurrentModel() {
        return currentModel;
    }

    public String getCurrentProvider() {
        return currentProvider;
    }

    public boolean isDisposed() {
        return disposed;
    }

    public boolean isActiveContent() {
        try {
            return activeContentSupplier.getAsBoolean();
        } catch (RuntimeException e) {
            return true;
        }
    }

    public String getContentTitle() {
        try {
            return contentTitleSupplier.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    public void activateContent() {
        if (!disposed) {
            contentActivator.run();
        }
    }

    // Setters
    public void setSession(OpencodeSession session) {
        this.session = session;
    }

    public void setBrowser(JBCefBrowser browser) {
        this.browser = browser;
    }

    public void setCurrentModel(String currentModel) {
        this.currentModel = currentModel;
    }

    public void setCurrentProvider(String currentProvider) {
        this.currentProvider = currentProvider;
    }

    public void setDisposed(boolean disposed) {
        this.disposed = disposed;
    }

    public void setContentActivator(Runnable contentActivator) {
        this.contentActivator = contentActivator == null ? () -> { } : contentActivator;
    }

    // JavaScript callback proxy methods
    public void callJavaScript(String functionName, String... args) {
        jsCallback.callJavaScript(functionName, args);
    }

    /**
     * Broadcast JavaScript call to the current window and all other active chat windows across all projects.
     */
    public void broadcastToAll(String functionName, String... args) {
        callJavaScript(functionName, args);
        OpencodeBuddyToolWindow.broadcastToAllChatWindows(this, functionName, args);
    }

    /**
     * Broadcast JavaScript call to the current window and all other active chat windows belonging to the current project.
     */
    public void broadcastToProject(String functionName, String... args) {
        callJavaScript(functionName, args);
        OpencodeBuddyToolWindow.broadcastToProjectChatWindows(this.project, this, functionName, args);
    }

    public String escapeJs(String str) {
        return jsCallback.escapeJs(str);
    }

    /**
     * Execute JavaScript through the window's ordered webview event queue
     * (which marshals to the EDT and batches with callback events).
     */
    public void executeJavaScriptQueued(String jsCode) {
        if (this.disposed || this.jsCallback == null) {
            return;
        }
        this.jsCallback.executeJavaScript(jsCode);
    }

    /**
     * Execute JavaScript on the EDT via the serialized webview event queue.
     * Maintained for compatibility with existing handler callers.
     */
    public void executeJavaScriptOnEDT(String jsCode) {
        executeJavaScriptQueued(jsCode);
    }
}
