package com.opencodebuddy.tab;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * One send whose session id is not known yet.
 *
 * <p>A new opencode session only gets {@code ses_*} after the daemon resolves it.
 * Until {@code [SESSION_ID]} arrives, data lines cannot be routed by session:
 * the host has no sink to register. They belong to the request that started the
 * send, and this buffer keeps them in wire order until the identity is known.
 * The marker itself is delivered to the request immediately, because that is
 * what lets the host bind the tab and register the session sink.</p>
 *
 * <p>Bounded by the 30 s bootstrap timeout from plan §8.5: a send that never
 * reports an id fails instead of leaving the spinner running forever.</p>
 */
public final class PendingSend {

    public static final long BOOTSTRAP_TIMEOUT_SECONDS = 30;

    private final String requestId;
    private final String tabId;
    private final Consumer<String> markerConsumer;
    private final List<String> buffered = new ArrayList<>();
    private Consumer<String> sessionSink;
    private Runnable timeoutAction = () -> { };
    private String sessionId;
    private boolean completed;
    private boolean timedOut;

    public PendingSend(String requestId, String tabId, Consumer<String> markerConsumer) {
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.tabId = tabId;
        this.markerConsumer = Objects.requireNonNull(markerConsumer, "markerConsumer");
        CompletableFuture.delayedExecutor(BOOTSTRAP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .execute(this::expire);
    }

    public String requestId() {
        return requestId;
    }

    public String tabId() {
        return tabId;
    }

    public synchronized String sessionId() {
        return sessionId;
    }

    public synchronized boolean isTimedOut() {
        return timedOut;
    }

    /** The line that names the session, or null when it is not an identity marker. */
    public static String sessionIdOf(String line) {
        if (line == null || !line.startsWith("[SESSION_ID]")) {
            return null;
        }
        String value = line.substring("[SESSION_ID]".length()).trim();
        if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
            value = value.substring(1, value.length() - 1);
        }
        return value.isEmpty() ? null : value;
    }

    /**
     * Accept one data line. Before the identity marker, content is buffered;
     * the marker is handed to the request callback and starts the bind.
     */
    public synchronized void onLine(String line) {
        if (completed) {
            return;
        }
        String markerSession = sessionIdOf(line);
        if (markerSession != null) {
            sessionId = markerSession;
            markerConsumer.accept(line);
            return;
        }
        if (sessionId == null || sessionSink == null) {
            buffered.add(line);
            return;
        }
        sessionSink.accept(line);
    }

    /**
     * Called by the host when the tab's session sink is registered. Replays the
     * lines that arrived before the bind, then switches to direct delivery.
     */
    public synchronized void attach(Consumer<String> sink) {
        if (completed) {
            return;
        }
        sessionSink = Objects.requireNonNull(sink, "sink");
        for (String line : buffered) {
            sink.accept(line);
        }
        buffered.clear();
    }

    /** The request finished; no line can belong to it any more. */
    public synchronized void complete() {
        completed = true;
        buffered.clear();
        sessionSink = null;
    }

    public synchronized void setTimeoutAction(Runnable action) {
        timeoutAction = action != null ? action : () -> { };
    }

    /** Test hook: exercise the timeout synchronously instead of waiting 30 s. */
    void expireNow() {
        expire();
    }

    private void expire() {
        boolean fire;
        synchronized (this) {
            fire = !completed && sessionId == null && !timedOut;
            if (fire) {
                timedOut = true;
                completed = true;
                buffered.clear();
            }
        }
        if (fire) {
            timeoutAction.run();
        }
    }
}
