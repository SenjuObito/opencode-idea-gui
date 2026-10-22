package com.opencodebuddy.session;

import com.opencodebuddy.handler.PermissionHandler;
import com.opencodebuddy.permission.PermissionRequest;
import com.opencodebuddy.util.JsUtils;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.util.Alarm;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Named implementation of OpencodeSession.SessionCallback.
 * Replaces the large anonymous inner class in setupSessionCallbacks().
 * Delegates streaming events to StreamMessageCoalescer and UI events to JavaScript callbacks.
 */
public class SessionCallbackAdapter implements OpencodeSession.SessionCallback {

    private static final Logger LOG = Logger.getInstance(SessionCallbackAdapter.class);
    /** Throttle interval targeting ~30fps to balance responsiveness with UI thread load. */
    private static final int DELTA_THROTTLE_MS = 33;

    /**
     * Callback interface for JavaScript calls from session events.
     */
    public interface JsTarget {
        void callJavaScript(String functionName, String... args);
    }

    private final StreamMessageCoalescer streamCoalescer;
    private final JsTarget jsTarget;
    private final PermissionHandler permissionHandler;
    private final BooleanSupplier slashCommandsFetchedSupplier;
    private final Runnable streamEndCallback;
    private final StreamDeltaThrottler contentDeltaThrottler;
    private final StreamDeltaThrottler thinkingDeltaThrottler;
    private final Alarm streamEndFallbackAlarm;
    private volatile boolean active = true;
    private volatile String sessionId;
    /** Stable UI tab that owns this adapter. Empty for legacy/session-only paths. */
    private final String tabId;
    /** Lock making deactivate() atomic with onMessageUpdate()'s active-check-then-enqueue. */
    private final Object lifecycleLock = new Object();
    /** Guards against duplicate onStreamEnd delivery from dual-path dispatch. */
    private volatile boolean streamEndSignalSent = false;
    /** One-shot warn when a call has to be dropped for lack of a session id. */
    private volatile boolean unattributedSessionWarned = false;

    /**
     * @param sessionId the opencode session this callback speaks for. It may be
     *     {@code null} only during the brief window before the daemon has
     *     reported the id of a brand-new session — and in that window every
     *     outgoing call is tagged with {@code null}, which the webview treats as
     *     "unattributable" and drops (with a counter). There is deliberately no
     *     default: an implicit {@code null} used to be indistinguishable from
     *     "we know the session is X", which is how output leaked into whatever
     *     tab happened to be active.
     */
    public SessionCallbackAdapter(
            String sessionId,
            StreamMessageCoalescer streamCoalescer,
            JsTarget jsTarget,
            PermissionHandler permissionHandler,
            BooleanSupplier slashCommandsFetchedSupplier,
            Runnable streamEndCallback
    ) {
        this(sessionId, streamCoalescer, jsTarget, permissionHandler,
                slashCommandsFetchedSupplier, streamEndCallback, null);
    }

