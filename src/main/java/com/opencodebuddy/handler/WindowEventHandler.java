package com.opencodebuddy.handler;

import com.opencodebuddy.handler.core.BaseMessageHandler;
import com.opencodebuddy.handler.core.HandlerContext;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

/**
 * Handles window-level events from the frontend:
 * heartbeat, tab status changes, session lifecycle signals, and DOM commit acknowledgments.
 */
public class WindowEventHandler extends BaseMessageHandler {

    private static final Logger LOG = Logger.getInstance(WindowEventHandler.class);
    private static final String[] SUPPORTED_TYPES = {
        "heartbeat", "tab_loading_changed", "tab_status_changed",
        "create_new_session", "frontend_ready", "history_dom_committed",
        "history_render_complete", "surface_damage_applied",
        "refresh_slash_commands",
        "fork_session", "share_session", "unshare_session",
        "revert_session", "unrevert_session", "compact_session",
        "set_thinking_enabled", "set_ui_preferences"
    };

    /**
     * Callback interface for window-level operations.
     */
    public interface Callback {
        void onHeartbeat(String content);
        void onTabLoadingChanged(boolean loading);
        void onTabStatusChanged(String status);
        void onCreateNewSession();
        void onFrontendReady();
        void onHistoryRenderComplete(long commitEpoch);
        void onSurfaceDamageApplied(String token, String phase, boolean applied);
        void onRefreshSlashCommands();
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
                callback.onHeartbeat(content);
                return true;
            case "tab_loading_changed":
                handleTabLoadingChanged(content);
                return true;
            case "tab_status_changed":
                handleTabStatusChanged(content);
                return true;
            case "create_new_session":
                callback.onCreateNewSession();
                return true;
            case "frontend_ready":
                callback.onFrontendReady();
                return true;
            case "history_dom_committed":
                callback.onHistoryRenderComplete(parseHistoryCommitEpoch(content));
                return true;
            case "history_render_complete":
                callback.onHistoryRenderComplete(1L);
                return true;
            case "surface_damage_applied":
                handleSurfaceDamageApplied(content);
                return true;
            case "refresh_slash_commands":
                callback.onRefreshSlashCommands();
                return true;
            case "fork_session":
                handleForkSession(content);
                return true;
            case "share_session":
                handleShareSession(content);
                return true;
            case "unshare_session":
                handleUnshareSession(content);
                return true;
            case "revert_session":
                handleRevertSession(content);
                return true;
            case "unrevert_session":
                handleUnrevertSession();
                return true;
            case "compact_session":
                handleCompactSession();
                return true;
            case "set_thinking_enabled":
            case "set_ui_preferences":
                // UI preference replays; the webview persists these locally.
                return true;
            default:
                return false;
        }
    }


    // =========================================================================
    // Fork / Share / Revert / Compact (daemon-backed, mirrors the vscode host)
    // =========================================================================

    private void handleForkSession(String content) {
        String messageId = content == null || content.isBlank()
                || "latest".equals(content.trim()) ? null : content.trim();
        if ("full".equals(messageId)) {
            messageId = null;
        }
        final String mid = messageId;
        requestDaemonJson("fork", () -> {
            java.util.List<String> chunks = new java.util.ArrayList<>();
            com.google.gson.JsonObject params = new com.google.gson.JsonObject();
            params.addProperty("sessionId", sessionIdOrEmpty());
            if (mid != null) {
                params.addProperty("messageID", mid);
            }
            return params;
        }, done -> {
            String newSessionId = extractStringField(done, "newSessionId");
            if (!newSessionId.isEmpty()) {
                callJavaScript("onForkSuccess", "{\"sessionId\":\"" + newSessionId + "\"}");
            } else {
                callJavaScript("onForkError", "");
            }
        });
    }

    private void handleShareSession(String sessionId) {
        requestDaemonJson("shareSession", () -> singleSessionParams(sessionId), done -> {
            String url = extractShareUrl(done);
            if (!url.isEmpty()) {
                callJavaScript("onShareSuccess", url);
            } else {
                callJavaScript("onShareError", "");
            }
        });
    }

    private void handleUnshareSession(String sessionId) {
        requestDaemonJson("unshareSession", () -> singleSessionParams(sessionId),
                done -> callJavaScript("onShareSuccess", ""));
    }

    private void handleRevertSession(String messageId) {
        requestDaemonJson("revert", () -> {
            com.google.gson.JsonObject params = singleSessionParams(null);
            if (messageId != null && !messageId.isBlank()) {
                params.addProperty("messageID", messageId.trim());
            }
            return params;
        }, done -> callJavaScript("onRevertState", joinLines(done)));
    }

    private void handleUnrevertSession() {
        requestDaemonJson("unrevert", () -> singleSessionParams(null),
                done -> callJavaScript("onRevertState", joinLines(done)));
    }

    private void handleCompactSession() {
        requestDaemonJson("summarize", () -> singleSessionParams(null),
                done -> callJavaScript("onCompactResult", "{\"success\":true}"));
    }

    private interface ParamsBuilder {
        com.google.gson.JsonObject build();
    }

    private interface CompleteHandler {
        void handle(java.util.List<String> chunks);
    }

    private void requestDaemonJson(String command, ParamsBuilder params, CompleteHandler onComplete) {
        try {
            java.util.List<String> chunks = new java.util.ArrayList<>();
            context.getOpenCodeSDKBridge().request("opencode." + command, params.build(),
                    new com.opencodebuddy.provider.common.DaemonBridge.DaemonOutputCallback() {
                @Override
                public void onLine(String line) {
                    chunks.add(line);
                }

                @Override
                public void onStderr(String text) {
                }

                @Override
                public void onError(String error) {
                    LOG.warn("[WindowEventHandler] opencode." + command + " failed: " + error);
                }

                @Override
                public void onComplete(boolean success) {
                    onComplete.handle(chunks);
                }
            });
        } catch (Exception e) {
            LOG.error("[WindowEventHandler] opencode." + command + " request failed", e);
        }
    }

    private String sessionIdOrEmpty() {
        com.opencodebuddy.session.ClaudeSession session = context.getSession();
        String id = session != null ? session.getSessionId() : null;
        return id != null ? id : "";
    }

    private com.google.gson.JsonObject singleSessionParams(String sessionId) {
        String id = sessionId != null && !sessionId.isBlank() ? sessionId : sessionIdOrEmpty();
        com.google.gson.JsonObject params = new com.google.gson.JsonObject();
        params.addProperty("sessionId", id);
        return params;
    }

    private static String joinLines(java.util.List<String> chunks) {
        return String.join("\n", chunks);
    }

    private static String extractStringField(java.util.List<String> chunks, String field) {
        com.google.gson.JsonObject obj = extractJsonObject(chunks);
        if (obj != null && obj.has(field) && !obj.get(field).isJsonNull()) {
            return obj.get(field).getAsString();
        }
        return "";
    }

    private static String extractShareUrl(java.util.List<String> chunks) {
        com.google.gson.JsonObject obj = extractJsonObject(chunks);
        if (obj == null) {
            return "";
        }
        if (obj.has("url") && !obj.get("url").isJsonNull()) {
            return obj.get("url").getAsString();
        }
        if (obj.has("share") && obj.get("share").isJsonObject()) {
            com.google.gson.JsonObject share = obj.getAsJsonObject("share");
            if (share.has("url") && !share.get("url").isJsonNull()) {
                return share.get("url").getAsString();
            }
            if (share.has("share") && share.get("share").isJsonObject()) {
                com.google.gson.JsonObject nested = share.getAsJsonObject("share");
                if (nested.has("url") && !nested.get("url").isJsonNull()) {
                    return nested.get("url").getAsString();
                }
            }
        }
        return "";
    }

    private static com.google.gson.JsonObject extractJsonObject(java.util.List<String> chunks) {
        for (int i = chunks.size() - 1; i >= 0; i--) {
            String trimmed = chunks.get(i).trim();
            if (trimmed.startsWith("{")) {
                try {
                    com.google.gson.JsonElement el = com.google.gson.JsonParser.parseString(trimmed);
                    if (el.isJsonObject()) {
                        return el.getAsJsonObject();
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    @Override
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
            return Math.max(1L, Long.parseLong(content == null ? "" : content.trim()));
        } catch (NumberFormatException e) {
            LOG.warn("[WebviewSurface] Invalid history commit epoch: " + content);
            return 1L;
        }
    }
}
