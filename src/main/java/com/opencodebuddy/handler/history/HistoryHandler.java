package com.opencodebuddy.handler.history;

import com.opencodebuddy.handler.core.BaseMessageHandler;
import com.opencodebuddy.handler.core.HandlerContext;

import com.intellij.openapi.diagnostic.Logger;

/**
 * History data handler.
 * Routes history-related messages to dedicated service classes.
 */
public class HistoryHandler extends BaseMessageHandler {

    private static final Logger LOG = Logger.getInstance(HistoryHandler.class);

    private static final String[] SUPPORTED_TYPES = {
            "load_history_data",
            "load_session",
            "delete_session",  // Delete session
            "delete_sessions", // Batch delete sessions
            "export_session",  // Export session
            "toggle_favorite", // Toggle favorite status
            "update_title",    // Update session title
            "delete_title",    // Delete orphaned custom title (B-011)
            "deep_search_history" // Deep search (clear cache and reload)
    };

    // Session load callback interface
    public interface SessionLoadCallback {
        /**
         * @param model optional model id from the history row; null/blank keeps previous UI model
         */
        void onLoadSession(String sessionId, String projectPath, String provider, String model);
    }

    private SessionLoadCallback sessionLoadCallback;
    private String currentProvider = HandlerContext.DEFAULT_PROVIDER;

    private final HistoryLoadService historyLoadService;
    private final HistoryDeleteService historyDeleteService;
    private final HistoryExportService historyExportService;
    private final HistoryMessageInjector historyMessageInjector;
    private final HistoryMetadataService historyMetadataService;

    public HistoryHandler(HandlerContext context) {
        super(context);
        this.historyLoadService = new HistoryLoadService(context);
        this.historyDeleteService = new HistoryDeleteService(context, historyLoadService);
        this.historyExportService = new HistoryExportService(context);
        this.historyMessageInjector = new HistoryMessageInjector(context);
        this.historyMetadataService = new HistoryMetadataService(context, historyLoadService);
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
                LOG.debug("[HistoryHandler] 处理: load_history_data, provider=" + content);
                this.currentProvider = HandlerContext.DEFAULT_PROVIDER;
                historyLoadService.handleLoadHistoryData(currentProvider);
                return true;
            case "load_session":
                LOG.debug("[HistoryHandler] 处理: load_session");
                historyMessageInjector.handleLoadSession(content, currentProvider, sessionLoadCallback);
                return true;
            case "delete_session":
                LOG.info("[HistoryHandler] 处理: delete_session, sessionId=" + content);
                historyDeleteService.handleDeleteSession(content, currentProvider);
                return true;
            case "delete_sessions":
                LOG.info("[HistoryHandler] 处理: delete_sessions");
                historyDeleteService.handleDeleteSessions(content, currentProvider);
                return true;
            case "export_session":
                LOG.info("[HistoryHandler] 处理: export_session, sessionId=" + content);
                historyExportService.handleExportSession(content, currentProvider);
                return true;
            case "toggle_favorite":
                LOG.info("[HistoryHandler] 处理: toggle_favorite, sessionId=" + content);
                historyMetadataService.handleToggleFavorite(content);
                return true;
            case "update_title":
                LOG.info("[HistoryHandler] 处理: update_title");
                historyMetadataService.handleUpdateTitle(content, currentProvider);
                return true;
            case "delete_title":
                LOG.info("[HistoryHandler] 处理: delete_title, sessionId=" + content);
                historyMetadataService.handleDeleteTitle(content);
                return true;
            case "deep_search_history":
                LOG.info("[HistoryHandler] 处理: deep_search_history, provider=" + content);
                this.currentProvider = HandlerContext.DEFAULT_PROVIDER;
                historyLoadService.handleDeepSearchHistory(currentProvider);
                return true;
            default:
                return false;
        }
    }
}
