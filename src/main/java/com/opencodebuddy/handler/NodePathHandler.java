package com.opencodebuddy.handler;

import com.opencodebuddy.handler.core.HandlerContext;
import com.opencodebuddy.bridge.NodeDetector;
import com.opencodebuddy.model.NodeDetectionResult;
import com.opencodebuddy.settings.OpenCodeBuddySettingsService;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.util.concurrent.CompletableFuture;

/**
 * Handles Node.js path detection, verification, and persistence.
 * Persisted in {@link OpenCodeBuddySettingsService} (~/.opencodebuddy/config.json).
 */
public class NodePathHandler {

    private static final Logger LOG = Logger.getInstance(NodePathHandler.class);

    private final HandlerContext context;
    private final OpenCodeBuddySettingsService settingsService;
    private final Gson gson = new Gson();

    public NodePathHandler(HandlerContext context) {
        this(context, new OpenCodeBuddySettingsService());
    }

    public NodePathHandler(HandlerContext context, OpenCodeBuddySettingsService settingsService) {
        this.context = context;
        this.settingsService = settingsService != null ? settingsService : new OpenCodeBuddySettingsService();
    }

    /**
     * Get Node.js path and version information.
     * Runs detection/verification in a background thread to avoid blocking the CEF IO thread.
     */
    public void handleGetNodePath() {
        CompletableFuture.runAsync(() -> {
            try {
                String saved = settingsService.getNodePath();
                String pathToSend = "";
                String versionToSend = null;

                if (saved != null && !saved.trim().isEmpty()) {
                    String trimmedPath = saved.trim();
                    NodeDetectionResult result = NodeDetector.getInstance().verifyAndCacheNodePath(trimmedPath);
                    if (result != null && result.isFound()) {
                        pathToSend = trimmedPath;
                        versionToSend = result.getNodeVersion();
                    } else {
                        // Saved path is invalid, clear it and trigger re-detection
                        LOG.warn("[NodePathHandler] Saved Node.js path is invalid: " + trimmedPath
                            + ", clearing and triggering re-detection");
                        try {
                            settingsService.setNodePath(null);
                        } catch (Exception ignored) {
                        }
                        NodeDetector.getInstance().setNodeExecutable(null);

                        NodeDetectionResult detected = NodeDetector.getInstance().detectNodeWithDetails();
                        if (detected != null && detected.isFound() && detected.getNodePath() != null) {
                            pathToSend = detected.getNodePath();
                            versionToSend = detected.getNodeVersion();
                            NodeDetector.getInstance().verifyAndCacheNodePath(pathToSend);
                            NodeDetector.getInstance().setNodeExecutable(pathToSend);
                        }
                    }
                } else {
                    NodeDetectionResult detected = NodeDetector.getInstance().detectNodeWithDetails();
                    if (detected != null && detected.isFound() && detected.getNodePath() != null) {
                        pathToSend = detected.getNodePath();
                        versionToSend = detected.getNodeVersion();
                        NodeDetector.getInstance().verifyAndCacheNodePath(pathToSend);
                        NodeDetector.getInstance().setNodeExecutable(pathToSend);
                    }
                }

                final String finalPath = pathToSend;
                final String finalVersion = versionToSend;

                ApplicationManager.getApplication().invokeLater(() -> {
                    JsonObject response = new JsonObject();
                    response.addProperty("path", finalPath);
                    response.addProperty("version", finalVersion);
                    response.addProperty("minVersion", NodeDetector.MIN_NODE_MAJOR_VERSION);
                    context.callJavaScript("window.updateNodePath", context.escapeJs(gson.toJson(response)));
                });
            } catch (Exception e) {
                LOG.error("[NodePathHandler] Failed to get Node.js path: " + e.getMessage(), e);
                ApplicationManager.getApplication().invokeLater(() ->
                    context.callJavaScript("window.showError", context.escapeJs("获取 Node.js 路径失败: " + e.getMessage()))
                );
            }
        }, AppExecutorUtil.getAppExecutorService()).exceptionally(ex -> {
            LOG.error("[NodePathHandler] Unexpected error in handleGetNodePath: " + ex.getMessage(), ex);
            return null;
        });
    }

