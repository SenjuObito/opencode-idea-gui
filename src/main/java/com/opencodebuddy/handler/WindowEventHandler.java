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
        "revert_session", "unrevert_session", "compact_session"
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
            default:
                return false;
        }
    }


    // =========================================================================
    // Fork / Share / Revert / Compact (daemon-backed, mirrors the vscode host)
    // =========================================================================

    private void handleForkSession(String content) {
        String sessionId = sessionIdOrEmpty();
        LOG.info("[fork] request content=\"" + content + "\" sessionId=\"" + sessionId + "\"");
        if (sessionId.isEmpty()) {
            LOG.warn("[fork] skipped: no active sessionId");
            return;
        }

        com.opencodebuddy.session.OpencodeSession session = context.getSession();
        if (session != null && session.isBusy()) {
            LOG.warn("[fork] skipped: session busy");
            callJavaScript("onForkError", "session busy");
            return;
        }

        if ("full".equals(content != null ? content.trim() : null)) {
            requestFork(sessionId, null);
            return;
        }

        withMessageId(sessionId, content, "fork", resolvedId -> {
            requestFork(sessionId, resolvedId);
        });
    }

    private void requestFork(String sessionId, String messageID) {
        String cwd = context.resolveEffectiveWorkingDirectory();
        com.google.gson.JsonObject params = new com.google.gson.JsonObject();
        params.addProperty("sessionId", sessionId);
        if (messageID != null && !messageID.isBlank()) {
            params.addProperty("messageID", messageID);
        }
        if (cwd != null && !cwd.isBlank()) {
            params.addProperty("directory", cwd);
        }

        requestDaemonJson("fork", () -> params, new ResponseHandler() {
            @Override
            public void onError(String error) {
                LOG.error("[fork] Failed to fork session: " + error);
                callJavaScript("onForkError", error != null ? error : "");
            }

            @Override
            public void onComplete(boolean success, java.util.List<String> chunks) {
                if (!success) {
                    LOG.error("[fork] Fork session failed (success=false)");
                    callJavaScript("onForkError", "");
                    return;
                }
                String newSessionId = extractStringField(chunks, "newSessionId");
                LOG.info("[fork] newSessionId=\"" + newSessionId + "\"");
                if (!newSessionId.isEmpty()) {
                    registerForkedSession(newSessionId);
                    callJavaScript("onForkSuccess", "{\"sessionId\":\"" + newSessionId + "\"}");
                } else {
                    callJavaScript("onForkError", "");
                }
            }
        });
    }

    private void registerForkedSession(String newSessionId) {
        try {
            new com.opencodebuddy.handler.history.HistoryLoadService(context).handleLoadHistoryData("opencode");
        } catch (Exception e) {
            LOG.warn("[WindowEventHandler] Failed to reload history after fork: " + e.getMessage());
        }
    }

    private void handleShareSession(String sessionId) {
        requestDaemonJson("shareSession", () -> singleSessionParams(sessionId), new ResponseHandler() {
            @Override
            public void onError(String error) {
                callJavaScript("onShareError", error != null ? error : "");
            }

            @Override
            public void onComplete(boolean success, java.util.List<String> chunks) {
                if (!success) {
                    callJavaScript("onShareError", "");
                    return;
                }
                String url = extractShareUrl(chunks);
                if (!url.isEmpty()) {
                    callJavaScript("onShareSuccess", url);
                } else {
                    callJavaScript("onShareError", "");
                }
            }
        });
    }

    private void handleUnshareSession(String sessionId) {
        requestDaemonJson("unshareSession", () -> singleSessionParams(sessionId), new ResponseHandler() {
            @Override
            public void onError(String error) {
                callJavaScript("onUnshareError", error != null ? error : "");
            }

            @Override
            public void onComplete(boolean success, java.util.List<String> chunks) {
                if (success) {
                    callJavaScript("onUnshareSuccess", "");
                } else {
                    callJavaScript("onUnshareError", "");
                }
            }
        });
    }

    private void handleRevertSession(String messageId) {
        String sessionId = sessionIdOrEmpty();
        LOG.info("[revert] request messageId=\"" + messageId + "\" sessionId=\"" + sessionId + "\"");
        if (sessionId.isEmpty()) {
            LOG.warn("[revert] skipped: no active sessionId");
            return;
        }

        withMessageId(sessionId, messageId, "revert", resolvedId -> {
            String cwd = context.resolveEffectiveWorkingDirectory();
            com.google.gson.JsonObject params = new com.google.gson.JsonObject();
            params.addProperty("sessionId", sessionId);
            params.addProperty("messageID", resolvedId);
            if (cwd != null && !cwd.isBlank()) {
                params.addProperty("directory", cwd);
            }
            requestDaemonJson("revert", () -> params, new ResponseHandler() {
                @Override
                public void onError(String error) {
                    LOG.error("[revert] Failed to revert session: " + error);
                    com.google.gson.JsonObject err = new com.google.gson.JsonObject();
                    err.addProperty("op", "undo");
                    if (error != null) {
                        err.addProperty("error", error);
                    }
                    callJavaScript("onRevertError", err.toString());
                }

                @Override
                public void onComplete(boolean success, java.util.List<String> chunks) {
                    if (!success) {
                        LOG.error("[revert] Revert session failed (success=false)");
                        com.google.gson.JsonObject err = new com.google.gson.JsonObject();
                        err.addProperty("op", "undo");
                        callJavaScript("onRevertError", err.toString());
                        return;
                    }
                    pushRevertStateFromResponse(joinLines(chunks));
                }
            });
        });
    }

    private void handleUnrevertSession() {
        String sessionId = sessionIdOrEmpty();
        LOG.info("[unrevert] request sessionId=\"" + sessionId + "\"");
        if (sessionId.isEmpty()) {
            LOG.warn("[unrevert] skipped: no active sessionId");
            return;
        }

        String cwd = context.resolveEffectiveWorkingDirectory();
        com.google.gson.JsonObject params = new com.google.gson.JsonObject();
        params.addProperty("sessionId", sessionId);
        if (cwd != null && !cwd.isBlank()) {
            params.addProperty("directory", cwd);
        }
        requestDaemonJson("unrevert", () -> params, new ResponseHandler() {
            @Override
            public void onError(String error) {
                LOG.error("[unrevert] Failed to unrevert session: " + error);
                com.google.gson.JsonObject err = new com.google.gson.JsonObject();
                err.addProperty("op", "redo");
                if (error != null) {
                    err.addProperty("error", error);
                }
                callJavaScript("onRevertError", err.toString());
            }

            @Override
            public void onComplete(boolean success, java.util.List<String> chunks) {
                if (!success) {
                    LOG.error("[unrevert] Unrevert session failed (success=false)");
                    com.google.gson.JsonObject err = new com.google.gson.JsonObject();
                    err.addProperty("op", "redo");
                    callJavaScript("onRevertError", err.toString());
                    return;
                }
                pushRevertStateFromResponse(joinLines(chunks));
            }
        });
    }

    private void pushRevertStateFromResponse(String raw) {
        com.google.gson.JsonObject payload = extractJsonObject(java.util.List.of(raw));
        com.google.gson.JsonObject sessionObj = payload != null && payload.has("session") && payload.get("session").isJsonObject()
                ? payload.getAsJsonObject("session") : null;
        com.google.gson.JsonObject revertObj = sessionObj != null && sessionObj.has("revert") && sessionObj.get("revert").isJsonObject()
                ? sessionObj.getAsJsonObject("revert") : null;
        String messageId = null;
        if (revertObj != null && revertObj.has("messageID") && !revertObj.get("messageID").isJsonNull()) {
            messageId = revertObj.get("messageID").getAsString();
        }
        boolean hasRevert = revertObj != null;
        LOG.info("[revert-state] hasRevert=" + hasRevert + " messageId=" + messageId);

        com.opencodebuddy.session.OpencodeSession session = context.getSession();
        if (session != null) {
            session.setRevertState(hasRevert ? new com.opencodebuddy.session.SessionState.RevertState(messageId != null ? messageId : "") : null);
        }

        com.google.gson.JsonObject out = new com.google.gson.JsonObject();
        out.addProperty("hasRevert", hasRevert);
        if (messageId != null && !messageId.isBlank()) {
            out.addProperty("messageId", messageId);
        } else {
            out.add("messageId", com.google.gson.JsonNull.INSTANCE);
        }
        callJavaScript("onRevertStateUpdate", out.toString());
    }

    private void withMessageId(String sessionId, String messageId, String label, java.util.function.Consumer<String> run) {
        if (messageId != null && !messageId.isBlank() && !"latest".equals(messageId.trim())) {
            run.accept(messageId.trim());
            return;
        }
        LOG.info("[" + label + "] messageId empty or latest — resolving latest user message via listMessages, sessionId=" + sessionId);
        String cwd = context.resolveEffectiveWorkingDirectory();
        context.getOpenCodeSDKBridge().listMessages(sessionId, cwd).whenComplete((element, error) -> {
            if (error != null || element == null || !element.isJsonArray()) {
                LOG.warn("[" + label + "] listMessages failed — abort: " + (error != null ? error.getMessage() : "empty result"));
                return;
            }
            String latestUserId = null;
            for (com.google.gson.JsonElement el : element.getAsJsonArray()) {
                if (!el.isJsonObject()) {
                    continue;
                }
                com.google.gson.JsonObject entry = el.getAsJsonObject();
                com.google.gson.JsonObject info = entry.has("info") && entry.get("info").isJsonObject()
                        ? entry.getAsJsonObject("info") : entry;
                String role = info.has("role") && !info.get("role").isJsonNull()
                        ? info.get("role").getAsString() : "";
                if ("user".equals(role)) {
                    if (info.has("id") && !info.get("id").isJsonNull()) {
                        String id = info.get("id").getAsString();
                        if (id != null && !id.isBlank()) {
                            latestUserId = id;
                        }
                    }
                }
            }
            LOG.info("[" + label + "] resolved latest user message id=" + (latestUserId != null ? latestUserId : "(none)"));
            if (latestUserId != null) {
                run.accept(latestUserId);
            }
        });
    }

    private void handleCompactSession() {
        requestDaemonJson("summarize", () -> singleSessionParams(null), new ResponseHandler() {
            @Override
            public void onError(String error) {
                LOG.error("[WindowEventHandler] Compact session failed: " + error);
                callJavaScript("onCompactError", error != null ? error : "");
            }

            @Override
            public void onComplete(boolean success, java.util.List<String> chunks) {
                if (success) {
                    LOG.info("[WindowEventHandler] Compact session succeeded");
                    callJavaScript("onCompactSuccess", "");
                } else {
                    LOG.error("[WindowEventHandler] Compact session failed (success=false)");
                    callJavaScript("onCompactError", "");
                }
            }
        });
    }

    private interface ParamsBuilder {
        com.google.gson.JsonObject build();
    }

    private interface ResponseHandler {
        default void onError(String error) {}
        void onComplete(boolean success, java.util.List<String> chunks);
    }

    private void requestDaemonJson(String command, ParamsBuilder params, ResponseHandler handler) {
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
                    handler.onError(error);
                }

                @Override
                public void onComplete(boolean success) {
                    handler.onComplete(success, chunks);
                }
            });
        } catch (Exception e) {
            LOG.error("[WindowEventHandler] opencode." + command + " request failed", e);
            handler.onError(e.getMessage());
        }
    }

    private String sessionIdOrEmpty() {
        com.opencodebuddy.session.OpencodeSession session = context.getSession();
        String id = session != null ? session.getSessionId() : null;
        return id != null ? id : "";
    }

    private com.google.gson.JsonObject singleSessionParams(String sessionId) {
        String id = sessionId != null && !sessionId.isBlank() ? sessionId : sessionIdOrEmpty();
        com.google.gson.JsonObject params = new com.google.gson.JsonObject();
        params.addProperty("sessionId", id);
        String cwd = context.resolveEffectiveWorkingDirectory();
        if (cwd != null && !cwd.isBlank()) {
            params.addProperty("directory", cwd);
        }
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
