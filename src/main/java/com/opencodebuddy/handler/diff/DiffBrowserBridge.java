package com.opencodebuddy.handler.diff;

import com.opencodebuddy.handler.core.HandlerContext;
import com.opencodebuddy.util.JsUtils;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

/**
 * Browser communication bridge for diff operations.
 * Encapsulates Java to WebView JavaScript calls.
 */
public class DiffBrowserBridge {

    private static final Logger LOG = Logger.getInstance(DiffBrowserBridge.class);
    private final HandlerContext context;
    private final Gson gson;

    public DiffBrowserBridge(HandlerContext context, Gson gson) {
        this.context = context;
        this.gson = gson;
    }

    /**
     * Show an error toast in the WebView.
     */
    public void showErrorToast(String message) {
        context.callJavaScript("window.addToast", JsUtils.escapeJs(message), "error");
    }

    /**
     * Notify the frontend to remove a file from the edits list.
     */
    public void sendRemoveFileFromEdits(String filePath) {
        JsonObject payload = new JsonObject();
        payload.addProperty("filePath", filePath);
        context.callJavaScript("window.handleRemoveFileFromEdits", JsUtils.escapeJs(gson.toJson(payload)));
    }

    /**
     * Send diff result to the frontend.
     */
    public void sendDiffResult(String filePath, String action, String content, String error) {
        JsonObject payload = new JsonObject();
        payload.addProperty("filePath", filePath);
        payload.addProperty("action", action);
        if (content != null) {
            payload.addProperty("content", content);
        }
        if (error != null) {
            payload.addProperty("error", error);
        }

        context.callJavaScript("window.handleDiffResult", JsUtils.escapeJs(gson.toJson(payload)));
        LOG.info("Diff result sent to frontend: " + action + " for " + filePath);
    }
}
