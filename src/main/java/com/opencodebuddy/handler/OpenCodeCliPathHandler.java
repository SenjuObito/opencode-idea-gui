package com.opencodebuddy.handler;

import com.opencodebuddy.handler.core.HandlerContext;
import com.opencodebuddy.settings.OpenCodeBuddySettingsService;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.io.File;
import java.util.concurrent.CompletableFuture;

/**
 * Handles persistence of a user-provided opencode CLI executable path.
 *
 * <p>Persisted in {@link OpenCodeBuddySettingsService} (global config.json) as the source of truth.
 */
public class OpenCodeCliPathHandler {

    private static final Logger LOG = Logger.getInstance(OpenCodeCliPathHandler.class);

    private final HandlerContext context;
    private final OpenCodeBuddySettingsService settingsService;
    private final Gson gson = new Gson();

    public OpenCodeCliPathHandler(HandlerContext context) {
        this(context, new OpenCodeBuddySettingsService());
    }

    public OpenCodeCliPathHandler(HandlerContext context, OpenCodeBuddySettingsService settingsService) {
        this.context = context;
        this.settingsService = settingsService != null ? settingsService : new OpenCodeBuddySettingsService();
    }

    /**
     * Get the configured opencode CLI path (empty string when unset).
     */
    public void handleGetOpencodeCliPath() {
        CompletableFuture.runAsync(() -> {
            try {
                String saved = settingsService.getOpencodeCliPath();
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
     * existing file (when non-empty), then writes to config.json.
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

        final String rawPath = (parsedPath != null) ? parsedPath.trim() : "";
        final String resolvedPath = rawPath.isEmpty() ? "" : resolveValidCliPath(rawPath);

        if (!rawPath.isEmpty() && resolvedPath == null) {
            ApplicationManager.getApplication().invokeLater(() ->
                context.callJavaScript("window.showError", context.escapeJs("opencode CLI path does not exist: " + rawPath))
            );
            return;
        }

        final String path = resolvedPath != null ? resolvedPath : "";

        try {
            // Persist to global config.json
            settingsService.setOpencodeCliPath(path.isEmpty() ? null : path);

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

    /**
     * Resolves and validates an executable path across platforms.
     * Expands home directory (~) and probes Windows executable extensions (.exe, .cmd, .bat, .ps1).
     *
     * @param input the raw path string provided by user
     * @return the absolute canonical path if valid and exists, or null if invalid
     */
    static String resolveValidCliPath(String input) {
        if (input == null || input.trim().isEmpty()) {
            return null;
        }

        String cleaned = input.trim();
        // Remove surrounding quotes if any
        if ((cleaned.startsWith("\"") && cleaned.endsWith("\"")) ||
            (cleaned.startsWith("'") && cleaned.endsWith("'"))) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }

        // Expand home directory ~
        if (cleaned.startsWith("~" + File.separator) || cleaned.startsWith("~/") || cleaned.startsWith("~\\")) {
            String userHome = com.opencodebuddy.util.PlatformUtils.getHomeDirectory();
            if (userHome != null && !userHome.isEmpty()) {
                cleaned = new File(userHome, cleaned.substring(2)).getAbsolutePath();
            }
        }

        File target = new File(cleaned);
        if (target.exists() && target.isFile()) {
            return target.getAbsolutePath();
        }

        // Windows executable extension fallback
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("win")) {
            String[] winExtensions = {".exe", ".cmd", ".bat", ".ps1"};
            for (String ext : winExtensions) {
                File candidate = new File(cleaned + ext);
                if (candidate.exists() && candidate.isFile()) {
                    return candidate.getAbsolutePath();
                }
            }
        }

        return null;
    }
}
