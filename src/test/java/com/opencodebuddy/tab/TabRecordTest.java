package com.opencodebuddy.tab;

import com.opencodebuddy.session.OpencodeSession;
import com.opencodebuddy.session.SessionCallbackAdapter;
import com.opencodebuddy.session.StreamMessageCoalescer;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TabRecordTest {

    private static TabRecord record() {
        return new TabRecord(TabId.of("tab-a"), "/repo");
    }

    @Test
    public void eventSinkBelongsToTheRecord() {
        List<TabEvent> published = new ArrayList<>();
        TabRecord record = new TabRecord(TabId.of("tab-a"), "/repo", published::add);

        record.getEventSink().publish(TabEvent.of("turn.started").tab("tab-a").build());

        assertEquals(1, published.size());
    }

    @Test
    public void firstTurnTerminalWinsAndRepeatsAreRejected() {
        TabRecord record = record();

        assertTrue(record.markTurnEnded(TurnId.of(7)));
        assertFalse(record.markTurnEnded(TurnId.of(7)));
        assertTrue(record.isTurnSettled(TurnId.of(7)));
    }

    @Test
    public void distinctTurnsEachSettleOnce() {
        TabRecord record = record();

        assertTrue(record.markTurnEnded(TurnId.of(1)));
        assertTrue(record.markTurnEnded(TurnId.of(2)));
        assertFalse(record.markTurnEnded(TurnId.of(1)));
        assertFalse(record.markTurnEnded(TurnId.of(2)));
        assertEquals(2, record.settledTurnCount());
    }

    @Test
    public void missingTurnIdIsNeverBlocked() {
        TabRecord record = record();

        assertTrue(record.markTurnEnded(null));
        assertTrue(record.markTurnEnded(null));
        assertFalse(record.isTurnSettled(null));
    }

    @Test
    public void ledgerIsBounded() {
        TabRecord record = record();

        for (long turn = 1; turn <= 256; turn++) {
            assertTrue(record.markTurnEnded(TurnId.of(turn)));
        }
        assertEquals(256, record.settledTurnCount());

        // The 257th id evicts the oldest entry; a very late duplicate of an
        // evicted turn is forwarded again rather than growing the ledger.
        assertTrue(record.markTurnEnded(TurnId.of(257)));
        assertEquals(256, record.settledTurnCount());
        assertFalse(record.isTurnSettled(TurnId.of(1)));
        assertTrue(record.isTurnSettled(TurnId.of(257)));
    }

    @Test
    public void closeReleasesTheLedgerWithTheTab() {
        TabRecord record = record();
        record.markTurnEnded(TurnId.of(3));

        record.close();

        assertTrue(record.isClosed());
        assertEquals(0, record.settledTurnCount());
    }

    @Test
    public void autoTitleAppliesUntilTheUserNamesTheTab() {
        TabRecord record = record();

        record.setTitle("新会话");
        assertEquals("新会话", record.getTitle());
        assertEquals(TabSettings.TitleSource.AUTO, record.getSettings().getTitleSource());

        record.setTitle("引擎标题");
        assertEquals("引擎标题", record.getTitle());

        record.setUserTitle("我的会话");
        assertEquals(TabSettings.TitleSource.USER, record.getSettings().getTitleSource());

        // A late engine event must never undo the user's name.
        record.setTitle("迟到的引擎标题");
        assertEquals("我的会话", record.getTitle());
    }

    @Test
    public void turnIdRejectsNonPositiveAndParsesDecimalStrings() {
        assertEquals(42, TurnId.tryParse("42").value());
        assertNull(TurnId.tryParse("0"));
        assertNull(TurnId.tryParse("-3"));
        assertNull(TurnId.tryParse("abc"));
        assertNull(TurnId.tryParse(""));
        assertNull(TurnId.tryParse(null));

        try {
            TurnId.of(0);
            fail("turn ids start at 1");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void aSessionWithoutResourcesHasNoOutputs() {
        TabRecord record = record();
        record.setProvisionalResources(new OpencodeSession(null, null), null, null);

        assertFalse(record.hasOutputs());
        assertNull(record.getAdapter());
        assertNull(record.getCoalescer());
    }

    @Test
    public void provisionalResourcesWireTheSessionAndItsCallback() {
        TabRecord record = record();
        RecordingSession session = new RecordingSession();
        StreamMessageCoalescer coalescer = new StreamMessageCoalescer(
                "ses_a", "tab-a", null, null);
        SessionCallbackAdapter adapter = new SessionCallbackAdapter(
                "ses_a", coalescer, event -> { }, null, () -> true, null, "tab-a");

        record.setProvisionalResources(session, adapter, coalescer);

        assertTrue(record.hasOutputs());
        assertSame(adapter, record.getAdapter());
        assertSame(coalescer, record.getCoalescer());
        assertSame("attaching the tab's data plane must also set the session callback",
                adapter, session.capturedCallback);
    }

    /** Captures the callback so the wiring can be asserted without a real daemon. */
    private static final class RecordingSession extends OpencodeSession {
        private OpencodeSession.SessionCallback capturedCallback;

        private RecordingSession() {
            super(null, null);
        }

        @Override
        public void setCallback(OpencodeSession.SessionCallback callback) {
            this.capturedCallback = callback;
            super.setCallback(callback);
        }
    }
}