    public SessionCallbackAdapter(
            String sessionId,
            StreamMessageCoalescer streamCoalescer,
            JsTarget jsTarget,
            PermissionHandler permissionHandler,
            BooleanSupplier slashCommandsFetchedSupplier,
            Runnable streamEndCallback,
            String tabId
    ) {
        this.sessionId = sessionId;
        this.tabId = tabId;
        this.streamCoalescer = streamCoalescer;
        // Propagate immediately: the coalescer stamps its own session id onto
        // every updateMessages it pushes, and an un-stamped snapshot is now
        // dropped by the frontend rather than adopted by the active tab.
        if (this.streamCoalescer != null && sessionId != null && !sessionId.isBlank()) {
            this.streamCoalescer.setSessionId(sessionId);
        }
        this.jsTarget = jsTarget;
        this.permissionHandler = permissionHandler;
        this.slashCommandsFetchedSupplier = slashCommandsFetchedSupplier;
        this.streamEndCallback = streamEndCallback;
        this.contentDeltaThrottler = new StreamDeltaThrottler(
                DELTA_THROTTLE_MS,
                delta -> {
                    if (!isInactive()) {
                        if (!hasIdentity()) {
                            warnUnattributedOnce("content delta");
                            return;
                        }
                        jsTarget.callJavaScript("onContentDelta", getTargetSessionId(), JsUtils.escapeJs(delta));
                    }
                }
        );
        this.thinkingDeltaThrottler = new StreamDeltaThrottler(
                DELTA_THROTTLE_MS,
                delta -> {
                    if (!isInactive()) {
                        if (!hasIdentity()) {
                            warnUnattributedOnce("thinking delta");
                            return;
                        }
                        jsTarget.callJavaScript("onThinkingDelta", getTargetSessionId(), JsUtils.escapeJs(delta));
                    }
                }
        );
        this.streamEndFallbackAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD);
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
        if (this.streamCoalescer != null) {
            this.streamCoalescer.setSessionId(sessionId);
        }
    }

    /**
     * This adapter's session identity — the tag on every outgoing call.
     *
     * <p>Never coerced to an empty string. The receiver distinguishes "unknown"
     * from "empty" and refuses to attribute output it cannot place; coercing to
     * {@code ""} here is precisely what made an un-attributable write land
     * silently in the active session.</p>
     *
     * @return the session id, or {@code null} before it has been resolved
     */
    /**
     * Whether this adapter can attribute its output to a session.
     *
     * <p>Un-attributable writes are <b>dropped</b> rather than delivered with a
     * blank id. A blank id used to be read by the frontend as "the session that
     * is currently on screen", so any output arriving before the id was known —
     * or after the owning session had been closed — landed in whichever tab
     * happened to be active. Dropping is the only behaviour that cannot
     * mis-route; the single warning makes the drop visible instead of silent.</p>
     */
    private boolean hasIdentity() {
        return sessionId != null && !sessionId.isBlank();
    }

    private void warnUnattributedOnce(String what) {
        if (!unattributedSessionWarned) {
            unattributedSessionWarned = true;
            LOG.warn("Dropping " + what + ": SessionCallbackAdapter has no session id yet, "
                    + "and guessing the active session is what caused cross-session leakage");
        }
    }

    private String getTargetSessionId() {
        if (!hasIdentity()) {
            warnUnattributedOnce("session-scoped callback");
            return null;
        }
        return sessionId;
    }

    public void deactivate() {
        synchronized (lifecycleLock) {
            active = false;
        }
        contentDeltaThrottler.dispose();
        thinkingDeltaThrottler.dispose();
        streamEndFallbackAlarm.cancelAllRequests();
    }

    private boolean isInactive() {
        return !active;
    }

    @Override
    public void onMessageUpdate(List<OpencodeSession.Message> messages) {
        // Atomic vs deactivate(): a stale-session reload landing mid-transition
        // must not enqueue with a post-barrier sequence and resurrect the cleared list.
        synchronized (lifecycleLock) {
            if (!active) {
                return;
            }
            streamCoalescer.enqueue(messages);
        }
    }

    @Override
    public void onStateChange(boolean busy, boolean loading, String error) {
        if (isInactive()) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            if (isInactive()) {
                return;
            }
            // No "suppress loading=false while streaming" guard here any more.
            // It existed because the turn's terminal signal was unreliable, so a
            // premature clearing had to be blocked. The daemon now emits an
            // explicit, per-session, idempotent [TURN_END], and this session's
            // callback is the only writer of its own loading state — so a
            // loading=false that arrives here is authoritative and must be
            // delivered, not deferred to a stream end that may never come.
            if (!hasIdentity()) {
                warnUnattributedOnce("loading state");
                return;
            }
            jsTarget.callJavaScript("showLoading", getTargetSessionId(), String.valueOf(loading));
            // The primary error display is the ERROR message in chat list and StatusNoticeBar banner.
            // Do not call updateStatus here to avoid redundant notifications/toasts.
            if (!busy && !loading) {
                VirtualFileManager.getInstance().asyncRefresh(null);
            }
        });
    }

    @Override
    public void onStatusMessage(String message) {
        if (isInactive() || message == null || message.trim().isEmpty()) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            if (isInactive() || !hasIdentity()) {
                if (!isInactive()) {
                    warnUnattributedOnce("status message");
                }
                return;
            }
            jsTarget.callJavaScript("updateStatus", getTargetSessionId(), JsUtils.escapeJs(message));
        });
    }

    @Override
    public void onRetryStatus(String jsonContent) {
        if (isInactive() || jsonContent == null || jsonContent.isBlank()) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            if (isInactive()) {
                return;
            }
            // Dedicated channel (not updateStatus): a retry notice persists and
            // carries a clickable action link, whereas updateStatus is a
            // fire-and-forget toast that cannot render one.
            jsTarget.callJavaScript("updateRetryStatus", getTargetSessionId(), JsUtils.escapeJs(jsonContent));
        });
    }

    @Override
    public void onSessionIdReceived(String sessionId) {
        if (isInactive()) {
            return;
        }
        String oldSessionId = this.sessionId;
        this.sessionId = sessionId;
        if (this.streamCoalescer != null) {
            this.streamCoalescer.setSessionId(sessionId);
        }
        LOG.info("Session ID: " + sessionId + " (prev: " + oldSessionId + ")");
        ApplicationManager.getApplication().invokeLater(() -> {
            if (isInactive()) {
                return;
            }
            jsTarget.callJavaScript(
                    "setSessionId",
                    JsUtils.escapeJs(sessionId),
                    JsUtils.escapeJs(oldSessionId != null ? oldSessionId : ""),
                    JsUtils.escapeJs(tabId != null ? tabId : ""));
        });
    }

    @Override
    public void onSessionTitleReceived(String sessionId, String title) {
        if (isInactive() || sessionId == null || title == null) {
            return;
        }
        LOG.info("Session Title update: " + sessionId + " -> " + title);
        ApplicationManager.getApplication().invokeLater(() -> {
            if (isInactive()) {
                return;
            }
            jsTarget.callJavaScript("updateSessionTitle", JsUtils.escapeJs(sessionId), JsUtils.escapeJs(title));
        });
    }

    @Override
    public void onPermissionRequested(PermissionRequest request) {
        if (isInactive()) {
            return;
        }
        // Prefer the session carried by the request itself: it came off the wire
        // with the prompt, so it is correct even if this adapter's identity has
        // not caught up. Only fall back to our own id — never to "the active
        // session" — when the payload genuinely omits it.
        String requestSessionId = request != null ? request.getSessionId() : null;
        final String targetSessionId = (requestSessionId != null && !requestSessionId.isBlank())
                ? requestSessionId
                : getTargetSessionId();
        ApplicationManager.getApplication().invokeLater(() -> {
            if (isInactive()) {
                return;
            }
            permissionHandler.showPermissionDialog(request, targetSessionId);
        });
    }

    @Override
    public void onThinkingStatusChanged(boolean isThinking) {
        if (isInactive()) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            if (isInactive() || !hasIdentity()) {
                if (!isInactive()) {
                    warnUnattributedOnce("thinking status");
                }
                return;
            }
            jsTarget.callJavaScript("showThinkingStatus", getTargetSessionId(), String.valueOf(isThinking));
            LOG.debug("Thinking status changed: " + isThinking);
        });
    }

    @Override
    public void onSlashCommandsReceived(List<String> slashCommands) {
        // No longer send old-format (string array) commands to the frontend.
        // Reasons:
        // 1. The full command list (with descriptions) was already fetched from getSlashCommands() during init.
        // 2. The commands received here are in old format (names only, no descriptions).
        // 3. Sending to frontend would overwrite the full command list, losing descriptions.
        int incomingCount = slashCommands != null ? slashCommands.size() : 0;
        LOG.debug("onSlashCommandsReceived called (old format, ignored). incoming=" + incomingCount);

        if (slashCommands != null && !slashCommands.isEmpty() && !slashCommandsFetchedSupplier.getAsBoolean()) {
            LOG.debug("Received " + incomingCount + " slash commands (old format), but keeping existing commands with descriptions");
        }
    }

    @Override
    public void onSummaryReceived(String summary) {
        LOG.debug("Summary received: " + (summary != null ? summary.substring(0, Math.min(50, summary.length())) : "null"));
        if (isInactive() || summary == null || summary.trim().isEmpty()) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            if (isInactive() || !hasIdentity()) {
                if (!isInactive()) {
                    warnUnattributedOnce("summary");
                }
                return;
            }
            jsTarget.callJavaScript("showSummary", getTargetSessionId(), JsUtils.escapeJs(summary));
        });
    }

    @Override
    public void onNodeLog(String log) {
        LOG.debug("Node log: " + (log != null ? log.substring(0, Math.min(100, log.length())) : "null"));
    }

    // ===== Streaming callback methods =====

    @Override
    public void onStreamStart() {
        if (isInactive()) {
            return;
        }
        // Cancel any stale fallback alarm from the previous turn to prevent
        // it from firing during the new turn's streaming phase.
        streamEndFallbackAlarm.cancelAllRequests();
        contentDeltaThrottler.reset();
        thinkingDeltaThrottler.reset();
        streamCoalescer.onStreamStart();
        ApplicationManager.getApplication().invokeLater(() -> {
            if (isInactive() || !hasIdentity()) {
                if (!isInactive()) {
                    warnUnattributedOnce("stream start");
                }
                return;
            }
            jsTarget.callJavaScript("showLoading", getTargetSessionId(), "true");
            jsTarget.callJavaScript("onStreamStart", getTargetSessionId());
            LOG.debug("Stream started - notified frontend with loading=true");
        });
    }

    @Override
    public void onStreamEnd() {
        if (isInactive()) {
            return;
        }
        // Reset the signal guard so this turn's dual-path dispatch can proceed.
        // Thread-safety: this runs on the process reader thread; the callbacks that
        // read/write streamEndSignalSent all run on EDT (via invokeLater or Alarm).
        // The reset happens-before flush() schedules any callbacks, so no race exists.
        streamEndSignalSent = false;

        // Each step is wrapped in safeRun so that a failure in one step
        // (e.g., flushNow throwing due to a disposed throttler, or JCEF
        // rejecting a large payload) does not prevent the critical
        // onStreamEnd signal from reaching the frontend.
        safeRun("contentDeltaThrottler.flushNow", contentDeltaThrottler::flushNow);
        safeRun("thinkingDeltaThrottler.flushNow", thinkingDeltaThrottler::flushNow);
        safeRun("streamCoalescer.onStreamEnd", streamCoalescer::onStreamEnd);

        // ── Dual-path onStreamEnd delivery ──
        //
        // Primary path: chain onStreamEnd inside the flush callback. The callback
        // runs on the EDT *after* the updateMessages JS call has been dispatched,
        // guaranteeing the frontend receives the final message snapshot before the
        // stream-end signal.
        //
        // Fallback path: an independent Alarm fires after 300ms. This covers the
        // scenario where the flush's 3-layer async pipeline fails silently (JCEF
        // large payload rejection, disposed browser, JSON serialization OOM).
        //
        // The frontend's onStreamEnd is idempotent (per-turn guard), so receiving
        // both signals is harmless — only the first takes effect.

        // Primary: ordered delivery via flush callback
        streamCoalescer.flush(sequence -> {
            if (streamEndSignalSent) {
                return;
            }
            streamEndSignalSent = true;
            streamEndFallbackAlarm.cancelAllRequests();
            sendStreamEndToFrontend(sequence);
        });

        // Fallback: independent delivery after timeout
        streamEndFallbackAlarm.cancelAllRequests();
        streamEndFallbackAlarm.addRequest(() -> {
            if (streamEndSignalSent || isInactive()) {
                return;
            }
            streamEndSignalSent = true;
            LOG.warn("Stream end signal delivered via fallback (primary flush callback did not fire within 300ms)");
            sendStreamEndToFrontend(-1);
        }, 300);
    }

    /**
     * Send the onStreamEnd signal and associated cleanup to the frontend.
     * Called from either the primary (flush callback) or fallback (Alarm) path.
     *
     * @param sequence the flush sequence number, or -1 if fired from fallback
     */
    private void sendStreamEndToFrontend(long sequence) {
        if (isInactive()) {
            LOG.debug("Skipping sendStreamEndToFrontend — adapter deactivated (sequence=" + sequence + ")");
            return;
        }
        if (!hasIdentity()) {
            // Never emit a blank-id terminal: the frontend reads that as "the
            // active tab", which is how stopping one session cleared another's
            // spinner — or left this one spinning forever.
            warnUnattributedOnce("onStreamEnd");
            return;
        }
        safeRun("callJavaScript(onStreamEnd)", () ->
                jsTarget.callJavaScript("onStreamEnd", getTargetSessionId(), String.valueOf(sequence)));
        safeRun("callJavaScript(showLoading, false)", () ->
                jsTarget.callJavaScript("showLoading", getTargetSessionId(), "false"));
        if (streamEndCallback != null) {
            safeRun("streamEndCallback", streamEndCallback);
        }
        LOG.debug("Stream ended - notified frontend (sequence=" + sequence + ")");
    }

    private static void safeRun(String label, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            LOG.warn(label + " failed: " + e.getMessage(), e);
        }
    }

    /**
     * Forward the daemon's session-scoped turn terminal to the webview.
     *
     * <p>Sent to the session named by the payload — not to the active tab — so a
     * background session's spinner clears when its turn actually ends. The
     * webview keys this on {@code (sessionId, turnId)} and ignores repeats, which
     * is what makes it safe for this signal to arrive alongside (or instead of)
     * {@code onStreamEnd} and {@code showLoading(false)}.</p>
     */
    @Override
    public void onTurnEnd(String jsonContent) {
        if (isInactive() || jsonContent == null || jsonContent.isBlank()) {
            return;
        }
        if (!hasIdentity()) {
            warnUnattributedOnce("turn terminal");
            return;
        }
        final String targetSessionId = getTargetSessionId();
        ApplicationManager.getApplication().invokeLater(() -> {
            if (isInactive()) {
                return;
            }
            jsTarget.callJavaScript("onTurnEnd", targetSessionId, JsUtils.escapeJs(jsonContent));
        });
    }

    @Override
    public void onContentDelta(String delta) {
        if (isInactive()) {
            return;
        }
        contentDeltaThrottler.append(delta);
    }

    @Override
    public void onThinkingDelta(String delta) {
        if (isInactive()) {
            return;
        }
        thinkingDeltaThrottler.append(delta);
    }

    @Override
    public void onBlockReset() {
        if (isInactive()) {
            return;
        }
        // Flush BEFORE resetting: block boundaries now fire mid-response (one per
        // content-block edge, not just per tool-loop turn), so deltas buffered in
        // the throttlers belong to the ending block. reset() alone would silently
        // drop them and force the frontend to fall back to updateMessages snapshots.
        contentDeltaThrottler.flushNow();
        thinkingDeltaThrottler.flushNow();
        contentDeltaThrottler.reset();
        thinkingDeltaThrottler.reset();
        ApplicationManager.getApplication().invokeLater(() -> {
            if (isInactive()) {
                return;
            }
            jsTarget.callJavaScript("onBlockReset", getTargetSessionId());
            LOG.debug("Block reset sent to frontend - streaming refs cleared");
        });
    }

    @Override
    public void onUsageUpdate(int usedTokens, int maxTokens) {
        if (isInactive()) {
            return;
        }
        if (!hasIdentity()) {
            warnUnattributedOnce("usage update");
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            if (isInactive() || !hasIdentity()) {
                return;
            }
            int safeUsedTokens = normalizeUsageValue(usedTokens);
            int safeMaxTokens = normalizeUsageValue(maxTokens);
            double percentage = calculateUsagePercentage(safeUsedTokens, safeMaxTokens);
            // 用 Gson 而不是 String.format("%.2f")：后者按默认 locale 渲染，逗号小数点
            // 的地区（de/fr/ru…）会产出 {"percentage":12,34} 这种非法 JSON，前端
            // JSON.parse 抛错后整条推送被丢弃，用量环就永远停在 0%。对应 vscode
            // SessionCallbackAdapter 里的 JSON.stringify。
            JsonObject payload = new JsonObject();
            payload.addProperty("percentage", percentage);
            payload.addProperty("usedTokens", safeUsedTokens);
            payload.addProperty("maxTokens", safeMaxTokens);
            String json = payload.toString();
            jsTarget.callJavaScript("onUsageUpdate", getTargetSessionId(), JsUtils.escapeJs(json));
            LOG.debug("Usage update sent to frontend: " + safeUsedTokens + "/" + safeMaxTokens);
        });
    }

    /**
     * Keep usage counters non-negative before they cross the JavaScript bridge.
     * Malformed or incomplete SDK usage payloads must not produce negative values
     * in the context tooltip.
     */
    static int normalizeUsageValue(int value) {
        return Math.max(0, value);
    }

    /**
     * Calculate a bounded usage percentage for the context indicator.
     */
    static double calculateUsagePercentage(int usedTokens, int maxTokens) {
        if (maxTokens <= 0) {
            return 0.0;
        }
        double percentage = usedTokens * 100.0 / maxTokens;
        return Math.max(0.0, Math.min(100.0, percentage));
    }

    @Override
    public void onUserMessageUuidPatched(String content, String uuid) {
        if (isInactive()) {
            return;
        }
        jsTarget.callJavaScript("patchMessageUuid", getTargetSessionId(), JsUtils.escapeJs(content), JsUtils.escapeJs(uuid));
    }

    @Override
    public void onTaskEvent(String eventJson) {
        if (isInactive() || eventJson == null || eventJson.trim().isEmpty()) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            if (isInactive()) {
                return;
            }
            jsTarget.callJavaScript("onTaskEvent", getTargetSessionId(), JsUtils.escapeJs(eventJson));
        });
    }

    @Override
    public void onTodoUpdated(String jsonContent) {
        if (isInactive() || jsonContent == null || jsonContent.trim().isEmpty()) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            if (isInactive()) {
                return;
            }
            jsTarget.callJavaScript("onTodoUpdated", getTargetSessionId(), JsUtils.escapeJs(jsonContent));
        });
    }

    @Override
    public void onQuestionRequested(String jsonContent) {
        if (isInactive() || jsonContent == null || jsonContent.trim().isEmpty()) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            if (isInactive()) {
                return;
            }
            permissionHandler.onQuestionRequested(jsonContent, getTargetSessionId());
        });
    }

    @Override
    public void onPromptClosed(String kind, String jsonContent) {
        if (isInactive() || jsonContent == null || jsonContent.trim().isEmpty()) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            if (isInactive()) {
                return;
            }
            permissionHandler.onPromptClosed(kind, jsonContent);
        });
    }

    @Override
    public void onRevertStateUpdate(boolean hasRevert, String messageId) {
        if (isInactive()) {
            return;
        }
        com.google.gson.JsonObject payload = new com.google.gson.JsonObject();
        payload.addProperty("hasRevert", hasRevert);
        if (messageId != null && !messageId.isBlank()) {
            payload.addProperty("messageId", messageId);
        } else {
            payload.add("messageId", com.google.gson.JsonNull.INSTANCE);
        }
        final String jsonStr = payload.toString();
        ApplicationManager.getApplication().invokeLater(() -> {
            if (isInactive()) {
                return;
            }
            jsTarget.callJavaScript("onRevertStateUpdate", getTargetSessionId(), jsonStr);
        });
    }

    /**
     * Forward a server-side message deletion to the webview.
     *
     * <p>The payload is a bare JSON array of opencode message ids, matching the
     * shape the webview's {@code onMessagesRemoved} handler parses. It is sent
     * BEFORE the follow-up {@code updateMessages} snapshot, so the webview's list
     * has already shrunk by the time the (also shorter) snapshot lands — which is
     * what stops its shrink-protection from restoring the voided tail.</p>
     */
    @Override
    public void onMessagesRemoved(java.util.List<String> messageIds) {
        if (isInactive() || messageIds == null || messageIds.isEmpty()) {
            return;
        }
        com.google.gson.JsonArray payload = new com.google.gson.JsonArray();
        for (String id : messageIds) {
            if (id != null && !id.isBlank()) {
                payload.add(id);
            }
        }
        if (payload.isEmpty()) {
            return;
        }
        final String jsonStr = payload.toString();
        ApplicationManager.getApplication().invokeLater(() -> {
            if (isInactive()) {
                return;
            }
            jsTarget.callJavaScript("onMessagesRemoved", getTargetSessionId(), jsonStr);
        });
    }

    /**
     * Dispose internal resources. Call when the parent window is disposed.
     */
    public void dispose() {
        deactivate();
        try {
            streamEndFallbackAlarm.dispose();
        } catch (Exception e) {
            LOG.warn("Failed to dispose streamEndFallbackAlarm: " + e.getMessage());
        }
    }
}
