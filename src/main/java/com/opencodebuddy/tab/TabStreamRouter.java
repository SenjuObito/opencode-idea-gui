package com.opencodebuddy.tab;

import com.google.gson.Gson;
import com.opencodebuddy.provider.common.DaemonBridge;
import com.opencodebuddy.provider.common.SDKResult;
import com.opencodebuddy.provider.opencode.OpenCodeMarkerParser;
import com.opencodebuddy.session.CallbackHandler;
import com.opencodebuddy.session.OpenCodeMessageHandler;
import com.opencodebuddy.session.SessionCallbackAdapter;
import com.opencodebuddy.session.SessionState;
import com.opencodebuddy.session.StreamMessageCoalescer;
import com.intellij.openapi.diagnostic.Logger;

/**
 * Per-session owner of the daemon output pipeline (data plane).
 *
 * <p>One instance per {@link OpencodeSession}. The daemon tags every output line
 * with the session it belongs to, and {@link DaemonBridge} hands the line
 * straight to that session's router — so a marker is parsed once and dispatched
 * to exactly one session, no matter how many sessions stream concurrently.</p>
 *
 * <p>Before this existed, the line was routed by request id and the parser state
 * lived in the {@code opencode.send} call site, which made "which session owns
 * this line" a function of which request happened to be in flight. Lines that
 * arrived when no request was in flight (a title update, a todo update, a
 * server-side prompt close, {@code message.removed}, a late error) had nowhere
 * to go and were dropped.</p>
 *
 * <p>Lifetime: created with the session, released when the tab closes. The
 * callback it owns is deliberately long-lived — {@link OpenCodeMessageHandler}
 * resets its per-turn accumulators on every {@code [MESSAGE_START]}, so a
 * session-wide instance behaves identically to a per-turn one while also being
 * able to serve out-of-turn events.</p>
 */
public final class TabStreamRouter implements DaemonBridge.SessionLineSink {

    private static final Logger LOG = Logger.getInstance(TabStreamRouter.class);

    /**
     * Cap on the scratch result's retained {@code [MESSAGE]} blocks. Nothing
     * consumes this list on the session path — it exists only because
     * {@link OpenCodeMarkerParser} writes into it — and unbounded retention
     * would grow for the whole life of the session.
     */
    private static final int MAX_SCRATCH_MESSAGES = 64;

    private final OpenCodeMarkerParser markerParser = new OpenCodeMarkerParser(new Gson());
    private final OpenCodeMessageHandler messageHandler;
    private final SDKResult scratchResult = new SDKResult();
    private final OpenCodeMarkerParser.StreamContext streamContext =
            new OpenCodeMarkerParser.StreamContext();

    /**
     * The two outputs this tab owns. The router is the single owner so a tab
     * cannot end up with an adapter from one session and a coalescer from
     * another; they are attached together and released together.
     */
    private volatile SessionCallbackAdapter adapter;
    private volatile StreamMessageCoalescer coalescer;

    public TabStreamRouter(SessionState state, CallbackHandler callbackHandler) {
        this.messageHandler = new OpenCodeMessageHandler(state, callbackHandler);
    }

    /** Attach the tab's renderer-facing outputs. */
    public void attachOutputs(SessionCallbackAdapter adapter, StreamMessageCoalescer coalescer) {
        this.adapter = adapter;
        this.coalescer = coalescer;
    }

    /** The tab's session→webview adapter. */
    public SessionCallbackAdapter getAdapter() {
        return adapter;
    }

    /** The tab's message coalescer. */
    public StreamMessageCoalescer getCoalescer() {
        return coalescer;
    }

    /** Drop the outputs when the tab closes or its session is replaced. */
    public void clearOutputs() {
        adapter = null;
        coalescer = null;
    }

    /**
     * The session's streaming message callback. Handed to the send path so that
     * in-turn and out-of-turn markers share one handler (and therefore one
     * {@code SessionState} accumulator view) per session.
     */
    public OpenCodeMessageHandler getMessageCallback() {
        return messageHandler;
    }

    @Override
    public void onLine(String line) {
        try {
            markerParser.processOutputLine(line, messageHandler, scratchResult, streamContext);
        } catch (Exception e) {
            LOG.warn("TabStreamRouter failed to process line: " + e.getMessage());
        }
        if (scratchResult.messages.size() > MAX_SCRATCH_MESSAGES) {
            scratchResult.messages.clear();
        }
    }

    @Override
    public void onStderr(String text) {
        if (text != null && !text.isBlank()) {
            messageHandler.onMessage("node_log", text);
        }
    }
}
