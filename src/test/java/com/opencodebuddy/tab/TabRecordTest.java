package com.opencodebuddy.tab;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TabRecordTest {

    private static TabRecord record() {
        return new TabRecord(TabId.of("tab-a"), "/repo");
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
}
