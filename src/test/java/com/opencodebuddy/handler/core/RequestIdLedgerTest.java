package com.opencodebuddy.handler.core;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RequestIdLedgerTest {

    @Test
    public void aRequestIdIsAcceptedOnce() {
        RequestIdLedger ledger = new RequestIdLedger();

        assertTrue(ledger.remember("req-1"));
        assertFalse(ledger.remember("req-1"));
        assertTrue(ledger.remember("req-2"));
    }

    @Test
    public void aForgottenRequestIdCanBeRetried() {
        RequestIdLedger ledger = new RequestIdLedger();

        assertTrue(ledger.remember("req-1"));
        ledger.forget("req-1");
        assertTrue(ledger.remember("req-1"));
    }

    @Test
    public void theLedgerIsBounded() {
        RequestIdLedger ledger = new RequestIdLedger();

        for (int i = 0; i < 3_000; i++) {
            assertTrue(ledger.remember("req-" + i));
        }
        assertTrue(ledger.size() <= 2_048);
    }
}
