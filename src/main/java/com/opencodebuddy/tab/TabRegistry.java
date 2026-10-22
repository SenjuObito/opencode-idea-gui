package com.opencodebuddy.tab;

import com.opencodebuddy.session.OpencodeSession;
import com.opencodebuddy.session.SessionCallbackAdapter;
import com.opencodebuddy.session.StreamMessageCoalescer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The single owner of tab -> session bindings for one chat window.
 *
 * <p>All methods are synchronized because commands arrive on the EDT/JCEF
 * thread while daemon callbacks arrive on the reader thread. The critical
 * invariants are enforced here, not at call sites:</p>
 *
 * <ul>
 *   <li>a tab exists independently of a session;</li>
 *   <li>a tab has at most one canonical session;</li>
 *   <li>a canonical session belongs to at most one tab;</li>
 *   <li>closing a tab releases both indexes.</li>
 * </ul>
 */
public final class TabRegistry {

    private final Map<TabId, TabRecord> tabs = new LinkedHashMap<>();
    private final Map<SessionId, TabId> sessionIndex = new LinkedHashMap<>();
    private TabId activeTabId;

    public synchronized TabRecord create(TabId tabId, String cwd) {
        TabRecord existing = tabs.get(tabId);
        if (existing != null) {
            if (existing.isClosed()) {
                throw new IllegalStateException("tab is closed: " + tabId);
            }
            return existing;
        }
        TabRecord record = new TabRecord(tabId, cwd);
        tabs.put(tabId, record);
        if (activeTabId == null) {
            activeTabId = tabId;
        }
        return record;
    }

    public synchronized TabRecord get(TabId tabId) {
        return tabId != null ? tabs.get(tabId) : null;
    }

    public synchronized TabRecord require(TabId tabId) {
        TabRecord record = get(tabId);
        if (record == null) {
            throw new IllegalArgumentException("unknown tabId: " + tabId);
        }
        if (record.isClosed()) {
            throw new IllegalStateException("tab is closed: " + tabId);
        }
        return record;
    }

    public synchronized TabRecord findBySession(SessionId sessionId) {
        if (sessionId == null) {
            return null;
        }
        TabId tabId = sessionIndex.get(sessionId);
        return tabId != null ? tabs.get(tabId) : null;
    }

    public synchronized TabRecord findBySession(String sessionId) {
        SessionId parsed = SessionId.tryParse(sessionId);
        return parsed != null ? findBySession(parsed) : null;
    }

    /**
     * Reserve a tab for an in-flight first send. The tab has no canonical
     * session yet; only the request id can route bootstrap lines.
     */
    public synchronized void markCreating(TabId tabId) {
        TabRecord record = require(tabId);
        if (record.getSessionId() != null) {
            throw new IllegalStateException("tab already bound: " + tabId);
        }
        record.setState(TabState.CREATING);
    }

    /**
     * Bind the canonical session reported by opencode.
     */
    public synchronized void bind(
            TabId tabId,
            String canonicalSessionId,
            OpencodeSession session,
            SessionCallbackAdapter adapter,
            StreamMessageCoalescer coalescer
    ) {
        TabRecord record = require(tabId);
        SessionId sessionId = SessionId.of(canonicalSessionId);

        SessionId current = record.getSessionId();
        if (current != null && !current.equals(sessionId)) {
            throw new IllegalStateException("tab " + tabId + " already bound to " + current);
        }

        TabId owner = sessionIndex.get(sessionId);
        if (owner != null && !owner.equals(tabId)) {
            throw new IllegalStateException("session " + sessionId + " already owned by " + owner);
        }

        record.setSessionResources(sessionId, session, adapter, coalescer);
        record.setState(TabState.BOUND);
        record.bumpEpoch();
        sessionIndex.put(sessionId, tabId);
    }

    /**
     * Mark a tab as loading history. The session may already be bound (history
     * load) or bound by the caller immediately after this call.
     */
    public synchronized void markLoadingHistory(TabId tabId) {
        TabRecord record = require(tabId);
        if (record.isStreaming()) {
            throw new IllegalStateException("cannot replace a streaming tab: " + tabId);
        }
        record.setState(TabState.LOADING_HISTORY);
    }

    public synchronized void markBound(TabId tabId) {
        TabRecord record = require(tabId);
        if (record.isBound()) {
            record.setState(TabState.BOUND);
        }
    }

    public synchronized void markStreaming(TabId tabId, boolean streaming) {
        TabRecord record = require(tabId);
        if (record.isClosed()) {
            return;
        }
        if (streaming) {
            record.setState(TabState.STREAMING);
        } else if (record.isBound()) {
            record.setState(TabState.BOUND);
        }
    }

    public synchronized void activate(TabId tabId) {
        require(tabId);
        activeTabId = tabId;
    }

    public synchronized TabId getActiveTabId() {
        return activeTabId;
    }

    public synchronized Collection<TabRecord> all() {
        return new ArrayList<>(tabs.values());
    }

    public synchronized List<TabRecord> liveTabs() {
        List<TabRecord> result = new ArrayList<>();
        for (TabRecord record : tabs.values()) {
            if (!record.isClosed()) {
                result.add(record);
            }
        }
        return result;
    }

    /** One immutable view per live tab, for reload/resync. */
    public synchronized List<TabSnapshot> snapshots() {
        List<TabSnapshot> result = new ArrayList<>();
        for (TabRecord record : tabs.values()) {
            if (record.isClosed()) {
                continue;
            }
            result.add(new TabSnapshot(
                    record.getTabId().value(),
                    record.getSessionIdString(),
                    record.getTitle(),
                    record.getTabId().equals(activeTabId),
                    record.isStreaming(),
                    record.getState() == TabState.LOADING_HISTORY,
                    record.getEpoch()));
        }
        return result;
    }

    /**
     * Close and remove a tab, releasing both indexes. Idempotent.
     *
     * @return the closed record, or null if it never existed or was already closed
     */
    public synchronized TabRecord close(TabId tabId) {
        TabRecord record = tabs.get(tabId);
        if (record == null) {
            return null;
        }
        if (record.isClosed()) {
            return record;
        }
        SessionId sessionId = record.getSessionId();
        if (sessionId != null) {
            sessionIndex.remove(sessionId);
        }
        record.close();
        tabs.remove(tabId);
        if (tabId.equals(activeTabId)) {
            activeTabId = tabs.isEmpty() ? null : tabs.keySet().iterator().next();
        }
        return record;
    }

    public synchronized int size() {
        return tabs.size();
    }

    public synchronized void clear() {
        for (TabRecord record : tabs.values()) {
            record.close();
        }
        tabs.clear();
        sessionIndex.clear();
        activeTabId = null;
    }
}
