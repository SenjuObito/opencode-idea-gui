package com.opencodebuddy.handler;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.opencodebuddy.handler.core.HandlerContext;
import com.opencodebuddy.handler.history.HistoryLoadService;
import com.opencodebuddy.session.OpencodeSession;
import com.opencodebuddy.session.SessionState;
import com.opencodebuddy.tab.TabCommand;
import com.opencodebuddy.tab.TabEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The tab-scoped session commands: fork, share/unshare, revert/unrevert and
 * compact.
 *
 * <p>These used to be window-level handlers, so each one resolved its target
 * through {@code "the active tab"} whenever the payload did not name a session.
 * A command that arrived while the user was switching tabs, or that was queued
 * behind another command, could therefore operate on a conversation the user
 * was not looking at.</p>
 *
 * <p>They are now dispatched by {@link com.opencodebuddy.tab.TabCommandRouter}:
 * the router resolves the tab that sent the command and runs the handler on
 * that tab's actor. The handler treats that tab as the only owner of the
 * command; it never falls back to the active tab. Every event it publishes also
 * names the session and the tab, so a result that arrives while another tab is
 * on screen still lands on the tab that asked for it.</p>
 */
public final class TabSessionCommandHandler {

    private static final Logger LOG = Logger.getInstance(TabSessionCommandHandler.class);

    /** The window side of a fork: bind the new session to the reserved tab. */
    public interface Callback {
        void onForkBound(String targetTabId, String newSessionId);
    }

    /** The session a command is allowed to touch. */
    private record Target(OpencodeSession session, String sessionId, String tabId, String error) {
        static Target error(String tabId, String error) {
            return new Target(null, null, tabId, error);
        }

        boolean usable() {
            return error == null;
        }
    }

    private final Supplier<HandlerContext> contextSupplier;
    private final Callback callback;

    public TabSessionCommandHandler(HandlerContext context, Callback callback) {
        this(() -> context, callback);
    }

    public TabSessionCommandHandler(Supplier<HandlerContext> contextSupplier, Callback callback) {
        this.contextSupplier = contextSupplier;
        this.callback = callback != null ? callback : (tabId, sessionId) -> {
        };
    }

    // ── Fork ─────────────────────────────────────────────────────────────

    /**
     * Fork the session of the routed tab into the reserved target tab.
     *
     * <p>The source is the command's tab, never the payload's {@code sourceTabId}
     * and never the active tab: the router already decided which conversation
     * asked for the fork.</p>
     */
    public void fork(TabCommand command) {
        HandlerContext context = context();
        String sourceTabId = command.tabId().value();
        if (context == null) {
            publishForkFailure(null, sourceTabId, null, "the window is not ready");
            return;
        }

        String payloadSource = command.string("sourceTabId");
        if (payloadSource != null && !payloadSource.isBlank() && !payloadSource.equals(sourceTabId)) {
            LOG.warn("[fork] payload source " + payloadSource + " differs from the routed tab "
                    + sourceTabId + "; using the routed tab");
        }

        String targetTabId = command.string("targetTabId");
        if (targetTabId == null || targetTabId.isBlank()) {
            publishForkFailure(context, sourceTabId, null, "the fork must name its target tab");
            return;
        }

        OpencodeSession source = context.getSessionForTab(sourceTabId);
        String sourceSessionId = source != null ? source.getSessionId() : null;
        if (source == null || isBlank(sourceSessionId)) {
            publishForkFailure(context, sourceTabId, sourceSessionId, "the source tab has no session to fork");
            return;
        }

        String messageId = command.string("messageId");
        if (!isBlank(messageId)) {
            withMessageId(context, sourceSessionId, messageId, "fork",
                    resolvedId -> requestFork(context, sourceSessionId, resolvedId, sourceTabId, targetTabId),
                    error -> publishForkFailure(context, sourceTabId, sourceSessionId, error));
            return;
        }
        requestFork(context, sourceSessionId, null, sourceTabId, targetTabId);
    }

