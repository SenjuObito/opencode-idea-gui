package com.opencodebuddy.tab;

import com.opencodebuddy.session.OpencodeSession;
import com.opencodebuddy.session.SessionCallbackAdapter;
import com.opencodebuddy.session.StreamMessageCoalescer;

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

    private final TabId tabId;
    private final String cwd;
    private final TabSettings settings = new TabSettings();
    private final TabSessionActor actor;
    private final AtomicLong epoch = new AtomicLong(0);
    private final AtomicLong revision = new AtomicLong();

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

    public void close() {
        state = TabState.CLOSED;
        clearSessionResources();
        actor.close();
    }
}
