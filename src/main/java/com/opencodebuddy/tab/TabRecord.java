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
    private volatile SessionCallbackAdapter adapter;
    private volatile StreamMessageCoalescer coalescer;
    private volatile String title;

    public TabRecord(TabId tabId, String cwd) {
        this.tabId = Objects.requireNonNull(tabId, "tabId");
        this.cwd = cwd;
        this.actor = new TabSessionActor(tabId);
        this.attachments = new TabAttachmentStore(
                TabAttachmentStore.defaultRoot().resolve(sanitizeForPath(this.tabId.value())));
    }

    public TabId getTabId() {
        return tabId;
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
        this.state = Objects.requireNonNull(state, "state");
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
        return adapter;
    }

    public StreamMessageCoalescer getCoalescer() {
        return coalescer;
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
        return title != null ? title : settings.getTitle();
    }

    public void setTitle(String title) {
        this.title = title;
        settings.applyAutoTitle(title);
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
        this.session = session;
    }

    public void setProvisionalResources(
            OpencodeSession session,
            SessionCallbackAdapter adapter,
            StreamMessageCoalescer coalescer
    ) {
        this.session = session;
        this.adapter = adapter;
        this.coalescer = coalescer;
    }

    public void setSessionResources(
            SessionId sessionId,
            OpencodeSession session,
            SessionCallbackAdapter adapter,
            StreamMessageCoalescer coalescer
    ) {
        this.sessionId = sessionId;
        this.session = session;
        this.adapter = adapter;
        this.coalescer = coalescer;
    }

    public void clearSessionResources() {
        this.sessionId = null;
        this.session = null;
        this.adapter = null;
        this.coalescer = null;
    }

    public synchronized void close() {
        state = TabState.CLOSED;
        clearSessionResources();
        settledTurns.clear();
        settledTurnOrder.clear();
        // The bytes must not outlive the tab that owns them.
        attachments.release();
        actor.close();
    }
}
