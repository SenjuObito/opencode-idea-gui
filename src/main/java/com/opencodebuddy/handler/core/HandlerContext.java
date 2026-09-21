package com.opencodebuddy.handler.core;

import com.opencodebuddy.provider.opencode.OpenCodeSDKBridge;
import com.opencodebuddy.session.OpencodeSession;
import com.opencodebuddy.settings.OpenCodeBuddySettingsService;
import com.opencodebuddy.ui.toolwindow.OpencodeBuddyToolWindow;
import com.intellij.openapi.application.ApplicationManager;
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

    /**
     * JavaScript callback interface.
     */
    public interface JsCallback {
        void callJavaScript(String functionName, String... args);
        String escapeJs(String str);
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

    public OpencodeSession getSession() {
        return session;
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
     * Execute JavaScript on the EDT (Event Dispatch Thread).
     */
    public void executeJavaScriptOnEDT(String jsCode) {
        JBCefBrowser targetBrowser = this.browser;
        if (targetBrowser == null || this.disposed) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            if (this.disposed || this.browser != targetBrowser) {
                return;
            }
            try {
                org.cef.browser.CefBrowser cefBrowser = targetBrowser.getCefBrowser();
                cefBrowser.executeJavaScript(jsCode, cefBrowser.getURL(), 0);
            } catch (Exception | LinkageError ignored) {
                // The webview may be disposed between the generation check and execution.
            }
        });
    }
}
