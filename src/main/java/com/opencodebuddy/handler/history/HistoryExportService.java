package com.opencodebuddy.handler.history;

import com.opencodebuddy.handler.core.HandlerContext;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Service for exporting session data. opencode-only: messages are loaded
 * through the daemon ({@code opencode.listMessages}) and converted into the
 * Claude-compatible message shape.
 */
class HistoryExportService {

    private static final Logger LOG = Logger.getInstance(HistoryExportService.class);

    private final HandlerContext context;
    private final Gson gson = new Gson();

    HistoryExportService(HandlerContext context) {
        this.context = context;
    }

    /**
     * Export session data.
     * Reads all messages of the session and returns them to the frontend.
     */
    void handleExportSession(String content, String currentProvider) {
        CompletableFuture.runAsync(() -> {
            LOG.info("[HistoryHandler] ========== 开始导出会话 ==========");

            try {
                // Parse JSON from frontend to extract sessionId and title
                JsonObject exportRequest = gson.fromJson(content, JsonObject.class);
                String sessionId = exportRequest.get("sessionId").getAsString();
                String title = exportRequest.get("title").getAsString();
                String provider = currentProvider != null && !currentProvider.trim().isEmpty()
                        ? currentProvider : com.opencodebuddy.handler.core.HandlerContext.DEFAULT_PROVIDER;

                String projectPath = context.resolveEffectiveWorkingDirectory();
                if (projectPath == null) {
                    LOG.warn("[HistoryHandler] Project base path is null");
                    return;
                }
                LOG.info("[HistoryHandler] SessionId: " + sessionId);
                LOG.info("[HistoryHandler] Title: " + title);
                LOG.info("[HistoryHandler] ProjectPath: " + projectPath);

                JsonElement messagesElement = loadMessagesForExport(sessionId, projectPath);

                // Wrap messages into an object containing sessionId and title
                JsonObject exportData = new JsonObject();
                exportData.addProperty("sessionId", sessionId);
                exportData.addProperty("title", title);
                exportData.addProperty("provider", provider);
                exportData.add("messages", messagesElement);

                String wrappedJson = gson.toJson(exportData);

                LOG.info("[HistoryHandler] 读取到会话消息，准备注入到前端");

                // Use Base64 encoding to avoid JavaScript string escaping issues
                String base64Json = Base64.getEncoder().encodeToString(
                        wrappedJson.getBytes(StandardCharsets.UTF_8));

                ApplicationManager.getApplication().invokeLater(() -> {
                    String jsCode = "console.log('[Backend->Frontend] Starting to inject export data');" +
                                            "if (window.onExportSessionData) { " +
                                            "  try { " +
                                            "    var base64Str = '" + base64Json + "'; " +
                                            "    var binaryStr = atob(base64Str); " +
                                            "    var bytes = new Uint8Array(binaryStr.length); " +
                                            "    for (var i = 0; i < binaryStr.length; i++) { bytes[i] = binaryStr.charCodeAt(i); } " +
                                            "    var jsonStr = new TextDecoder('utf-8').decode(bytes); " +
                                            "    window.onExportSessionData(jsonStr); " +
                                            "    console.log('[Backend->Frontend] Export data injected successfully'); " +
                                            "  } catch(e) { " +
                                            "    console.error('[Backend->Frontend] Failed to inject export data:', e); " +
                                            "  } " +
                                            "} else { " +
                                            "  console.error('[Backend->Frontend] onExportSessionData not available!'); " +
                                            "}";

                    context.executeJavaScriptOnEDT(jsCode);
                });

                LOG.info("[HistoryHandler] ========== 导出会话完成 ==========");

            } catch (Exception e) {
                LOG.error("[HistoryHandler] 导出会话失败: " + e.getMessage(), e);

                ApplicationManager.getApplication().invokeLater(() -> {
                    String jsCode = "if (window.addToast) { " +
                                            "  window.addToast('导出失败: " + context.escapeJs(e.getMessage() != null ? e.getMessage() : "未知错误") + "', 'error'); " +
                                            "}";
                    context.executeJavaScriptOnEDT(jsCode);
                });
            }
        });
    }

    /**
     * Load the session transcript via the daemon and convert it into the
     * Claude-compatible message array.
     */
    private JsonElement loadMessagesForExport(String sessionId, String projectPath) throws Exception {
        List<JsonObject> messages = new com.opencodebuddy.session.SessionProviderRouter(
                context.getOpenCodeSDKBridge())
                .getSessionMessages(
                        com.opencodebuddy.handler.core.HandlerContext.DEFAULT_PROVIDER, sessionId, projectPath);
        JsonArray array = new JsonArray();
        for (JsonObject message : messages) {
            if (message != null) {
                array.add(message);
            }
        }
        return array;
    }
}
