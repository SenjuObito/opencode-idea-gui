package com.opencodebuddy.handler.history;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class HistoryHandlerTest {

    @Test
    public void extractStringReadsEnvelopePayload() {
        assertEquals("ses_123", HistoryHandler.extractString("{\"sessionId\":\"ses_123\"}", "sessionId"));
    }

    @Test
    public void extractStringKeepsLegacyRawValue() {
        assertEquals("ses_legacy", HistoryHandler.extractString("ses_legacy", "sessionId"));
        assertEquals("{\"other\":1}", HistoryHandler.extractString("{\"other\":1}", "sessionId"));
    }

    @Test
    public void extractArrayReadsEnvelopePayloadAndKeepsLegacyArray() {
        assertEquals("[\"a\",\"b\"]", HistoryHandler.extractArray("{\"sessionIds\":[\"a\",\"b\"]}", "sessionIds"));
        assertEquals("[\"a\",\"b\"]", HistoryHandler.extractArray("[\"a\",\"b\"]", "sessionIds"));
    }
}
