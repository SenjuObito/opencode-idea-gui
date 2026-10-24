package com.opencodebuddy.handler.history;

import com.opencodebuddy.handler.core.HandlerContext;
import com.opencodebuddy.provider.opencode.OpenCodeSDKBridge;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Service for loading and enhancing history data.
 * OpenCode native: sessions are queried directly from the OpenCode server
 * via SDK (default limit: 20) with favorites retrieved from session.metadata.
 */
public class HistoryLoadService {

    private static final Logger LOG = Logger.getInstance(HistoryLoadService.class);
    private static final Gson GSON = new Gson();
    private static final int DEFAULT_SESSION_LIMIT = 20;

    private final HandlerContext context;

    public HistoryLoadService(HandlerContext context) {
        this.context = context;
    }

    /**
     * Load and inject history data into the frontend (including favorite info from session.metadata).
     */
    public void handleLoadHistoryData(String provider) {
        CompletableFuture.runAsync(() -> {
            LOG.info("[HistoryHandler] ========== 开始加载历史数据 ========== provider=" + provider);

            try {
                String projectPath = context.resolveEffectiveWorkingDirectory();
                if (projectPath == null) {
                    LOG.warn("[HistoryHandler] Project base path is null");
                    notifyHistoryError("项目根路径为空，无法加载历史记录");
                    return;
                }
                projectPath = projectPath.replace('\\', '/');

                OpenCodeSDKBridge bridge = context.getOpenCodeSDKBridge();
                if (bridge == null) {
                    LOG.warn("[HistoryHandler] OpenCodeSDKBridge is null");
                    notifyHistoryError("OpenCode SDK 未就绪，无法加载历史记录");
                    return;
                }

                JsonElement resultElement = bridge.listSessions(projectPath, 0)
                        .orTimeout(15, TimeUnit.SECONDS)
                        .join();
                String finalJson = buildHistoryJsonFromNativeSessions(resultElement, provider);
                LOG.info("[HistoryHandler] 构建原生历史会话数据完成，JSON 长度: " + finalJson.length());

                context.callJavaScript("window.setHistoryData", context.escapeJs(finalJson));
                LOG.info("[HistoryHandler] 历史会话数据已通过 callJavaScript 发送给前端");

            } catch (Exception e) {
                LOG.error("[HistoryHandler] 加载历史数据失败: " + e.getMessage(), e);
                notifyHistoryError(e.getMessage() != null ? e.getMessage() : "未知错误");
            }
        });
    }

    private void notifyHistoryError(String errorMessage) {
        JsonObject errorObj = new JsonObject();
        errorObj.addProperty("success", false);
        errorObj.addProperty("error", errorMessage);
        context.callJavaScript("window.setHistoryData", context.escapeJs(GSON.toJson(errorObj)));
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
                if (!item.isJsonObject()) {
                    continue;
                }
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

                // Model (core normalizes v2's {providerID,id} object to a
                // provider/id string; the host only consumes that contract).
                if (s.has("model") && s.get("model").isJsonPrimitive()
                        && !s.get("model").isJsonNull()) {
                    session.addProperty("model", s.get("model").getAsString());
                }

                // Agent
                if (s.has("agent") && !s.get("agent").isJsonNull()) {
                    session.addProperty("agent", s.get("agent").getAsString());
                }

                // Core already unified v1 `directory` and v2 `location.directory`
                // into `directory`; do not re-implement that split here.
                if (s.has("directory") && !s.get("directory").isJsonNull()) {
                    String sessionDir = s.get("directory").getAsString();
                    if (!sessionDir.isBlank()) {
                        session.addProperty("projectPath", sessionDir);
                    }
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
