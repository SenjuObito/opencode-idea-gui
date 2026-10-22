package com.opencodebuddy.handler;

import com.opencodebuddy.handler.core.HandlerContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The v2 command entry point of the send path.
 *
 * <p>These tests only cover the rejection paths: they need no IDE, no daemon
 * and no session, which is exactly the point — a command that does not add up
 * must be refused before it can reach any of them.</p>
 */
public class SessionHandlerHostCommandTest {

    private final List<String[]> jsCalls = new ArrayList<>();

    private SessionHandler handler() {
        HandlerContext context = new HandlerContext(
                null,
                null,
                null,
                new HandlerContext.JsCallback() {
                    @Override
                    public void callJavaScript(String functionName, String... args) {
                        jsCalls.add(new String[] {functionName, args == null ? "" : String.join("|", args)});
                    }

                    @Override
                    public String escapeJs(String str) {
                        return str;
                    }
                });
        return new SessionHandler(context);
    }

    private String envelope(String tabId, String requestId, String version) {
        JsonObject root = new JsonObject();
        root.addProperty("v", version == null ? 2 : Integer.parseInt(version));
        root.addProperty("type", "session.send");
        if (tabId != null) {
            root.addProperty("tabId", tabId);
        }
        if (requestId != null) {
            root.addProperty("requestId", requestId);
        }
        root.add("payload", JsonParser.parseString("{\"text\":\"hi\"}"));
        return root.toString();
    }

    @Test
    public void aMalformedEnvelopeIsRejectedInsteadOfExecuted() {
        SessionHandler handler = handler();

        assertTrue(handler.handle("session.send", "not json"));

        assertEquals(1, jsCalls.size());
        assertEquals("commandRejected", jsCalls.get(0)[0]);
        assertTrue(jsCalls.get(0)[1].contains("BAD_ENVELOPE"));
    }

    @Test
    public void anEnvelopeWithoutATabIdIsRejected() {
        SessionHandler handler = handler();

        assertTrue(handler.handle("session.send", envelope(null, "req-1", null)));

        assertEquals(1, jsCalls.size());
        assertEquals("commandRejected", jsCalls.get(0)[0]);
        assertTrue(jsCalls.get(0)[1].contains("MISSING_TAB_ID"));
    }

    @Test
    public void anEnvelopeWithoutARequestIdIsRejected() {
        SessionHandler handler = handler();

        assertTrue(handler.handle("session.send", envelope("tab-1", null, null)));

        assertEquals(1, jsCalls.size());
        assertEquals("commandRejected", jsCalls.get(0)[0]);
        assertTrue(jsCalls.get(0)[1].contains("MISSING_REQUEST_ID"));
    }

    @Test
    public void anOldEnvelopeVersionIsRejected() {
        SessionHandler handler = handler();

        assertTrue(handler.handle("session.send", envelope("tab-1", "req-1", "1")));

        assertEquals(1, jsCalls.size());
        assertEquals("commandRejected", jsCalls.get(0)[0]);
        assertTrue(jsCalls.get(0)[1].contains("UNSUPPORTED_VERSION"));
    }

    @Test
    public void aReplayedCommandIsExecutedOnlyOnce() {
        SessionHandler handler = handler();
        String command = envelope("tab-1", "req-1", null);

        handler.handle("session.send", command);
        int afterFirst = jsCalls.size();
        handler.handle("session.send", command);

        // The replay is swallowed: no second rejection is reported.
        assertEquals(afterFirst, jsCalls.size());
    }
}
