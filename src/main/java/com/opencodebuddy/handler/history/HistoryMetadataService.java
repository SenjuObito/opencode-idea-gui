package com.opencodebuddy.handler.history;

import com.opencodebuddy.handler.core.HandlerContext;
import com.opencodebuddy.provider.opencode.OpenCodeSDKBridge;
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

    HistoryMetadataService(HandlerContext context) {
        this.context = context;
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
     */
    void handleUpdateTitle(String content) {
        CompletableFuture.runAsync(() -> {
            try {
                LOG.info("[HistoryHandler] ========== 更新会话标题 ==========");

                // Parse JSON from frontend to extract sessionId and title/customTitle
                JsonObject request = GSON.fromJson(content, JsonObject.class);
                String sessionId = request.get("sessionId").getAsString();
                String title = request.has("customTitle") && !request.get("customTitle").isJsonNull()
                        ? request.get("customTitle").getAsString()
                        : (request.has("title") && !request.get("title").isJsonNull() ? request.get("title").getAsString() : "");

                LOG.info("[HistoryHandler] SessionId: " + sessionId);
                LOG.info("[HistoryHandler] Title: " + title);

                String projectPath = context.resolveEffectiveWorkingDirectory();
                OpenCodeSDKBridge bridge = context.getOpenCodeSDKBridge();
                if (bridge != null) {
                    bridge.updateSessionTitle(sessionId, title, projectPath).join();
                    LOG.info("[HistoryHandler] 标题更新成功: sessionId=" + sessionId);
                }

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
