package com.opencodebuddy.handler;

import com.opencodebuddy.handler.core.HandlerContext;
import com.opencodebuddy.provider.common.DaemonBridge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.io.File;
import java.util.concurrent.CompletableFuture;

/**
 * Handles persistence of a user-provided opencode CLI executable path.
 *
 * <p>When set, the daemon injects {@code OPENCODE_BIN} into the
 * {@code opencode serve} environment so a non-PATH binary is honored.
 * Persisted in {@link PropertiesComponent} under
 * {@link DaemonBridge#OPENCODE_CLI_PATH_PROPERTY_KEY}; mirrors {@link NodePathHandler}.
 */
public class OpenCodeCliPathHandler {

    private static final Logger LOG = Logger.getInstance(OpenCodeCliPathHandler.class);

    private final HandlerContext context;
    private final Gson gson = new Gson();

    public OpenCodeCliPathHandler(HandlerContext context) {
        this.context = context;
    }

    /**
     * Get the configured opencode CLI path (empty string when unset).
     */
    public void handleGetOpencodeCliPath() {
        CompletableFuture.runAsync(() -> {
            try {
                String saved = PropertiesComponent.getInstance()
                        .getValue(DaemonBridge.OPENCODE_CLI_PATH_PROPERTY_KEY);
                String pathToSend = (saved != null) ? saved.trim() : "";

                ApplicationManager.getApplication().invokeLater(() -> {
                    JsonObject response = new JsonObject();
                    response.addProperty("path", pathToSend);
                    context.callJavaScript("window.updateOpencodeCliPath", context.escapeJs(gson.toJson(response)));
                });
            } catch (Exception e) {
                LOG.error("[OpenCodeCliPathHandler] Failed to get opencode CLI path: " + e.getMessage(), e);
                ApplicationManager.getApplication().invokeLater(() ->
                    context.callJavaScript("window.showError", context.escapeJs("Failed to load opencode CLI path: " + e.getMessage()))
                );
            }
        }, AppExecutorUtil.getAppExecutorService()).exceptionally(ex -> {
            LOG.error("[OpenCodeCliPathHandler] Unexpected error in handleGetOpencodeCliPath: " + ex.getMessage(), ex);
            return null;
        });
    }

    /**
     * Persist a custom opencode CLI path. Validates that the path points at an
     * existing file (when non-empty), then stops the daemon so the next request
     * picks up the new {@code OPENCODE_BIN} env var.
     */
    public void handleSetOpencodeCliPath(String content) {
        String parsedPath = null;
        try {
            JsonObject json = gson.fromJson(content, JsonObject.class);
            if (json != null && json.has("path") && !json.get("path").isJsonNull()) {
                parsedPath = json.get("path").getAsString();
            }
        } catch (Exception e) {
            LOG.error("[OpenCodeCliPathHandler] Failed to parse set_opencode_cli_path content: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() ->
                context.callJavaScript("window.showError", context.escapeJs("Failed to save opencode CLI path: " + e.getMessage()))
            );
            return;
        }

        final String path = (parsedPath != null) ? parsedPath.trim() : "";

        if (!path.isEmpty() && !new File(path).exists()) {
            ApplicationManager.getApplication().invokeLater(() ->
                context.callJavaScript("window.showError", context.escapeJs("opencode CLI path does not exist: " + path))
            );
            return;
        }

        try {
            PropertiesComponent props = PropertiesComponent.getInstance();
            if (path.isEmpty()) {
                props.unsetValue(DaemonBridge.OPENCODE_CLI_PATH_PROPERTY_KEY);
            } else {
                props.setValue(DaemonBridge.OPENCODE_CLI_PATH_PROPERTY_KEY, path);
            }
            LOG.info("[OpenCodeCliPathHandler] Saved opencode CLI path: " + (path.isEmpty() ? "(cleared)" : path));
            ApplicationManager.getApplication().invokeLater(() -> {
                JsonObject response = new JsonObject();
                response.addProperty("path", path);
                context.broadcastToAll("window.updateOpencodeCliPath", context.escapeJs(gson.toJson(response)));
                context.callJavaScript("window.showSuccess", context.escapeJs("opencode CLI path saved"));
            });
        } catch (Exception e) {
            LOG.error("[OpenCodeCliPathHandler] Failed to save opencode CLI path: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() ->
                context.callJavaScript("window.showError", context.escapeJs("Failed to save opencode CLI path: " + e.getMessage()))
            );
        }
    }
}
