package com.opencodebuddy.handler.history;

import com.opencodebuddy.handler.NodeJsServiceCaller;
import com.opencodebuddy.handler.core.HandlerContext;

import com.opencodebuddy.cache.OpenCodeSessionIndex;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Service for loading and enhancing history data.
 * opencode-only: sessions come from the plugin-side {@link OpenCodeSessionIndex};
 * entries are enriched with favorites and custom titles before injection.
 */
class HistoryLoadService {

    private static final Logger LOG = Logger.getInstance(HistoryLoadService.class);

    private final HandlerContext context;
    private final NodeJsServiceCaller nodeJsServiceCaller;

    HistoryLoadService(HandlerContext context, NodeJsServiceCaller nodeJsServiceCaller) {
        this.context = context;
        this.nodeJsServiceCaller = nodeJsServiceCaller;
    }

    /**
     * Load and inject history data into the frontend (including favorite info).
     */
    void handleLoadHistoryData(String provider) {
        CompletableFuture.runAsync(() -> {
            LOG.info("[HistoryHandler] ========== 开始加载历史数据 ========== provider=" + provider);

            try {
                String projectPath = context.resolveEffectiveWorkingDirectory();
                if (projectPath == null) {
                    LOG.warn("[HistoryHandler] Project base path is null");
                    return;
                }

                String historyJson = buildOpenCodeHistoryJson(projectPath);

                // Load favorite data and merge into history data
                String enhancedJson = enhanceHistoryWithFavorites(historyJson, provider);
                LOG.info("[HistoryHandler] enhanceHistoryWithFavorites 完成，JSON 长度: " + enhancedJson.length());

                // Load custom titles and merge into history data
                String finalJson = enhanceHistoryWithTitles(enhancedJson);
                LOG.info("[HistoryHandler] enhanceHistoryWithTitles 完成，JSON 长度: " + finalJson.length());

                // Use Base64 encoding to avoid JavaScript string escaping issues
                String base64Json = Base64.getEncoder().encodeToString(finalJson.getBytes(StandardCharsets.UTF_8));
                LOG.info("[HistoryHandler] Base64 编码完成，长度: " + base64Json.length());

                ApplicationManager.getApplication().invokeLater(() -> {
                    String jsCode = "console.log('[Backend->Frontend] Starting to inject history data');" +
                                            "if (window.setHistoryData) { " +
                                            "  try { " +
                                            "    var base64Str = '" + base64Json + "'; " +
                                            "    console.log('[Backend->Frontend] Base64 length:', base64Str.length); " +
                                            // Use TextDecoder to properly decode UTF-8 Base64 strings (avoid garbled non-ASCII characters)
                                            "    var binaryStr = atob(base64Str); " +
                                            "    var bytes = new Uint8Array(binaryStr.length); " +
                                            "    for (var i = 0; i < binaryStr.length; i++) { bytes[i] = binaryStr.charCodeAt(i); } " +
                                            "    var jsonStr = new TextDecoder('utf-8').decode(bytes); " +
                                            "    console.log('[Backend->Frontend] Decoded JSON length:', jsonStr.length); " +
                                            "    var data = JSON.parse(jsonStr); " +
                                            "    console.log('[Backend->Frontend] Parsed data, sessions:', data.sessions ? data.sessions.length : 0); " +
                                            "    window.setHistoryData(data); " +
                                            "    console.log('[Backend->Frontend] setHistoryData called successfully'); " +
                                            "  } catch(e) { " +
                                            "    console.error('[Backend->Frontend] Failed to parse/set history data:', e); " +
                                            "    window.setHistoryData({ success: false, error: '解析历史数据失败: ' + e.message }); " +
                                            "  } " +
                                            "} else { " +
                                            "  console.error('[Backend->Frontend] setHistoryData not available!'); " +
                                            "}";

                    context.executeJavaScriptOnEDT(jsCode);
                    LOG.info("[HistoryHandler] JavaScript 代码已注入");
                });

            } catch (Exception e) {
                LOG.error("[HistoryHandler] 加载历史数据失败: " + e.getMessage(), e);

                ApplicationManager.getApplication().invokeLater(() -> {
                    String errorMsg = context.escapeJs(e.getMessage() != null ? e.getMessage() : "未知错误");
                    String jsCode = "if (window.setHistoryData) { " +
                                            "  window.setHistoryData({ success: false, error: '" + errorMsg + "' }); " +
                                            "}";
                    context.executeJavaScriptOnEDT(jsCode);
                });
            }
        });
    }

    /**
     * Deep search history records. The opencode index is rebuilt from live
     * turn-end recording; deep search simply reloads it.
     */
    void handleDeepSearchHistory(String provider) {
        LOG.info("[HistoryHandler] ========== 开始深度搜索 ========== provider=" + provider);
        handleLoadHistoryData(provider);
    }

