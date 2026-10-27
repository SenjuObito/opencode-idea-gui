package com.opencodebuddy.tab;

import com.opencodebuddy.session.OpencodeSession;
import com.opencodebuddy.session.SessionCallbackAdapter;
import com.opencodebuddy.session.StreamMessageCoalescer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Host-side owner of one tab's session resources and per-tab settings.
 *
 * <p>There is deliberately no {@code active} flag here. Activity is a view
 * concern owned by the webview; it must never decide which tab receives a
 * message or a permission prompt.</p>
 *
 * <p>Prompts are not stored as a list on this record: {@link com.opencodebuddy.prompt.PromptBroker}
 * is the single owner of every pending permission/question/plan future and
 * keys each one by its owning tab, so closing a tab rejects exactly its prompts.
 * The editable draft likewise lives in the webview's {@code TabDraftStore};
 * the host only serializes sends through {@link PendingSend}.</p>
 */
public final class TabRecord {

    /**
     * A turn can settle through several paths (marker, stream end, interrupted
     * send, daemon restart), so the last few terminal ids are remembered to make
     * the second and later arrivals a no-op. Bounded so a long-lived tab cannot
     * grow without limit; an evicted id only means a very late duplicate would
     * be forwarded again, which the webview also ignores.
     */
    private static final int MAX_SETTLED_TURNS = 256;

    private final TabId tabId;
    private final String cwd;
    private final TabEventSink eventSink;
    private final TabSettings settings = new TabSettings();
    /** The attachments this tab staged; released with the tab. */
    private final TabAttachmentStore attachments;
    private final TabSessionActor actor;
    private final AtomicLong epoch = new AtomicLong(0);
    private final AtomicLong revision = new AtomicLong();
    private final Map<Long, Boolean> settledTurns = new LinkedHashMap<>();
    private final Deque<Long> settledTurnOrder = new ArrayDeque<>();

    private volatile TabState state = TabState.NEW;
    private volatile SessionId sessionId;
    private volatile OpencodeSession session;
    private volatile long streamingStartedAtMillis;
    private volatile long lastTurnDurationMs;

    public TabRecord(TabId tabId, String cwd) {
        this(tabId, cwd, event -> { });
    }

    public TabRecord(TabId tabId, String cwd, TabEventSink eventSink) {
        this.tabId = Objects.requireNonNull(tabId, "tabId");
        this.cwd = cwd;
        this.eventSink = eventSink != null ? eventSink : event -> { };
        this.actor = new TabSessionActor(tabId);
        this.attachments = new TabAttachmentStore(
                TabAttachmentStore.defaultRoot().resolve(sanitizeForPath(this.tabId.value())));
    }

    public TabId getTabId() {
        return tabId;
    }

    /** The tab's own event outlet; every adapter and coalescer publishes here. */
    public TabEventSink getEventSink() {
        return eventSink;
    }

    /** The tab's serial mutation queue. Closed with the tab. */
    public TabSessionActor getActor() {
        return actor;
    }

    public String getCwd() {
        return cwd;
    }

    public TabState getState() {
        return state;
    }

    public void setState(TabState state) {
        TabState next = Objects.requireNonNull(state, "state");
        TabState previous = this.state;
        this.state = next;
        if (next == TabState.STREAMING && previous != TabState.STREAMING) {
            streamingStartedAtMillis = System.currentTimeMillis();
        } else if (previous == TabState.STREAMING && next != TabState.STREAMING) {
            finishTurnDuration();
        }
    }

    private void finishTurnDuration() {
        long started = streamingStartedAtMillis;
        if (started > 0) {
            lastTurnDurationMs = Math.max(0, System.currentTimeMillis() - started);
            streamingStartedAtMillis = 0;
        }
    }

    /** Duration of the most recently finished streaming turn, or 0. */
    public long getLastTurnDurationMs() {
        return lastTurnDurationMs;
    }

    public SessionId getSessionId() {
        return sessionId;
    }

    public String getSessionIdString() {
        return sessionId != null ? sessionId.value() : null;
    }

    public OpencodeSession getSession() {
        return session;
    }

    public SessionCallbackAdapter getAdapter() {
        OpencodeSession current = session;
        return current != null ? current.getStreamRouter().getAdapter() : null;
    }

    public StreamMessageCoalescer getCoalescer() {
        OpencodeSession current = session;
        return current != null ? current.getStreamRouter().getCoalescer() : null;
    }

    /** The tab's data-plane router; the single owner of adapter and coalescer. */
    public TabStreamRouter getRouter() {
        OpencodeSession current = session;
        return current != null ? current.getStreamRouter() : null;
    }

    public TabSettings getSettings() {
        return settings;
    }

    /** The attachments this tab owns. */
    public TabAttachmentStore getAttachments() {
        return attachments;
    }