    private void requestFork(
            HandlerContext context,
            String sourceSessionId,
            String messageId,
            String sourceTabId,
            String targetTabId
    ) {
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sourceSessionId);
        if (!isBlank(messageId)) {
            params.addProperty("messageID", messageId);
        }
        addDirectory(context, params);

        requestDaemonJson(context, "fork", () -> params, new ResponseHandler() {
            @Override
            public void onError(String error) {
                LOG.error("[fork] Failed to fork session: " + error);
                publishForkFailure(context, sourceTabId, sourceSessionId, error);
            }

            @Override
            public void onComplete(boolean success, List<String> chunks) {
                if (!success) {
                    LOG.error("[fork] Fork session failed (success=false)");
                    publishForkFailure(context, sourceTabId, sourceSessionId, "");
                    return;
                }
                String newSessionId = extractStringField(chunks, "newSessionId");
                LOG.info("[fork] newSessionId=\"" + newSessionId + "\"");
                if (newSessionId.isBlank()) {
                    publishForkFailure(context, sourceTabId, sourceSessionId, "");
                    return;
                }
                registerForkedSession(context, newSessionId);
                callback.onForkBound(targetTabId, newSessionId);
                context.publishEvent(TabEvent.of("session.fork.bound")
                        .session(newSessionId)
                        .tab(targetTabId)
                        .field("args", List.of(targetTabId, newSessionId, sourceTabId, sourceSessionId != null ? sourceSessionId : ""))
                        .field("sourceTabId", sourceTabId)
                        .field("sourceSessionId", sourceSessionId != null ? sourceSessionId : "")
                        .build());
            }
        });
    }

    private void publishForkFailure(
            HandlerContext context,
            String sourceTabId,
            String sourceSessionId,
            String error
    ) {
        if (context == null) {
            return;
        }
        context.publishEvent(TabEvent.of("session.fork.failed")
                .session(sourceSessionId)
                .tab(sourceTabId)
                .field("data", error != null ? error : "")
                .build());
    }

    /** Refresh the history list so the forked session appears immediately. */
    private static void registerForkedSession(HandlerContext context, String newSessionId) {
        try {
            new HistoryLoadService(context).handleLoadHistoryData("opencode");
        } catch (Exception e) {
            LOG.warn("[fork] Failed to reload history after fork " + newSessionId + ": " + e.getMessage());
        }
    }

    // ── Share / unshare ──────────────────────────────────────────────────

    public void share(TabCommand command) {
        HandlerContext context = context();
        Target target = resolve(context, command);
        if (!target.usable()) {
            publishShareFailure(context, target, target.error());
            return;
        }
        requestDaemonJson(context, "shareSession", () -> sessionParams(context, target.sessionId()),
                new ResponseHandler() {
                    @Override
                    public void onError(String error) {
                        publishShareFailure(context, target, error);
                    }

                    @Override
                    public void onComplete(boolean success, List<String> chunks) {
                        if (!success) {
                            publishShareFailure(context, target, "");
                            return;
                        }
                        String url = extractShareUrl(chunks);
                        if (url.isEmpty()) {
                            publishShareFailure(context, target, "");
                            return;
                        }
                        context.publishEvent(TabEvent.of("session.share.done")
                                .session(target.sessionId())
                                .tab(target.tabId())
                                .field("data", url)
                                .build());
                    }
                });
    }

    public void unshare(TabCommand command) {
        HandlerContext context = context();
        Target target = resolve(context, command);
        if (!target.usable()) {
            publishUnshareFailure(context, target, target.error());
            return;
        }
        requestDaemonJson(context, "unshareSession", () -> sessionParams(context, target.sessionId()),
                new ResponseHandler() {
                    @Override
                    public void onError(String error) {
                        publishUnshareFailure(context, target, error);
                    }

                    @Override
                    public void onComplete(boolean success, List<String> chunks) {
                        if (!success) {
                            publishUnshareFailure(context, target, "");
                            return;
                        }
                        context.publishEvent(TabEvent.of("session.unshare.done")
                                .session(target.sessionId())
                                .tab(target.tabId())
                                .field("data", "")
                                .build());
                    }
                });
    }

    private void publishShareFailure(HandlerContext context, Target target, String error) {
        publishFailure(context, "session.share.failed", target, error);
    }

    private void publishUnshareFailure(HandlerContext context, Target target, String error) {
        publishFailure(context, "session.unshare.failed", target, error);
    }

    // ── Revert / unrevert ────────────────────────────────────────────────

    public void revert(TabCommand command) {
        HandlerContext context = context();
        Target target = resolve(context, command);
        if (!target.usable()) {
            publishRevertFailure(context, target, "undo", target.error());
            return;
        }
        String messageId = command.string("messageId");
        if (messageId == null) {
            messageId = command.string("messageID");
        }
        final String requestedMessageId = messageId;
        LOG.info("[revert] request messageId=\"" + requestedMessageId + "\" sessionId=\""
                + target.sessionId() + "\" tab=\"" + target.tabId() + "\"");
        withMessageId(context, target.sessionId(), requestedMessageId, "revert",
                resolvedId -> {
                    JsonObject params = sessionParams(context, target.sessionId());
                    params.addProperty("messageID", resolvedId);
                    requestDaemonJson(context, "revert", () -> params, new ResponseHandler() {
                        @Override
                        public void onError(String error) {
                            LOG.error("[revert] Failed to revert session: " + error);
                            publishRevertFailure(context, target, "undo", error);
                        }

                        @Override
                        public void onComplete(boolean success, List<String> chunks) {
                            if (!success) {
                                LOG.error("[revert] Revert session failed (success=false)");
                                publishRevertFailure(context, target, "undo", "");
                                return;
                            }
                            pushRevertStateFromResponse(context, target, joinLines(chunks), resolvedId);
                        }
                    });
                },
                error -> publishRevertFailure(context, target, "undo", error));
    }

    public void unrevert(TabCommand command) {
        HandlerContext context = context();
        Target target = resolve(context, command);
        if (!target.usable()) {
            publishRevertFailure(context, target, "redo", target.error());
            return;
        }
        LOG.info("[unrevert] request sessionId=\"" + target.sessionId() + "\" tab=\"" + target.tabId() + "\"");
        requestDaemonJson(context, "unrevert", () -> sessionParams(context, target.sessionId()),
                new ResponseHandler() {
                    @Override
                    public void onError(String error) {
                        LOG.error("[unrevert] Failed to unrevert session: " + error);
                        publishRevertFailure(context, target, "redo", error);
                    }

                    @Override
                    public void onComplete(boolean success, List<String> chunks) {
                        if (!success) {
                            LOG.error("[unrevert] Unrevert session failed (success=false)");
                            publishRevertFailure(context, target, "redo", "");
                            return;
                        }
                        pushRevertStateFromResponse(context, target, joinLines(chunks), null);
                    }
                });
    }

    private void pushRevertStateFromResponse(
            HandlerContext context,
            Target target,
            String raw,
            String fallbackMessageId
    ) {
        JsonObject payload = extractJsonObject(List.of(raw));
        JsonObject sessionObj = payload != null && payload.has("session") && payload.get("session").isJsonObject()
                ? payload.getAsJsonObject("session") : null;
        JsonObject revertObj = null;
        if (payload != null) {
            if (payload.has("revert") && payload.get("revert").isJsonObject()) {
                revertObj = payload.getAsJsonObject("revert");
            } else if (sessionObj != null && sessionObj.has("revert") && sessionObj.get("revert").isJsonObject()) {
                revertObj = sessionObj.getAsJsonObject("revert");
            } else if (payload.has("messageID") && !payload.get("messageID").isJsonNull()) {
                revertObj = payload;
            } else if (sessionObj != null && sessionObj.has("messageID") && !sessionObj.get("messageID").isJsonNull()) {
                revertObj = sessionObj;
            }
        }

        String messageId = null;
        if (revertObj != null) {
            if (revertObj.has("messageID") && !revertObj.get("messageID").isJsonNull()) {
                messageId = revertObj.get("messageID").getAsString();
            } else if (revertObj.has("messageId") && !revertObj.get("messageId").isJsonNull()) {
                messageId = revertObj.get("messageId").getAsString();
            } else if (revertObj.has("id") && !revertObj.get("id").isJsonNull()) {
                messageId = revertObj.get("id").getAsString();
            }
        }
        if (isBlank(messageId) && !isBlank(fallbackMessageId)) {
            messageId = fallbackMessageId;
        }

        boolean hasRevert = fallbackMessageId != null
                || (revertObj != null && !isBlank(messageId));
        LOG.info("[revert-state] sessionId=" + target.sessionId() + " hasRevert=" + hasRevert
                + " messageId=" + messageId + " tab=" + target.tabId());

        // The pointer belongs to the session the command named, not to the
        // session that happens to be on screen.
        if (target.session() != null) {
            target.session().setRevertState(hasRevert
                    ? new SessionState.RevertState(messageId != null ? messageId : "")
                    : null);
        }

        JsonObject out = new JsonObject();
        out.addProperty("hasRevert", hasRevert);
        if (!isBlank(messageId)) {
            out.addProperty("messageId", messageId);
        } else {
            out.add("messageId", com.google.gson.JsonNull.INSTANCE);
        }
        context.publishEvent(TabEvent.of("revert.changed")
                .session(target.sessionId())
                .tab(target.tabId())
                .json("state", out.toString())
                .build());

        var app = com.intellij.openapi.application.ApplicationManager.getApplication();
        if (app != null) {
            app.invokeLater(() -> {
                try {
                    var vfs = com.intellij.openapi.vfs.VirtualFileManager.getInstance();
                    if (vfs != null) {
                        vfs.asyncRefresh(null);
                    }
                } catch (Throwable ignored) {
                }
            });
        }
    }

    private void publishRevertFailure(HandlerContext context, Target target, String op, String error) {
        JsonObject payload = new JsonObject();
        payload.addProperty("op", op);
        if (error != null) {
            payload.addProperty("error", error);
        }
        publishFailure(context, "session.revert.failed", target, payload.toString());
    }

    // ── Compact ──────────────────────────────────────────────────────────

    public void compact(TabCommand command) {
        HandlerContext context = context();
        Target target = resolve(context, command);
        if (!target.usable()) {
            publishCompactFailure(context, target, target.error());
            return;
        }
        String model = command.string("model");
        final String resolvedModel = !isBlank(model) ? model.trim() : null;
        AtomicBoolean errorReported = new AtomicBoolean(false);
        requestDaemonJson(context, "summarize",
                () -> sessionParams(context, target.sessionId(), resolvedModel),
                new ResponseHandler() {
                    @Override
                    public void onError(String error) {
                        if (!errorReported.compareAndSet(false, true)) {
                            return;
                        }
                        LOG.warn("[compact] Compact session failed: " + error);
                        publishCompactFailure(context, target, error);
                    }

                    @Override
                    public void onComplete(boolean success, List<String> chunks) {
                        if (!success) {
                            if (!errorReported.compareAndSet(false, true)) {
                                return;
                            }
                            LOG.warn("[compact] Compact session failed (success=false)");
                            publishCompactFailure(context, target, "");
                            return;
                        }
                        LOG.info("[compact] Compact session succeeded: " + target.sessionId());
                        new UsagePushService(context).refreshContextBar();
                        context.publishEvent(TabEvent.of("session.compact.done")
                                .session(target.sessionId())
                                .tab(target.tabId())
                                .field("data", target.sessionId())
                                .build());
                    }
                });
    }

    private void publishCompactFailure(HandlerContext context, Target target, String error) {
        if (context == null) {
            return;
        }
        context.publishEvent(TabEvent.of("session.compact.failed")
                .session(target.sessionId())
                .tab(target.tabId())
                .field("args", List.of(
                        target.sessionId() != null ? target.sessionId() : "",
                        error != null ? error : ""))
                .build());
    }

    // ── Ownership ────────────────────────────────────────────────────────

    /**
     * Resolve the session this command may act on.
     *
     * <p>Two checks, both required:</p>
     * <ol>
     *   <li>the tab holds a session (a command on an empty tab has no target);</li>
     *   <li>the session the sender named — the envelope session id, copied into
     *       the payload by {@code HostCommand} — still belongs to that tab. A
     *       queued command for a session the tab has since switched away from
     *       must not touch the new conversation.</li>
     * </ol>
     */
    private Target resolve(HandlerContext context, TabCommand command) {
        String tabId = command.tabId().value();
        if (context == null) {
            return Target.error(tabId, "the window is not ready");
        }
        OpencodeSession session = context.getSessionForTab(tabId);
        if (session == null) {
            return Target.error(tabId, "the tab has no session");
        }
        String held = session.getSessionId();
        String requested = command.string("sessionId");
        if (!isBlank(requested) && !isBlank(held) && !requested.equals(held)) {
            LOG.warn("[TabSessionCommand] " + command.type() + " names " + requested
                    + " but tab " + tabId + " now holds " + held + "; refusing");
            return Target.error(tabId, "the tab now shows another session");
        }
        if (isBlank(held)) {
            return Target.error(tabId, "the tab has no canonical session");
        }
        return new Target(session, held, tabId, null);
    }

    private void publishFailure(HandlerContext context, String type, Target target, String error) {
        if (context == null) {
            return;
        }
        context.publishEvent(TabEvent.of(type)
                .session(target.sessionId())
                .tab(target.tabId())
                .field("data", error != null ? error : "")
                .build());
    }

    // ── Shared plumbing ──────────────────────────────────────────────────

    private HandlerContext context() {
        return contextSupplier != null ? contextSupplier.get() : null;
    }

    private static JsonObject sessionParams(HandlerContext context, String sessionId) {
        return sessionParams(context, sessionId, null);
    }

    private static JsonObject sessionParams(HandlerContext context, String sessionId, String model) {
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId);
        addDirectory(context, params);
        if (!isBlank(model)) {
            params.addProperty("model", model);
        }
        return params;
    }

    private static void addDirectory(HandlerContext context, JsonObject params) {
        String cwd = context.resolveEffectiveWorkingDirectory();
        if (!isBlank(cwd)) {
            params.addProperty("directory", cwd);
        }
    }

    private void withMessageId(
            HandlerContext context,
            String sessionId,
            String messageId,
            String label,
            Consumer<String> run,
            Consumer<String> onError
    ) {
        if (!isBlank(messageId) && !"latest".equals(messageId.trim())) {
            run.accept(messageId.trim());
            return;
        }
        LOG.info("[" + label + "] messageId empty or latest — resolving latest user message via listMessages, sessionId="
                + sessionId);
        if (context.getOpenCodeSDKBridge() == null) {
            if (onError != null) {
                onError.accept("the daemon bridge is not available");
            }
            return;
        }
        String cwd = context.resolveEffectiveWorkingDirectory();
        context.getOpenCodeSDKBridge().listMessages(sessionId, cwd).whenComplete((element, error) -> {
            if (error != null || element == null || !element.isJsonArray()) {
                String errorMsg = error != null ? error.getMessage() : "empty result";
                LOG.warn("[" + label + "] listMessages failed — abort: " + errorMsg);
                if (onError != null) {
                    onError.accept(errorMsg);
                }
                return;
            }
            String latestUserId = null;
            for (JsonElement el : element.getAsJsonArray()) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject entry = el.getAsJsonObject();
                JsonObject info = entry.has("info") && entry.get("info").isJsonObject()
                        ? entry.getAsJsonObject("info") : entry;
                String role = info.has("role") && !info.get("role").isJsonNull()
                        ? info.get("role").getAsString()
                        : (entry.has("role") && !entry.get("role").isJsonNull()
                                ? entry.get("role").getAsString() : "");
                if ("user".equals(role)) {
                    String id = null;
                    if (info.has("id") && !info.get("id").isJsonNull()) {
                        id = info.get("id").getAsString();
                    } else if (entry.has("id") && !entry.get("id").isJsonNull()) {
                        id = entry.get("id").getAsString();
                    }
                    if (!isBlank(id)) {
                        latestUserId = id;
                    }
                }
            }
            LOG.info("[" + label + "] resolved latest user message id="
                    + (latestUserId != null ? latestUserId : "(none)"));
            if (latestUserId != null) {
                run.accept(latestUserId);
            } else if (onError != null) {
                onError.accept("no user message found");
            }
        });
    }

    private interface ParamsBuilder {
        JsonObject build();
    }

    private interface ResponseHandler {
        default void onError(String error) {
        }

        void onComplete(boolean success, List<String> chunks);
    }

    private void requestDaemonJson(
            HandlerContext context,
            String command,
            ParamsBuilder params,
            ResponseHandler handler
    ) {
        if (context == null || context.getOpenCodeSDKBridge() == null) {
            handler.onError("the daemon bridge is not available");
            return;
        }
        try {
            List<String> chunks = new ArrayList<>();
            context.getOpenCodeSDKBridge().request("opencode." + command, params.build(),
                    new com.opencodebuddy.provider.common.DaemonBridge.DaemonOutputCallback() {
                        private boolean errorFired = false;

                        @Override
                        public void onLine(String line) {
                            chunks.add(line);
                        }

                        @Override
                        public void onStderr(String text) {
                        }

                        @Override
                        public void onError(String error) {
                            errorFired = true;
                            LOG.warn("[TabSessionCommand] opencode." + command + " failed: " + error);
                            handler.onError(error);
                        }

                        @Override
                        public void onComplete(boolean success) {
                            if (!success && errorFired) {
                                return;
                            }
                            handler.onComplete(success, chunks);
                        }
                    });
        } catch (Exception e) {
            LOG.error("[TabSessionCommand] opencode." + command + " request failed", e);
            handler.onError(e.getMessage());
        }
    }

    private static String joinLines(List<String> chunks) {
        return String.join("\n", chunks);
    }

    private static String extractStringField(List<String> chunks, String field) {
        JsonObject obj = extractJsonObject(chunks);
        if (obj != null && obj.has(field) && !obj.get(field).isJsonNull()) {
            return obj.get(field).getAsString();
        }
        return "";
    }

    private static String extractShareUrl(List<String> chunks) {
        JsonObject obj = extractJsonObject(chunks);
        if (obj == null) {
            return "";
        }
        if (obj.has("url") && !obj.get("url").isJsonNull()) {
            return obj.get("url").getAsString();
        }
        if (obj.has("share") && obj.get("share").isJsonObject()) {
            JsonObject share = obj.getAsJsonObject("share");
            if (share.has("url") && !share.get("url").isJsonNull()) {
                return share.get("url").getAsString();
            }
            if (share.has("share") && share.get("share").isJsonObject()) {
                JsonObject nested = share.getAsJsonObject("share");
                if (nested.has("url") && !nested.get("url").isJsonNull()) {
                    return nested.get("url").getAsString();
                }
            }
        }
        return "";
    }

    private static JsonObject extractJsonObject(List<String> chunks) {
        for (int i = chunks.size() - 1; i >= 0; i--) {
            String trimmed = chunks.get(i).trim();
            if (trimmed.startsWith("{")) {
                try {
                    JsonElement el = JsonParser.parseString(trimmed);
                    if (el.isJsonObject()) {
                        return el.getAsJsonObject();
                    }
                } catch (RuntimeException ignored) {
                }
            }
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
