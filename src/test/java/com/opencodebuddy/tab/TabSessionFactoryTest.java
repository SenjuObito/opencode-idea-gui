package com.opencodebuddy.tab;

import com.opencodebuddy.session.OpencodeSession;
import com.opencodebuddy.session.SessionCallbackAdapter;
import com.opencodebuddy.session.StreamMessageCoalescer;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

public class TabSessionFactoryTest {

    private static TabSessionFactory factory(TabRegistry registry) {
        return new TabSessionFactory(registry, new TabSessionFactory.Host() {
            @Override
            public OpencodeSession newSession(String cwd) {
                return null;
            }

            @Override
            public StreamMessageCoalescer newCoalescer(TabId tabId) {
                return null;
            }

            @Override
            public SessionCallbackAdapter newAdapter(TabId tabId, StreamMessageCoalescer coalescer) {
                return null;
            }
        });
    }

    @Test
    public void prepareCreatesTheTabEvenWhenNoSessionCouldBeBuilt() {
        TabRegistry registry = new TabRegistry();
        TabSessionFactory.Prepared prepared = factory(registry)
                .prepare(TabId.of("tab-a"), "/repo", session -> fail("must not configure a null session"));

        assertNotNull(prepared.tab());
        assertNull(prepared.session());
        assertFalse(prepared.created());
        assertEquals(TabState.NEW, prepared.tab().getState());
        assertSame(prepared.tab(), registry.get(TabId.of("tab-a")));
    }

    @Test
    public void attachBindsTheCanonicalSessionAndRejectsADifferentOne() {
        TabRegistry registry = new TabRegistry();
        TabSessionFactory factory = factory(registry);
        OpencodeSession session = new OpencodeSession(null, null);

        TabRecord record = factory.attach(
                TabId.of("tab-a"), "ses_first", session, null, null, "/repo");

        assertEquals("ses_first", record.getSessionIdString());
        assertEquals(TabState.LOADING_HISTORY, record.getState());
        assertSame(session, record.getSession());

        OpencodeSession other = new OpencodeSession(null, null);
        try {
            factory.attach(TabId.of("tab-a"), "ses_second", other, null, null, "/repo");
            fail("expected a different canonical session to be rejected");
        } catch (IllegalStateException expected) {
            assertEquals("ses_first", record.getSessionIdString());
        }
    }

    @Test
    public void attachWiresAdapterAndCoalescerIdentifiers() {
        TabRegistry registry = new TabRegistry();
        TabSessionFactory factory = factory(registry);
        OpencodeSession session = new OpencodeSession(null, null);
        StreamMessageCoalescer coalescer = new StreamMessageCoalescer("initial_ses", "initial_tab", null, null);
        SessionCallbackAdapter adapter = new SessionCallbackAdapter(
                "initial_ses", coalescer, event -> {}, null, () -> true, null, "initial_tab");

        TabRecord record = factory.attach(
                TabId.of("tab-fork"), "ses_fork", session, adapter, coalescer, "/repo");

        assertEquals("tab-fork", adapter.getTabId());
        assertEquals("ses_fork", adapter.getSessionId());
        assertEquals("tab-fork", coalescer.getTabId());
        assertEquals("ses_fork", coalescer.getSessionId());
        assertEquals(TabState.LOADING_HISTORY, record.getState());
    }

    @Test
    public void attachSessionCreatesAndWiresFromHost() {
        TabRegistry registry = new TabRegistry();
        OpencodeSession session = new OpencodeSession(null, null);
        StreamMessageCoalescer coalescer = new StreamMessageCoalescer("initial", "initial", null, null);
        SessionCallbackAdapter adapter = new SessionCallbackAdapter(
                "initial", coalescer, event -> {}, null, () -> true, null, "initial");

        TabSessionFactory factory = new TabSessionFactory(registry, new TabSessionFactory.Host() {
            @Override
            public OpencodeSession newSession(String cwd) {
                return session;
            }

            @Override
            public StreamMessageCoalescer newCoalescer(TabId tabId) {
                return coalescer;
            }

            @Override
            public SessionCallbackAdapter newAdapter(TabId tabId, StreamMessageCoalescer c) {
                return adapter;
            }
        });

        TabRecord record = factory.attachSession(
                TabId.of("tab-target"), "ses_target", "/repo", s -> s.getState().setModel("custom-model"));

        assertEquals("tab-target", record.getTabId().value());
        assertEquals("ses_target", record.getSessionIdString());
        assertSame(session, record.getSession());
        assertSame(adapter, record.getAdapter());
        assertSame(coalescer, record.getCoalescer());
        assertEquals("tab-target", adapter.getTabId());
        assertEquals("ses_target", adapter.getSessionId());
        assertEquals("tab-target", coalescer.getTabId());
        assertEquals("ses_target", coalescer.getSessionId());
        assertEquals("custom-model", session.getState().getModel());
        assertEquals(TabState.LOADING_HISTORY, record.getState());
    }
}
