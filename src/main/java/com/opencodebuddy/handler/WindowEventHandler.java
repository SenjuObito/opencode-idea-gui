package com.opencodebuddy.handler;

import com.opencodebuddy.handler.core.BaseMessageHandler;
import com.opencodebuddy.handler.core.HandlerContext;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;

/**
 * Handles window-level events from the frontend:
 * heartbeat, tab status changes, session lifecycle signals, and DOM commit acknowledgments.
 */
public class WindowEventHandler extends BaseMessageHandler {

    private static final Logger LOG = Logger.getInstance(WindowEventHandler.class);
    private static final String[] SUPPORTED_TYPES = {
        "heartbeat", "app.heartbeat", "tab_loading_changed", "tab.loading.changed", "tab_status_changed", "tab.status.changed",
        "tab_activate", "close_session",
        "frontend_ready", "app.frontendReady", "history_dom_committed", "history.domCommitted",
        "history_render_complete", "surface_damage_applied", "surface.damage.applied",
        "refresh_slash_commands", "commands.refresh",
        "context.refresh", "context.setEnabled"
    };

    /**
     * Callback interface for window-level operations.
     */
    public interface Callback {
        void onHeartbeat(String content);
        void onTabLoadingChanged(boolean loading);
        void onTabStatusChanged(String status);
        default void onActivateTab(String tabId) {}
        default void onCloseSession(String targetId) {}
        void onFrontendReady();
        void onHistoryRenderComplete(long commitEpoch);
        void onSurfaceDamageApplied(String token, String phase, boolean applied);
        void onRefreshSlashCommands(String tabId);
        default void onRefreshEditorContext() {}
        default void onSetTabContextEnabled(String tabId, boolean enabled) {}
    }

    private final Callback callback;

    public WindowEventHandler(HandlerContext context, Callback callback) {
        super(context);
        this.callback = callback;
    }

    @Override
    public boolean handle(String type, String content) {
        switch (type) {
            case "heartbeat":
            case "app.heartbeat":
                callback.onHeartbeat(content);
                return true;
            case "tab_loading_changed":
            case "tab.loading.changed":
                handleTabLoadingChanged(content);
                return true;
            case "tab_status_changed":
            case "tab.status.changed":
                handleTabStatusChanged(content);
                return true;
            case "tab_activate":
                callback.onActivateTab(parseSessionId(content));
                return true;
            case "close_session":
                handleCloseSession(content);
                return true;
            case "frontend_ready":
            case "app.frontendReady":
                callback.onFrontendReady();
                return true;
            case "history_dom_committed":
            case "history.domCommitted":
                callback.onHistoryRenderComplete(parseHistoryCommitEpoch(content));
                return true;
            case "history_render_complete":
                callback.onHistoryRenderComplete(1L);
                return true;
            case "surface_damage_applied":
            case "surface.damage.applied":
                handleSurfaceDamageApplied(content);
                return true;
            case "refresh_slash_commands":
            case "commands.refresh":
                callback.onRefreshSlashCommands(parseSessionId(content));
                return true;
            case "context.refresh":
                callback.onRefreshEditorContext();
                return true;
            case "context.setEnabled":
                handleSetTabContextEnabled(content);
                return true;
            default:
                return false;
        }
    }

    /**
     * The user toggled this tab's editor-context switch. The value belongs to
     * the tab record, so the host stores it; it is not a display refresh.
     */
    private void handleSetTabContextEnabled(String content) {
        String tabId = null;
        Boolean enabled = null;
        try {
            JsonObject payload = JsonParser.parseString(content).getAsJsonObject();
            if (payload.has("tabId") && !payload.get("tabId").isJsonNull()) {
                tabId = payload.get("tabId").getAsString();
            }
            if (payload.has("enabled") && !payload.get("enabled").isJsonNull()) {
                enabled = payload.get("enabled").getAsBoolean();
            }
        } catch (Exception e) {
            LOG.warn("[Context] Invalid context.setEnabled payload: " + e.getMessage());
            return;
        }
        if (tabId == null || tabId.isBlank()) {
            LOG.warn("[Context] context.setEnabled without a tab id");
            return;
        }
        if (enabled == null) {
            LOG.warn("[Context] context.setEnabled without an enabled flag");
            return;
        }
        callback.onSetTabContextEnabled(tabId, enabled);
    }

    private String parseSessionId(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        try {
            if (content.trim().startsWith("{")) {
                com.google.gson.JsonObject json = new com.google.gson.Gson().fromJson(content, com.google.gson.JsonObject.class);
                if (json != null && json.has("sessionId") && !json.get("sessionId").isJsonNull()) {
                    return json.get("sessionId").getAsString();
                }
                if (json != null && json.has("tabId") && !json.get("tabId").isJsonNull()) {
                    return json.get("tabId").getAsString();
                }
                return null;
            }
        } catch (Exception ignored) {}
        return content.trim();
    }

    private void handleCloseSession(String content) {
        String sessionId = parseSessionId(content);
        if (sessionId != null && !sessionId.isEmpty()) {
            callback.onCloseSession(sessionId);
        }
    }

    public String[] getSupportedTypes() {
        return SUPPORTED_TYPES;
    }

    private void handleTabLoadingChanged(String content) {
        try {
            JsonObject json = new Gson().fromJson(content, JsonObject.class);
            boolean loading = json.has("loading") && json.get("loading").getAsBoolean();
            callback.onTabLoadingChanged(loading);
        } catch (Exception e) {
            LOG.warn("[TabLoading] Failed to parse loading state: " + e.getMessage());
        }
    }

    private void handleTabStatusChanged(String content) {
        try {
            JsonObject json = new Gson().fromJson(content, JsonObject.class);
            String statusStr = json.has("status") ? json.get("status").getAsString() : "idle";
            callback.onTabStatusChanged(statusStr);
        } catch (Exception e) {
            LOG.warn("[TabStatus] Failed to parse tab status: " + e.getMessage());
        }
    }

    private void handleSurfaceDamageApplied(String content) {
        try {
            JsonObject json = new Gson().fromJson(content, JsonObject.class);
            String token = json.has("token") ? json.get("token").getAsString() : "";
            String phase = json.has("phase") ? json.get("phase").getAsString() : "";
            boolean applied = json.has("applied") && json.get("applied").getAsBoolean();
            callback.onSurfaceDamageApplied(token, phase, applied);
        } catch (Exception e) {
            LOG.warn("[WebviewSurface] Failed to parse damage acknowledgment: "
                    + e.getMessage());
        }
    }

    private long parseHistoryCommitEpoch(String content) {
        try {
            String value = content == null ? "" : content.trim();
            if (value.startsWith("{")) {
                JsonObject payload = new Gson().fromJson(value, JsonObject.class);
                if (payload != null && payload.has("epoch") && !payload.get("epoch").isJsonNull()) {
                    value = payload.get("epoch").getAsString();
                }
            }
            return Math.max(1L, Long.parseLong(value));
        } catch (Exception e) {
            LOG.warn("[WebviewSurface] Invalid history commit epoch: " + content);
            return 1L;
        }
    }
}