    /** Keep a path segment free of separators and traversal. */
    private static String sanitizeForPath(String value) {
        if (value == null || value.isBlank()) {
            return "unnamed";
        }
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    public String getTitle() {
        return settings.getTitle();
    }

    public void setTitle(String title) {
        settings.applyAutoTitle(title);
    }

    /**
     * Apply a title the user typed.
     *
     * <p>An auto title never overrides it afterwards: the user asked for this
     * name, and a rename that a later auto title silently undid is exactly how
     * the tab strip used to drift from the history list.</p>
     */
    public void setUserTitle(String title) {
        settings.applyUserTitle(title);
    }

    private volatile long turnStartedAtMillis = -1L;
    private volatile long lastTurnMillis = -1L;
    private volatile long lastSeq = -1L;

    /** Mark the start of a turn for the diagnostics report. */
    public void markTurnStarted() {
        this.turnStartedAtMillis = System.currentTimeMillis();
    }

    /** Mark the end of a turn and keep its duration. */
    public void markTurnEnded() {
        long startedAt = turnStartedAtMillis;
        if (startedAt > 0) {
            this.lastTurnMillis = System.currentTimeMillis() - startedAt;
            this.turnStartedAtMillis = -1L;
        }
    }

    /** The highest event sequence number this tab has seen. */
    public long getLastSeq() {
        return lastSeq;
    }

    /** Remember the newest sequence number of an applied event. */
    public void observeSeq(long seq) {
        if (seq > lastSeq) {
            lastSeq = seq;
        }
    }

    /** Duration of the last finished turn in milliseconds, or -1. */
    public long getLastTurnMillis() {
        return lastTurnMillis;
    }

    /** One-line diagnostics for this tab. */
    public String metricsLine() {
        return "tab=" + tabId.value()
                + " state=" + state
                + " lastSeq=" + lastSeq
                + " turnMs=" + lastTurnMillis
                + " attachments=" + attachments.size()
                + " epoch=" + epoch.get();
    }

    public long getEpoch() {
        return epoch.get();
    }

    public long bumpEpoch() {
        return epoch.incrementAndGet();
    }

    public long nextRevision() {
        return revision.incrementAndGet();
    }

    public long getRevision() {
        return revision.get();
    }

    /**
     * Record a turn terminal for this tab.
     *
     * <p>Terminal signals are not guaranteed to be unique: the daemon marker,
     * the stream-end path and an interrupted close can all report the same turn.
     * The first report wins; every later report for the same {@code turnId} is
     * rejected so the UI cannot clear a newer turn's spinner with an older
     * terminal.</p>
     *
     * @return {@code true} when this is the first report of the turn
     */
    public synchronized boolean markTurnEnded(TurnId turnId) {
        if (turnId == null) {
            return true;
        }
        Long key = turnId.value();
        if (settledTurns.containsKey(key)) {
            return false;
        }
        settledTurns.put(key, Boolean.TRUE);
        settledTurnOrder.addLast(key);
        while (settledTurnOrder.size() > MAX_SETTLED_TURNS) {
            settledTurns.remove(settledTurnOrder.removeFirst());
        }
        return true;
    }

    public synchronized boolean isTurnSettled(TurnId turnId) {
        return turnId != null && settledTurns.containsKey(turnId.value());
    }

    synchronized int settledTurnCount() {
        return settledTurns.size();
    }

    public boolean isBound() {
        return sessionId != null && session != null;
    }

    public boolean isClosed() {
        return state == TabState.CLOSED;
    }

    public boolean isStreaming() {
        return state == TabState.STREAMING;
    }

    /**
     * Attach the resources needed while the first send is in flight. There is
     * no canonical session yet; this is not a binding and must not be indexed.
     */
    /** Replace the session object while keeping the tab's canonical binding. */
    public void setSession(OpencodeSession session) {
        releasePreviousSession(session);
        this.session = session;
    }

    public void setProvisionalResources(
            OpencodeSession session,
            SessionCallbackAdapter adapter,
            StreamMessageCoalescer coalescer
    ) {
        releasePreviousSession(session);
        this.session = session;
        if (session != null) {
            session.getStreamRouter().attachOutputs(adapter, coalescer);
        }
    }

    public void setSessionResources(
            SessionId sessionId,
            OpencodeSession session,
            SessionCallbackAdapter adapter,
            StreamMessageCoalescer coalescer
    ) {
        releasePreviousSession(session);
        this.sessionId = sessionId;
        this.session = session;
        if (session != null) {
            session.getStreamRouter().attachOutputs(adapter, coalescer);
        }
    }

    /** A replaced session must not keep the outputs of the tab's old session. */
    private void releasePreviousSession(OpencodeSession next) {
        OpencodeSession previous = session;
        if (previous != null && previous != next) {
            previous.getStreamRouter().clearOutputs();
        }
    }

    public void clearSessionResources() {
        OpencodeSession current = session;
        if (current != null) {
            current.getStreamRouter().clearOutputs();
        }
        this.sessionId = null;
        this.session = null;
    }

    public synchronized void close() {
        setState(TabState.CLOSED);
        clearSessionResources();
        settledTurns.clear();
        settledTurnOrder.clear();
        // The bytes must not outlive the tab that owns them.
        attachments.release();
        actor.close();
    }
}
