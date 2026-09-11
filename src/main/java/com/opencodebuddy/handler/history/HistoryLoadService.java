package com.opencodebuddy.handler.history;

import com.opencodebuddy.handler.core.HandlerContext;
import com.opencodebuddy.provider.opencode.OpenCodeSDKBridge;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

/**
 * Service for loading and enhancing history data.
 * OpenCode native: sessions are queried directly from the OpenCode server
 * via SDK (default limit: 20) with favorites retrieved from session.metadata.
 */
class HistoryLoadService {

    private static final Logger LOG = Logger.getInstance(HistoryLoadService.class);
    private static final Gson GSON = new Gson();
    private static final int DEFAULT_SESSION_LIMIT = 20;

    private final HandlerContext context;

    HistoryLoadService(HandlerContext context) {
        this.context = context;
    }

    /**
     * Load and inject history data into the frontend (including favorite info from session.metadata).
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

                OpenCodeSDKBridge bridge = context.getOpenCodeSDKBridge();
                if (bridge == null) {
                    LOG.warn("[HistoryHandler] OpenCodeSDKBridge is null");
                    return;
                }

                JsonElement resultElement = bridge.listSessions(projectPath, 0).join();
                String finalJson = buildHistoryJsonFromNativeSessions(resultElement, provider);
                LOG.info("[HistoryHandler] 构建原生历史会话数据完成，JSON 长度: " + finalJson.length());

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
     * Deep search history records (reload from server).
     */
    void handleDeepSearchHistory(String provider) {
        LOG.info("[HistoryHandler] ========== 开始深度搜索 ========== provider=" + provider);
        handleLoadHistoryData(provider);
    }

    /**
     * Build standard frontend history payload from native OpenCode session list response.
     */
    private String buildHistoryJsonFromNativeSessions(JsonElement resultElement, String provider) {
        JsonObject history = new JsonObject();
        history.addProperty("success", true);

        JsonArray sessionsArray = new JsonArray();
        JsonObject favoritesObj = new JsonObject();

        JsonArray rawSessions = null;
        if (resultElement != null && resultElement.isJsonObject()) {
            JsonObject obj = resultElement.getAsJsonObject();
            if (obj.has("sessions") && obj.get("sessions").isJsonArray()) {
                rawSessions = obj.getAsJsonArray("sessions");
            }
        } else if (resultElement != null && resultElement.isJsonArray()) {
            rawSessions = resultElement.getAsJsonArray();
        }

        if (rawSessions != null) {
            for (JsonElement item : rawSessions) {
                if (!item.isJsonObject()) continue;
                JsonObject s = item.getAsJsonObject();

                JsonObject session = new JsonObject();
                String sessionId = s.has("id") && !s.get("id").isJsonNull() ? s.get("id").getAsString() : "";
                if (sessionId.isEmpty() && s.has("sessionId") && !s.get("sessionId").isJsonNull()) {
                    sessionId = s.get("sessionId").getAsString();
                }
                session.addProperty("sessionId", sessionId);

                String title = s.has("title") && !s.get("title").isJsonNull()
                        ? s.get("title").getAsString() : "Untitled session";
                session.addProperty("title", title);
                session.addProperty("messageCount", 0);
                session.addProperty("provider", provider != null && !provider.isBlank() ? provider : HandlerContext.DEFAULT_PROVIDER);

                // Timestamp
                if (s.has("time") && s.get("time").isJsonObject()) {
                    JsonObject timeObj = s.getAsJsonObject("time");
                    long updated = timeObj.has("updated") && timeObj.get("updated").isJsonPrimitive()
                            ? timeObj.get("updated").getAsLong()
                            : (timeObj.has("created") && timeObj.get("created").isJsonPrimitive() ? timeObj.get("created").getAsLong() : 0);
                    if (updated > 0) {
                        if (updated < 100_000_000_000L) {
                            updated *= 1000L;
                        }
                        session.addProperty("lastTimestamp", Instant.ofEpochMilli(updated).toString());
                    }
                } else if (s.has("lastTimestamp") && s.get("lastTimestamp").isJsonPrimitive()) {
                    session.addProperty("lastTimestamp", s.get("lastTimestamp").getAsString());
                }

                // Model
                if (s.has("model")) {
                    JsonElement modelElem = s.get("model");
                    if (modelElem.isJsonObject()) {
                        JsonObject mObj = modelElem.getAsJsonObject();
                        String p = mObj.has("providerID") && !mObj.get("providerID").isJsonNull() ? mObj.get("providerID").getAsString() : "";
                        String m = mObj.has("id") && !mObj.get("id").isJsonNull() ? mObj.get("id").getAsString() : "";
                        session.addProperty("model", !p.isEmpty() && !m.isEmpty() ? p + "/" + m : m);
                    } else if (modelElem.isJsonPrimitive()) {
                        session.addProperty("model", modelElem.getAsString());
                    }
                }

                // Agent
                if (s.has("agent") && !s.get("agent").isJsonNull()) {
                    session.addProperty("agent", s.get("agent").getAsString());
                }

                // Metadata: isFavorited & favoritedAt
                boolean isFavorited = false;
                long favoritedAt = 0;
                if (s.has("metadata") && s.get("metadata").isJsonObject()) {
                    JsonObject meta = s.getAsJsonObject("metadata");
                    if (meta.has("isFavorited") && meta.get("isFavorited").isJsonPrimitive()) {
                        isFavorited = meta.get("isFavorited").getAsBoolean();
                    }
                    if (meta.has("favoritedAt") && meta.get("favoritedAt").isJsonPrimitive()) {
                        favoritedAt = meta.get("favoritedAt").getAsLong();
                    }
                }

                session.addProperty("isFavorited", isFavorited);
                if (isFavorited) {
                    if (favoritedAt <= 0) {
                        favoritedAt = System.currentTimeMillis();
                    }
                    session.addProperty("favoritedAt", favoritedAt);
                    JsonObject favEntry = new JsonObject();
                    favEntry.addProperty("favoritedAt", favoritedAt);
                    favoritesObj.add(sessionId, favEntry);
                }

                sessionsArray.add(session);
            }
        }

        history.add("sessions", sessionsArray);
        history.add("favorites", favoritesObj);
        history.addProperty("total", sessionsArray.size());

        return GSON.toJson(history);
    }
}
