package com.opencodebuddy.handler.history;

import com.opencodebuddy.handler.core.HandlerContext;
import com.opencodebuddy.provider.opencode.OpenCodeSDKBridge;
import com.opencodebuddy.session.ClaudeSession;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Service for deleting session history data.
 * Calls OpenCode SDK to physically delete sessions from OpenCode server storage.
 */
class HistoryDeleteService {

    private static final Logger LOG = Logger.getInstance(HistoryDeleteService.class);
    private static final Gson GSON = new Gson();

    // Reject anything outside [A-Za-z0-9._-] to defeat path-traversal payloads such as "../foo"
    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._-]+$");

    static boolean isValidSessionId(String sessionId) {
        return sessionId != null && SESSION_ID_PATTERN.matcher(sessionId).matches();
    }

    private final HandlerContext context;
    private final HistoryLoadService historyLoadService;

    HistoryDeleteService(HandlerContext context, HistoryLoadService historyLoadService) {
        this.context = context;
        this.historyLoadService = historyLoadService;
    }

    /**
     * Delete session history data via OpenCode native delete API.
     */
    void handleDeleteSession(String sessionId, String currentProvider) {
        if (!isValidSessionId(sessionId)) {
            LOG.warn("[HistoryHandler] Delete session rejected: invalid sessionId");
            return;
        }
        quiesceActiveSessionForDeletion(
                context.getSession(), Collections.singleton(sessionId), currentProvider)
                .thenRunAsync(() -> {
                    try {
                        LOG.info("[HistoryHandler] ========== Delete session start ==========");
                        LOG.info("[HistoryHandler] SessionId: " + sessionId + ", Provider: " + currentProvider);

                        String projectPath = context.resolveEffectiveWorkingDirectory();
                        OpenCodeSDKBridge bridge = context.getOpenCodeSDKBridge();
                        if (bridge != null) {
                            bridge.deleteSession(sessionId, projectPath).join();
                            LOG.info("[HistoryHandler] Native session deleted successfully: " + sessionId);
                        }

                        LOG.info("[HistoryHandler] Reloading history data...");
                        historyLoadService.handleLoadHistoryData(currentProvider);

                    } catch (Exception e) {
                        LOG.error("[HistoryHandler] Delete session failed: " + e.getMessage(), e);
                    }
                }).exceptionally(ex -> {
                    handleQuiesceFailure("deletion", currentProvider, ex);
                    return null;
                });
    }

    /**
     * Batch delete session history data via OpenCode native delete API.
     */
    void handleDeleteSessions(String content, String currentProvider) {
        List<String> sessionIds = parseSessionIds(content);
        if (sessionIds.isEmpty()) {
            LOG.warn("[HistoryHandler] Batch delete failed: empty sessionIds");
            return;
        }

        quiesceActiveSessionForDeletion(context.getSession(), sessionIds, currentProvider)
                .thenRunAsync(() -> {
                    try {
                        LOG.info("[HistoryHandler] ========== Batch delete sessions start ==========");
                        LOG.info("[HistoryHandler] SessionIds: " + GSON.toJson(sessionIds) + ", Provider: " + currentProvider);

                        String projectPath = context.resolveEffectiveWorkingDirectory();
                        OpenCodeSDKBridge bridge = context.getOpenCodeSDKBridge();
                        if (bridge != null) {
                            for (String sessionId : sessionIds) {
                                try {
                                    bridge.deleteSession(sessionId, projectPath).join();
                                } catch (Exception ex) {
                                    LOG.warn("[HistoryHandler] Failed to delete session: " + sessionId, ex);
                                }
                            }
                        }

                        LOG.info("[HistoryHandler] Batch delete completed: " + sessionIds.size() + " sessions");
                        LOG.info("[HistoryHandler] Reloading history data...");
                        historyLoadService.handleLoadHistoryData(currentProvider);
                    } catch (Exception e) {
                        LOG.error("[HistoryHandler] Batch delete sessions failed: " + e.getMessage(), e);
                    }
                }).exceptionally(ex -> {
                    handleQuiesceFailure("batch deletion", currentProvider, ex);
                    return null;
                });
    }

    private void handleQuiesceFailure(String action, String currentProvider, Throwable error) {
        LOG.warn("[HistoryHandler] Failed to stop active session before " + action
                + ": " + error.getMessage(), error);
        try {
            historyLoadService.handleLoadHistoryData(currentProvider);
        } catch (Exception reloadError) {
            LOG.warn("[HistoryHandler] Failed to restore history after aborted " + action
                    + ": " + reloadError.getMessage(), reloadError);
        }
    }

    static CompletableFuture<Void> quiesceActiveSessionForDeletion(
            ClaudeSession session,
            Collection<String> sessionIds,
            String currentProvider
    ) {
        if (session == null || sessionIds == null || sessionIds.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        String activeSessionId = session.getSessionId();
        if (activeSessionId == null
                || !sessionIds.contains(activeSessionId)
                || !Objects.equals(session.getProvider(), currentProvider)) {
            return CompletableFuture.completedFuture(null);
        }
        return session.interrupt();
    }

    static List<String> parseSessionIds(String content) {
        LinkedHashSet<String> sessionIds = new LinkedHashSet<>();
        if (content == null || content.trim().isEmpty()) {
            return new ArrayList<>();
        }

        try {
            JsonElement parsed = JsonParser.parseString(content);
            if (parsed.isJsonArray()) {
                collectSessionIds(parsed.getAsJsonArray(), sessionIds);
            } else if (parsed.isJsonObject()) {
                JsonObject object = parsed.getAsJsonObject();
                JsonElement sessionIdsElement = object.get("sessionIds");
                if (sessionIdsElement != null && sessionIdsElement.isJsonArray()) {
                    collectSessionIds(sessionIdsElement.getAsJsonArray(), sessionIds);
                }
            }
        } catch (Exception e) {
            LOG.warn("[HistoryHandler] Batch delete sessionIds parse failed: " + e.getMessage());
        }

        return new ArrayList<>(sessionIds);
    }

    private static void collectSessionIds(JsonArray array, LinkedHashSet<String> sessionIds) {
        for (JsonElement element : array) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                continue;
            }

            String sessionId = element.getAsString().trim();
            if (sessionId.isEmpty()) {
                continue;
            }
            if (!isValidSessionId(sessionId)) {
                LOG.warn("[HistoryHandler] Batch delete ignored invalid sessionId");
                continue;
            }
            sessionIds.add(sessionId);
        }
    }
}
