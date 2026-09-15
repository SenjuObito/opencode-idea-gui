package com.opencodebuddy.handler.history;

import com.opencodebuddy.handler.core.HandlerContext;
import com.opencodebuddy.provider.opencode.OpenCodeSDKBridge;
import com.opencodebuddy.util.JsUtils;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

import java.util.concurrent.CompletableFuture;

/**
 * Service for managing session metadata: favorites (in session.metadata) and titles.
 */
class HistoryMetadataService {

    private static final Logger LOG = Logger.getInstance(HistoryMetadataService.class);
    private static final Gson GSON = new Gson();

    private final HandlerContext context;
    private final HistoryLoadService historyLoadService;

    HistoryMetadataService(HandlerContext context, HistoryLoadService historyLoadService) {
        this.context = context;
        this.historyLoadService = historyLoadService;
    }

    /**
     * Toggle favorite status in OpenCode native session.metadata.
     */
    void handleToggleFavorite(String sessionId) {
        CompletableFuture.runAsync(() -> {
            try {
                LOG.info("[HistoryHandler] ========== 切换收藏状态 ==========");
                LOG.info("[HistoryHandler] SessionId: " + sessionId);

                String projectPath = context.resolveEffectiveWorkingDirectory();
                OpenCodeSDKBridge bridge = context.getOpenCodeSDKBridge();
                if (bridge != null) {
                    JsonElement result = bridge.toggleFavorite(sessionId, null, projectPath).join();
                    LOG.info("[HistoryHandler] 收藏状态切换结果: " + result);
                }

            } catch (Exception e) {
                LOG.error("[HistoryHandler] 切换收藏状态失败: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Update session title on OpenCode server.
     * Mirrors the vscode host (HistoryHandler.handleUpdateTitle): incomplete payloads are
     * ignored silently, and a successful write is echoed back to the webview before the
     * history list is refreshed.
     */
    void handleUpdateTitle(String content, String provider) {
        CompletableFuture.runAsync(() -> {
            try {
                LOG.info("[HistoryHandler] ========== 更新会话标题 ==========");

                // Parse JSON from frontend to extract sessionId and title/customTitle
                JsonObject request = GSON.fromJson(content, JsonObject.class);
                String sessionId = request != null && request.has("sessionId") && !request.get("sessionId").isJsonNull()
                        ? request.get("sessionId").getAsString() : "";
                String title = request != null && request.has("customTitle") && !request.get("customTitle").isJsonNull()
                        ? request.get("customTitle").getAsString()
                        : (request != null && request.has("title") && !request.get("title").isJsonNull()
                                ? request.get("title").getAsString() : "");

                if (sessionId.isEmpty() || title.isEmpty()) {
                    LOG.warn("[HistoryHandler] 更新标题参数不完整，已忽略: sessionId=" + sessionId
                            + ", title=" + title);
                    return;
                }

                LOG.info("[HistoryHandler] SessionId: " + sessionId);
                LOG.info("[HistoryHandler] Title: " + title);

                String projectPath = context.resolveEffectiveWorkingDirectory();
                OpenCodeSDKBridge bridge = context.getOpenCodeSDKBridge();
                if (bridge == null) {
                    LOG.warn("[HistoryHandler] OpenCodeSDKBridge is null，跳过标题更新");
                    return;
                }

                bridge.updateSessionTitle(sessionId, title, projectPath).join();
                LOG.info("[HistoryHandler] 标题更新成功: sessionId=" + sessionId);

                // Echo the persisted title back so the visible header switches over, then
                // reload the history payload so the row reflects the server-side value.
                context.callJavaScript("updateSessionTitle", JsUtils.escapeJs(sessionId), JsUtils.escapeJs(title));
                historyLoadService.handleLoadHistoryData(provider);

            } catch (Exception e) {
                LOG.error("[HistoryHandler] 更新标题失败: " + e.getMessage(), e);
                ApplicationManager.getApplication().invokeLater(() -> {
                    String jsCode = "if (window.addToast) { " +
                                            "  window.addToast('更新标题失败: " + context.escapeJs(e.getMessage() != null ? e.getMessage() : "未知错误") + "', 'error'); " +
                                            "}";
                    context.executeJavaScriptOnEDT(jsCode);
                });
            }
        });
    }

    /**
     * Delete an orphaned custom title entry (no-op with native session API).
     */
    void handleDeleteTitle(String sessionId) {
        LOG.debug("[HistoryHandler] handleDeleteTitle called for sessionId: " + sessionId);
    }
}
