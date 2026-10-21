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
}
