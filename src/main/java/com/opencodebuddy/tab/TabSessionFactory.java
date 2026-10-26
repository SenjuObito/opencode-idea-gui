package com.opencodebuddy.tab;

import com.opencodebuddy.session.OpencodeSession;
import com.opencodebuddy.session.SessionCallbackAdapter;
import com.opencodebuddy.session.StreamMessageCoalescer;

import java.util.Objects;

/**
 * Creates and binds the resources of one tab's session.
 *
 * <p>Before this existed, "make a session for a tab" was spread over the chat
 * window: create the record, copy preferences, build a coalescer and adapter,
 * mark the tab creating, and (for history) bind it. Every one of those steps
 * had to be repeated by the history path, which is how a history load could
 * leave a tab with an adapter but no canonical binding.</p>
 *
 * <p>The factory is deliberately not a session lifecycle owner: it never talks
 * to the daemon, the webview or the handler context. Those stay in the window,
 * after the tab is in a known state.</p>
 */
public final class TabSessionFactory {

    /** The only pieces the factory cannot build itself. */
    public interface Host {
        OpencodeSession newSession(String cwd);

        StreamMessageCoalescer newCoalescer(TabId tabId);

        SessionCallbackAdapter newAdapter(TabId tabId, StreamMessageCoalescer coalescer);
    }

    /** Outcome of {@link #prepare(TabId, String, SessionConfigurer)}. */
    public record Prepared(TabRecord tab, OpencodeSession session, boolean created) {
    }

    @FunctionalInterface
    public interface SessionConfigurer {
        void configure(OpencodeSession session);
    }

    private final TabRegistry registry;
    private final Host host;

    public TabSessionFactory(TabRegistry registry, Host host) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.host = Objects.requireNonNull(host, "host");
    }

    /**
     * Ensure the tab exists and has a provisional session for its first send.
     * This is not a binding: no canonical session id is known yet.
     */
    public Prepared prepare(TabId tabId, String cwd, SessionConfigurer configurer) {
        TabRecord record = registry.create(tabId, cwd);
        OpencodeSession existing = record.getSession();
        if (existing != null) {
            return new Prepared(record, existing, false);
        }

        OpencodeSession session = host.newSession(cwd);
        if (session == null) {
            return new Prepared(record, null, false);
        }
        if (configurer != null) {
            configurer.configure(session);
        }
        StreamMessageCoalescer coalescer = host.newCoalescer(tabId);
        SessionCallbackAdapter adapter = host.newAdapter(tabId, coalescer);
        if (adapter != null) {
            session.setCallback(adapter);
        }
        record.setProvisionalResources(session, adapter, coalescer);
        registry.markCreating(tabId);
        return new Prepared(record, session, true);
    }

    /**
     * Bind a history session to a tab, creating the tab if the webview has not
     * done so yet. The caller supplies already-resolved resources; a tab that
     * is bound to a different canonical session is rejected here, once.
     */
    public TabRecord attach(
            TabId tabId,
            String canonicalSessionId,
            OpencodeSession session,
            SessionCallbackAdapter adapter,
            StreamMessageCoalescer coalescer,
            String cwd
    ) {
        if (session == null) {
            throw new IllegalArgumentException("session is required");
        }
        TabRecord record = registry.create(tabId, cwd);
        SessionId next = SessionId.of(canonicalSessionId);
        SessionId current = record.getSessionId();
        if (current != null && !current.equals(next)) {
            throw new IllegalStateException(
                    "tab " + tabId + " is already bound to " + current);
        }

        SessionCallbackAdapter effectiveAdapter = adapter != null ? adapter : record.getAdapter();
        StreamMessageCoalescer effectiveCoalescer = coalescer != null ? coalescer : record.getCoalescer();
        if (effectiveAdapter != null) {
            effectiveAdapter.setTabId(tabId.value());
            effectiveAdapter.setSessionId(canonicalSessionId);
            session.setCallback(effectiveAdapter);
        }
        if (effectiveCoalescer != null) {
            effectiveCoalescer.setTabId(tabId.value());
            effectiveCoalescer.setSessionId(canonicalSessionId);
        }

        registry.bind(tabId, canonicalSessionId,
                session,
                effectiveAdapter,
                effectiveCoalescer);
        registry.markLoadingHistory(tabId);
        return record;
    }

    /**
     * Bind a known canonical session (e.g. from fork) to a tab, creating
     * and wiring the session, adapter, and coalescer through Host if not already present.
     */
    public TabRecord attachSession(
            TabId tabId,
            String canonicalSessionId,
            String cwd,
            SessionConfigurer configurer
    ) {
        TabRecord record = registry.create(tabId, cwd);
        SessionId next = SessionId.of(canonicalSessionId);
        SessionId current = record.getSessionId();
        if (current != null && !current.equals(next)) {
            throw new IllegalStateException(
                    "tab " + tabId + " is already bound to " + current);
        }

        OpencodeSession session = record.getSession() != null ? record.getSession() : host.newSession(cwd);
        session.getState().setSessionId(canonicalSessionId);
        if (configurer != null) {
            configurer.configure(session);
        }

        StreamMessageCoalescer coalescer = record.getCoalescer() != null ? record.getCoalescer() : host.newCoalescer(tabId);
        SessionCallbackAdapter adapter = record.getAdapter() != null ? record.getAdapter() : host.newAdapter(tabId, coalescer);

        if (adapter != null) {
            adapter.setTabId(tabId.value());
            adapter.setSessionId(canonicalSessionId);
            session.setCallback(adapter);
        }
        if (coalescer != null) {
            coalescer.setTabId(tabId.value());
            coalescer.setSessionId(canonicalSessionId);
        }

        registry.bind(tabId, canonicalSessionId, session, adapter, coalescer);
        registry.markLoadingHistory(tabId);
        return record;
    }
}
