package com.opencodebuddy.tab;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

public class TabRegistryTest {

    private static TabId tab(String value) {
        return TabId.of(value);
    }

    @Test
    public void createIsIdempotentAndHasNoSession() {
        TabRegistry registry = new TabRegistry();
        TabId id = tab("tab-a");

        TabRecord first = registry.create(id, "/repo");
        TabRecord second = registry.create(id, "/other");

        assertSame(first, second);
        assertEquals(TabState.NEW, first.getState());
        assertNull(first.getSessionId());
        assertEquals(1, registry.size());
    }

    @Test
    public void snapshotsCarryStableIdsAndTheActiveFlag() {
        TabRegistry registry = new TabRegistry();
        TabId first = tab("tab-a");
        TabId second = tab("tab-b");
        registry.create(first, "/repo");
        registry.create(second, "/repo");
        registry.bind(first, "ses_a", null, null, null);
        registry.activate(second);

        java.util.List<TabSnapshot> snapshots = registry.snapshots();

        assertEquals(2, snapshots.size());
        assertEquals("tab-a", snapshots.get(0).tabId());
        assertEquals("ses_a", snapshots.get(0).sessionId());
        assertEquals(false, snapshots.get(0).active());
        assertEquals("tab-b", snapshots.get(1).tabId());
        assertEquals(true, snapshots.get(1).active());
    }

    @Test
    public void bindIsAtomicAndIndexesBothDirections() {
        TabRegistry registry = new TabRegistry();
        TabRecord record = registry.create(tab("tab-a"), "/repo");

        registry.bind(record.getTabId(), "ses_abc", null, null, null);

        assertEquals(TabState.BOUND, record.getState());
        assertEquals("ses_abc", record.getSessionIdString());
        assertSame(record, registry.findBySession("ses_abc"));
        assertSame(record, registry.findBySession(SessionId.of("ses_abc")));
        assertEquals(1, record.getEpoch());
    }

    @Test
    public void sessionCannotBeOwnedByTwoTabs() {
        TabRegistry registry = new TabRegistry();
        registry.create(tab("tab-a"), "/repo");
        registry.create(tab("tab-b"), "/repo");
        registry.bind(tab("tab-a"), "ses_shared", null, null, null);

        try {
            registry.bind(tab("tab-b"), "ses_shared", null, null, null);
            fail("expected bind to be rejected");
        } catch (IllegalStateException expected) {
            assertEquals("ses_shared", registry.findBySession("ses_shared").getSessionIdString());
            assertNull(registry.get(tab("tab-b")).getSessionId());
        }
    }

    @Test
    public void tabCannotBeReboundToAnotherSession() {
        TabRegistry registry = new TabRegistry();
        registry.create(tab("tab-a"), "/repo");
        registry.bind(tab("tab-a"), "ses_one", null, null, null);

        try {
            registry.bind(tab("tab-a"), "ses_two", null, null, null);
            fail("expected rebind to be rejected");
        } catch (IllegalStateException expected) {
            assertEquals("ses_one", registry.get(tab("tab-a")).getSessionIdString());
        }
    }

    @Test
    public void closeReleasesBothIndexesAndIsIdempotent() {
        TabRegistry registry = new TabRegistry();
        registry.create(tab("tab-a"), "/repo");
        registry.bind(tab("tab-a"), "ses_abc", null, null, null);

        TabRecord closed = registry.close(tab("tab-a"));
        assertNotNull(closed);
        assertEquals(TabState.CLOSED, closed.getState());
        assertNull(registry.findBySession("ses_abc"));
        assertNull(registry.get(tab("tab-a")));
        assertEquals(0, registry.size());

        assertNull(registry.close(tab("tab-a")));
    }

    @Test(expected = IllegalArgumentException.class)
    public void sessionIdRejectsTabId() {
        SessionId.of("tab-123");
    }

    @Test(expected = IllegalArgumentException.class)
    public void sessionIdRejectsPermissionKey() {
        SessionId.of("4f6b4f8e-17fd-4b19-9c80-9c93d6c2a3a1");
    }

    @Test
    public void tryParseKeepsOnlyCanonicalIds() {
        assertNull(SessionId.tryParse("tab-1"));
        assertNull(SessionId.tryParse(null));
        assertEquals("ses_x", SessionId.tryParse("ses_x").value());
    }

    @Test
    public void autoTitleDoesNotOverwriteUserTitle() {
        TabSettings settings = new TabSettings();
        settings.applyAutoTitle("Auto title");
        assertEquals("Auto title", settings.getTitle());
        assertEquals(TabSettings.TitleSource.AUTO, settings.getTitleSource());

        settings.applyUserTitle("My title");
        settings.applyAutoTitle("Later auto title");

        assertEquals("My title", settings.getTitle());
        assertEquals(TabSettings.TitleSource.USER, settings.getTitleSource());
    }

    @Test
    public void activeTabIsOnlyAViewHint() {
        TabRegistry registry = new TabRegistry();
        registry.create(tab("tab-a"), "/repo");
        registry.create(tab("tab-b"), "/repo");
        registry.bind(tab("tab-b"), "ses_b", null, null, null);

        registry.activate(tab("tab-a"));

        assertEquals(tab("tab-a"), registry.getActiveTabId());
        assertSame(registry.get(tab("tab-b")), registry.findBySession("ses_b"));
    }
}
