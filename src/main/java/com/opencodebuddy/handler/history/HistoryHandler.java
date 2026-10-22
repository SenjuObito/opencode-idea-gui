package com.opencodebuddy.handler.history;

import com.opencodebuddy.handler.core.BaseMessageHandler;
import com.opencodebuddy.handler.core.HandlerContext;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;

/**
 * History data handler.
 * Routes history-related messages to dedicated service classes.
 */
public class HistoryHandler extends BaseMessageHandler {

    private static final Logger LOG = Logger.getInstance(HistoryHandler.class);

    private static final String[] SUPPORTED_TYPES = {
            "load_history_data",
            "history.refresh",
            "session.load",
            "delete_session",  // Delete session
            "history.delete",
            "delete_sessions", // Batch delete sessions
            "history.deleteMany",
            "export_session",  // Export session
            "history.export",
            "toggle_favorite", // Toggle favorite status
            "history.favorite",
            "session.rename", // Rename the session title
            "delete_title",    // Delete orphaned custom title (B-011)
            "deep_search_history", // Deep search (clear cache and reload)
            "history.deepSearch",
            "subagent.history",
            "load_subagent_session"
    };

    // Session load callback interface
    public interface SessionLoadCallback {
        /**
         * @param model optional model id from the history row; null/blank keeps previous UI model
         * @param tabId stable webview tab that requested the load; null for legacy callers
         */
        void onLoadSession(String sessionId, String projectPath, String provider, String model, String tabId);
    }

    private SessionLoadCallback sessionLoadCallback;
    private String currentProvider = HandlerContext.DEFAULT_PROVIDER;

    private final HistoryLoadService historyLoadService;
    private final HistoryDeleteService historyDeleteService;
    private final HistoryExportService historyExportService;
    private final HistoryMessageInjector historyMessageInjector;
    private final HistoryMetadataService historyMetadataService;
    private final SubagentHistoryService subagentHistoryService;

    public HistoryHandler(HandlerContext context) {
        super(context);
        this.historyLoadService = new HistoryLoadService(context);
        this.historyDeleteService = new HistoryDeleteService(context, historyLoadService);
        this.historyExportService = new HistoryExportService(context);
        this.historyMessageInjector = new HistoryMessageInjector(context);
        this.historyMetadataService = new HistoryMetadataService(context, historyLoadService);
        this.subagentHistoryService = new SubagentHistoryService(context);
    }

    public void setSessionLoadCallback(SessionLoadCallback callback) {
        this.sessionLoadCallback = callback;
    }

    @Override
    public String[] getSupportedTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public boolean handle(String type, String content) {
        switch (type) {
            case "load_history_data":
            case "history.refresh":
                LOG.debug("[HistoryHandler] 处理: history.refresh, provider=" + content);
                this.currentProvider = HandlerContext.DEFAULT_PROVIDER;
                historyLoadService.handleLoadHistoryData(currentProvider);
                return true;
            case "session.load":
                LOG.debug("[HistoryHandler] 处理: load_session");
                historyMessageInjector.handleLoadSession(content, currentProvider, sessionLoadCallback);
                return true;
            case "delete_session":
            case "history.delete":
                LOG.info("[HistoryHandler] 处理: delete_session, sessionId=" + content);
                historyDeleteService.handleDeleteSession(extractString(content, "sessionId"), currentProvider);
                return true;
            case "delete_sessions":
            case "history.deleteMany":
                LOG.info("[HistoryHandler] 处理: delete_sessions");
                historyDeleteService.handleDeleteSessions(extractArray(content, "sessionIds"), currentProvider);
                return true;
            case "export_session":
            case "history.export":
                LOG.info("[HistoryHandler] 处理: export_session, sessionId=" + content);
                historyExportService.handleExportSession(content, currentProvider);
                return true;
            case "toggle_favorite":
            case "history.favorite":
                LOG.info("[HistoryHandler] 处理: toggle_favorite, sessionId=" + content);
                historyMetadataService.handleToggleFavorite(extractString(content, "sessionId"));
                return true;
            case "session.rename":
                LOG.info("[HistoryHandler] 处理: session.rename");
                historyMetadataService.handleUpdateTitle(content, currentProvider);
                return true;
            case "delete_title":
                LOG.info("[HistoryHandler] 处理: delete_title, sessionId=" + content);
                historyMetadataService.handleDeleteTitle(content);
                return true;
            case "deep_search_history":
            case "history.deepSearch":
                LOG.info("[HistoryHandler] 处理: deep_search_history, provider=" + content);
                this.currentProvider = HandlerContext.DEFAULT_PROVIDER;
                historyLoadService.handleDeepSearchHistory(currentProvider);
                return true;
            case "subagent.history":
            case "load_subagent_session":
                LOG.debug("[HistoryHandler] 处理: subagent.history");
                subagentHistoryService.handle(content);
                return true;
            default:
                return false;
        }
    }

    /** Envelope payloads carry the id as JSON; legacy callers send the bare id. */
    static String extractString(String content, String field) {
        if (content == null) {
            return null;
        }
        String trimmed = content.trim();
        if (!trimmed.startsWith("{")) {
            return content;
        }
        try {
            JsonObject payload = JsonParser.parseString(trimmed).getAsJsonObject();
            if (payload.has(field) && !payload.get(field).isJsonNull()) {
                return payload.get(field).getAsString();
            }
        } catch (Exception ignored) {
            // Fall through to the legacy raw value.
        }
        return content;
    }

    /** Envelope payloads carry the id list as JSON; legacy callers send the bare array. */
    static String extractArray(String content, String field) {
        if (content == null) {
            return null;
        }
        String trimmed = content.trim();
        if (!trimmed.startsWith("{")) {
            return content;
        }
        try {
            JsonObject payload = JsonParser.parseString(trimmed).getAsJsonObject();
            if (payload.has(field) && payload.get(field).isJsonArray()) {
                return payload.get(field).toString();
            }
        } catch (Exception ignored) {
            // Fall through to the legacy raw value.
        }
        return content;
    }
}