    /**
     * Build the frontend history payload from the plugin-side opencode session index.
     */
    private String buildOpenCodeHistoryJson(String projectPath) {
        List<JsonObject> sessions = OpenCodeSessionIndex.getInstance().sessionsForProject(projectPath);

        JsonObject history = new JsonObject();
        history.addProperty("success", true);
        JsonArray sessionArray = new JsonArray();
        long totalMessages = 0;
        for (JsonObject entry : sessions) {
            JsonObject session = new JsonObject();
            session.addProperty("sessionId", entry.has("sessionId") ? entry.get("sessionId").getAsString() : "");
            session.addProperty("title", entry.has("title") && !entry.get("title").isJsonNull()
                    ? entry.get("title").getAsString() : "Untitled session");
            int messageCount = entry.has("messageCount") && entry.get("messageCount").isJsonPrimitive()
                    ? Math.max(0, entry.get("messageCount").getAsInt()) : 0;
            session.addProperty("messageCount", messageCount);
            totalMessages += messageCount;
            if (entry.has("model") && !entry.get("model").isJsonNull()) {
                session.addProperty("model", entry.get("model").getAsString());
            }
            if (entry.has("lastTimestamp") && entry.get("lastTimestamp").isJsonPrimitive()) {
                session.addProperty("lastTimestamp", java.time.Instant.ofEpochMilli(
                        entry.get("lastTimestamp").getAsLong()).toString());
            }
            sessionArray.add(session);
        }
        history.add("sessions", sessionArray);
        history.addProperty("total", totalMessages);
        return new Gson().toJson(history);
    }

    /**
     * Enhance history data: add favorite info to each session.
     */
    private String enhanceHistoryWithFavorites(String historyJson, String currentProvider) {
        try {
            // Load favorite data
            String favoritesJson = nodeJsServiceCaller.callNodeJsFavoritesService("loadFavorites", "");

            // Parse history data and favorite data
            JsonObject history = new Gson().fromJson(historyJson, JsonObject.class);
            JsonObject favorites = new Gson().fromJson(favoritesJson, JsonObject.class);

            // Add favorite info and provider info to each session
            if (history.has("sessions") && history.get("sessions").isJsonArray()) {
                JsonArray sessions = history.getAsJsonArray("sessions");
                for (int i = 0; i < sessions.size(); i++) {
                    JsonObject session = sessions.get(i).getAsJsonObject();
                    String sessionId = session.get("sessionId").getAsString();

                    // Add provider info
                    session.addProperty("provider", currentProvider);

                    if (favorites.has(sessionId)) {
                        JsonObject favoriteInfo = favorites.getAsJsonObject(sessionId);
                        session.addProperty("isFavorited", true);
                        session.addProperty("favoritedAt", favoriteInfo.get("favoritedAt").getAsLong());
                    } else {
                        session.addProperty("isFavorited", false);
                    }
                }
            }

            // Also add favorite data to the history data
            history.add("favorites", favorites);

            return new Gson().toJson(history);

        } catch (Exception e) {
            LOG.warn("[HistoryHandler] 增强历史数据失败，返回原始数据: " + e.getMessage());
            return historyJson;
        }
    }

    /**
     * Enhance history data: add custom titles to each session.
     */
    private String enhanceHistoryWithTitles(String historyJson) {
        try {
            // Load title data
            String titlesJson = nodeJsServiceCaller.callNodeJsTitlesService("loadTitles");

            // Parse history data and title data
            JsonObject history = new Gson().fromJson(historyJson, JsonObject.class);
            JsonObject titles = new Gson().fromJson(titlesJson, JsonObject.class);

            // Add custom title to each session
            if (history.has("sessions") && history.get("sessions").isJsonArray()) {
                JsonArray sessions = history.getAsJsonArray("sessions");
                for (int i = 0; i < sessions.size(); i++) {
                    JsonObject session = sessions.get(i).getAsJsonObject();
                    String sessionId = session.get("sessionId").getAsString();

                    if (titles.has(sessionId)) {
                        JsonObject titleInfo = titles.getAsJsonObject(sessionId);
                        // If a custom title exists, override the original title
                        if (titleInfo.has("customTitle")) {
                            String customTitle = titleInfo.get("customTitle").getAsString();
                            session.addProperty("title", customTitle);
                            session.addProperty("hasCustomTitle", true);
                        }
                    }
                }
            }

            return new Gson().toJson(history);

        } catch (Exception e) {
            LOG.warn("[HistoryHandler] 增强标题数据失败，返回原始数据: " + e.getMessage());
            return historyJson;
        }
    }
}