    /**
     * Set Node.js path.
     * JSON parsing runs on the CEF IO thread (safe, no I/O), while verification/detection
     * runs in a background thread to avoid blocking the CEF IO thread.
     */
    public void handleSetNodePath(String content) {
        LOG.debug("[NodePathHandler] ========== handleSetNodePath START ==========");
        LOG.debug("[NodePathHandler] Received content: " + content);

        String parsedPath = null;
        try {
            JsonObject json = gson.fromJson(content, JsonObject.class);
            if (json != null && json.has("path") && !json.get("path").isJsonNull()) {
                parsedPath = json.get("path").getAsString();
            }
        } catch (Exception e) {
            LOG.error("[NodePathHandler] Failed to parse set_node_path content: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() ->
                context.callJavaScript("window.showError", context.escapeJs("保存 Node.js 路径失败: " + e.getMessage()))
            );
            return;
        }
        final String pathArg = (parsedPath != null) ? parsedPath.trim() : null;

        CompletableFuture.runAsync(() -> {
            try {
                String finalPath = "";
                String versionToSend = null;
                boolean verifySuccess = false;
                String failureMsg = null;

                if (pathArg == null || pathArg.isEmpty()) {
                    try {
                        settingsService.setNodePath(null);
                    } catch (Exception e) {
                        LOG.warn("[NodePathHandler] Failed to clear nodePath in config.json: " + e.getMessage());
                    }
                    NodeDetector.getInstance().setNodeExecutable(null);
                    LOG.info("[NodePathHandler] Cleared manual Node.js path from settings");

                    NodeDetectionResult detected = NodeDetector.getInstance().detectNodeWithDetails();
                    if (detected != null && detected.isFound() && detected.getNodePath() != null) {
                        finalPath = detected.getNodePath();
                        versionToSend = detected.getNodeVersion();
                        NodeDetector.getInstance().verifyAndCacheNodePath(finalPath);
                        NodeDetector.getInstance().setNodeExecutable(finalPath);
                        verifySuccess = true;
                    } else {
                        failureMsg = "已清空自定义路径，但无法自动检测到 Node.js，请手动配置路径";
                    }
                } else {
                    String resolvedPath = OpenCodeCliPathHandler.resolveValidCliPath(pathArg);
                    String candidatePath = resolvedPath != null ? resolvedPath : pathArg;

                    // Verify before saving to avoid caching invalid path
                    NodeDetectionResult result = NodeDetector.getInstance().verifyAndCacheNodePath(candidatePath);
                    if (result != null && result.isFound()) {
                        try {
                            settingsService.setNodePath(candidatePath);
                        } catch (Exception e) {
                            LOG.warn("[NodePathHandler] Failed to save nodePath in config.json: " + e.getMessage());
                        }
                        NodeDetector.getInstance().setNodeExecutable(candidatePath);
                        finalPath = candidatePath;
                        versionToSend = result.getNodeVersion();
                        verifySuccess = true;
                        LOG.info("[NodePathHandler] Saved manual Node.js path: " + candidatePath);
                    } else {
                        finalPath = "";
                        failureMsg = result != null ? result.getErrorMessage() : "无法验证指定的 Node.js 路径";
                        LOG.warn("[NodePathHandler] Node.js path verification failed: " + candidatePath + " - " + failureMsg);
                    }
                }

                final boolean successFlag = verifySuccess;
                final String failureMsgFinal = failureMsg;
                final String finalPathToSend = finalPath;
                final String finalVersionToSend = versionToSend;

                ApplicationManager.getApplication().invokeLater(() -> {
                    JsonObject response = new JsonObject();
                    response.addProperty("path", finalPathToSend);
                    response.addProperty("version", finalVersionToSend);
                    response.addProperty("minVersion", NodeDetector.MIN_NODE_MAJOR_VERSION);

                    if (successFlag) {
                        context.broadcastToAll("window.updateNodePath", context.escapeJs(gson.toJson(response)));
                        context.callJavaScript("window.showSwitchSuccess", context.escapeJs("Node.js 路径已保存并生效,无需重启IDE"));
                        context.callJavaScript("window.checkNodeEnvironment");
                    } else {
                        context.callJavaScript("window.updateNodePath", context.escapeJs(gson.toJson(response)));
                        String msg = failureMsgFinal != null ? failureMsgFinal : "无法验证指定的 Node.js 路径";
                        context.callJavaScript("window.showError", context.escapeJs("保存的 Node.js 路径无效: " + msg));
                    }
                });
            } catch (Exception e) {
                LOG.error("[NodePathHandler] Failed to set Node.js path: " + e.getMessage(), e);
                ApplicationManager.getApplication().invokeLater(() ->
                    context.callJavaScript("window.showError", context.escapeJs("保存 Node.js 路径失败: " + e.getMessage()))
                );
            }
        }, AppExecutorUtil.getAppExecutorService()).exceptionally(ex -> {
            LOG.error("[NodePathHandler] Unexpected error in handleSetNodePath: " + ex.getMessage(), ex);
            return null;
        });

        LOG.debug("[NodePathHandler] ========== handleSetNodePath END (async dispatched) ==========");
    }
}
