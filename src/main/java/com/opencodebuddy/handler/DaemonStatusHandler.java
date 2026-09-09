package com.opencodebuddy.handler;

import com.opencodebuddy.handler.core.BaseMessageHandler;
import com.opencodebuddy.handler.core.HandlerContext;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.opencodebuddy.utils.PluginFileLogger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pushes daemon/serve status to the webview ({@code window.updateDaemonStatus}).
 *
 * <p>The webview shows a permanent "starting OpenCode service" spinner until it
 * receives {@code {alive:true, serveReady:true}} (see useUsageTracking.ts), and
 * already sends {@code check_daemon_status:} from its retry button. This handler
 * mirrors the vscode-plugin's {@code WindowEventHandler.sendDaemonStatus()}:</p>
 * <ol>
 *   <li>if the daemon is already confirmed ready, re-affirm {@code {alive:true,
 *       serveReady:true}} so a re-check never regresses the UI back to loading;</li>
 *   <li>otherwise immediately push the current daemon state ({@code serveReady:false}),</li>
 *   <li>asynchronously preconnect (daemon starts {@code opencode serve} + SSE),</li>
 *   <li>on success push {@code {alive:true, serveReady:true}} — the spinner
 *       clears; on failure leave the "not running / retry" state.</li>
 * </ol>
 *
 * <p>The owner (ChatWindowDelegate) additionally invokes {@link #checkAndPush()}
 * on frontend_ready and forwards the daemon bridge's lifecycle callbacks
 * (onDaemonReady / onDaemonDied) here so a daemon death flips the webview to
 * the retryable state.</p>
 */
public class DaemonStatusHandler extends BaseMessageHandler {

    private static final Logger LOG = Logger.getInstance(DaemonStatusHandler.class);

    private static final String[] SUPPORTED_TYPES = {
            "check_daemon_status",
    };

    /** Upper bound for one preconnect (daemon start + serve start + SSE subscribe). */
    private static final long PRECONNECT_TIMEOUT_SECONDS = 60L;

    private final Gson gson = new Gson();
    /** Dedups concurrent preconnect pushes; status pushes themselves are idempotent. */
    private final AtomicBoolean preconnectInFlight = new AtomicBoolean(false);
    /** Last status we actually pushed, so re-checks don't regress an already-ready UI. */
    private volatile boolean lastAlive;
    private volatile boolean lastServeReady;
    /** Strategy for reaching the daemon; overridable in tests. */
    private final DaemonStatusProbe probe;

    public DaemonStatusHandler(HandlerContext context) {
        this(context, null);
    }

    DaemonStatusHandler(HandlerContext context, DaemonStatusProbe probe) {
        super(context);
        this.probe = probe != null ? probe : new SdkBridgeProbe(context);
    }

    /** Narrow seam the handler needs from the daemon layer. */
    interface DaemonStatusProbe {
        boolean isDaemonAlive();

        CompletableFuture<Boolean> preconnect(String sessionId, String cwd);
    }

    /** Production probe delegating to the shared OpenCodeSDKBridge. */
    private static final class SdkBridgeProbe implements DaemonStatusProbe {
        private final HandlerContext context;

        SdkBridgeProbe(HandlerContext context) {
            this.context = context;
        }

        @Override
        public boolean isDaemonAlive() {
            return context.getOpenCodeSDKBridge() != null
                    && context.getOpenCodeSDKBridge().isDaemonAlive();
        }

        @Override
        public CompletableFuture<Boolean> preconnect(String sessionId, String cwd) {
            return context.getOpenCodeSDKBridge().preconnect(sessionId, cwd);
        }
    }

    @Override
    public String[] getSupportedTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public boolean handle(String type, String content) {
        if (!"check_daemon_status".equals(type)) {
            return false;
        }
        checkAndPush();
        return true;
    }

    /**
     * Push the current status immediately, then refresh it via preconnect so a
     * successful serve start clears the webview's loading spinner.
     */
    public void checkAndPush() {
        boolean alive = probe.isDaemonAlive();
        PluginFileLogger.info("DAEMON", "checkAndPush invoked (alive=" + alive
                + ", lastServeReady=" + lastServeReady
                + ", preconnectInFlight=" + preconnectInFlight.get() + ")");
        if (alive && lastServeReady) {
            // Daemon already confirmed ready in a prior cycle (e.g. a status push
            // already delivered serveReady:true). Re-affirm it instead of regressing
            // the webview back to the "starting OpenCode service" loading state.
            pushStatus(true, true);
            return;
        }
        if (preconnectInFlight.get()) {
            // A preconnect is still determining serve status; pushing a false
            // placeholder here would clobber an already-correct state, and the
            // in-flight run already owns the next (and only) status push.
            PluginFileLogger.info("DAEMON", "checkAndPush deferred: preconnect already in flight");
            return;
        }
        // First determination (or after a death): show loading, then preconnect.
        pushStatus(alive, false);
        preconnectAndPush();
    }

    /** Daemon lifecycle callback: a fresh daemon is running but serve may lag. */
    public void onDaemonReady() {
        PluginFileLogger.info("DAEMON", "onDaemonReady callback");
        pushStatus(true, false);
        preconnectAndPush();
    }

    /** Daemon lifecycle callback: the daemon died — flip the webview to retryable. */
    public void onDaemonDied() {
        PluginFileLogger.warn("DAEMON", "onDaemonDied callback");
        pushStatus(false, false);
    }

    private void preconnectAndPush() {
        if (!preconnectInFlight.compareAndSet(false, true)) {
            // A preconnect is already running; this call pushes nothing. If the webview
            // became ready in between, the pending run still owns the only status push.
            PluginFileLogger.warn("DAEMON", "preconnectAndPush SKIPPED: another preconnect is in flight");
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                // preconnect starts the daemon (and `opencode serve`) when idle.
                CompletableFuture<Boolean> preconnect = probe.preconnect(
                        sessionIdOrEmpty(), context.resolveEffectiveWorkingDirectory());
                boolean serveReady;
                try {
                    serveReady = Boolean.TRUE.equals(
                            preconnect.get(PRECONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
                } catch (TimeoutException e) {
                    preconnect.cancel(true);
                    serveReady = false;
                }
                LOG.info("[DaemonStatus] preconnect finished: serveReady=" + serveReady);
                PluginFileLogger.info("DAEMON", "preconnect finished: serveReady=" + serveReady
                        + " (cwd=" + context.resolveEffectiveWorkingDirectory() + ")");
                if (serveReady) {
                    pushStatus(true, true);
                    // Serve is up — warm the model catalog in the background so
                    // the first model dropdown open is instant.
                    CliModelsHandler.warmupModelCache(context);
                } else {
                    // The webview has no "alive but serve broken" state: anything
                    // other than serveReady:true must leave the loading state and
                    // show the retryable "not running" row (vscode-plugin parity).
                    pushStatus(false, false);
                }
            } catch (Exception e) {
                LOG.warn("[DaemonStatus] preconnect failed: " + e.getMessage());
                PluginFileLogger.error("DAEMON", "preconnect failed: " + e.getMessage(), e);
                pushStatus(false, false);
            } finally {
                preconnectInFlight.set(false);
            }
        }, AppExecutorUtil.getAppExecutorService());
    }

    private String sessionIdOrEmpty() {
        com.opencodebuddy.session.ClaudeSession session = context.getSession();
        String id = session != null ? session.getSessionId() : null;
        return id != null ? id : "";
    }

    private void pushStatus(boolean alive, boolean serveReady) {
        lastAlive = alive;
        lastServeReady = serveReady;
        JsonObject payload = new JsonObject();
        payload.addProperty("alive", alive);
        payload.addProperty("serveReady", serveReady);
        String json = gson.toJson(payload);
        PluginFileLogger.info("DAEMON", "pushStatus " + json);
        callJavaScript("window.updateDaemonStatus", escapeJs(json));
    }
}
