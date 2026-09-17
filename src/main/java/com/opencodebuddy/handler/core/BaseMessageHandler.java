package com.opencodebuddy.handler.core;

/**
 * Base class for message handlers.
 * Provides common utility methods.
 */
public abstract class BaseMessageHandler implements MessageHandler {

    protected final HandlerContext context;

    public BaseMessageHandler(HandlerContext context) {
        this.context = context;
    }

    /**
     * Call a JavaScript function on the current window.
     */
    protected void callJavaScript(String functionName, String... args) {
        context.callJavaScript(functionName, args);
    }

    /**
     * Broadcast a JavaScript call to the current window and all other active chat windows across all projects.
     */
    protected void broadcastToAll(String functionName, String... args) {
        context.broadcastToAll(functionName, args);
    }

    /**
     * Broadcast a JavaScript call to the current window and all other active chat windows belonging to the current project.
     */
    protected void broadcastToProject(String functionName, String... args) {
        context.broadcastToProject(functionName, args);
    }

    /**
     * Escape a JavaScript string.
     */
    protected String escapeJs(String str) {
        return context.escapeJs(str);
    }

    /**
     * Execute JavaScript on the EDT (Event Dispatch Thread).
     */
    protected void executeJavaScript(String jsCode) {
        context.executeJavaScriptOnEDT(jsCode);
    }

    /**
     * Check whether the message type matches any of the supported types.
     */
    protected boolean matchesType(String type, String... supportedTypes) {
        for (String supported : supportedTypes) {
            if (supported.equals(type)) {
                return true;
            }
        }
        return false;
    }
}
