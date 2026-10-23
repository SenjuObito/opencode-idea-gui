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
 * <p>These tests cover the rejection paths of the payload handled by SessionHandler.</p>
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

                    @Override
                    public void publishEvent(com.opencodebuddy.tab.TabEvent event) {
                        jsCalls.add(new String[] {event.getType(), String.valueOf(event.getPayload().get("code"))});
                    }
                });
        return new SessionHandler(context);
    }

    private String payload(String tabId, String requestId) {
        JsonObject root = new JsonObject();
        if (tabId != null) {
            root.addProperty("tabId", tabId);
        }
        if (requestId != null) {
            root.addProperty("requestId", requestId);
        }
        root.addProperty("text", "hi");
        return root.toString();
    }

    @Test
    public void aMalformedPayloadIsRejectedInsteadOfExecuted() {
        SessionHandler handler = handler();

        assertTrue(handler.handle("session.send", "not json"));

        assertEquals(1, jsCalls.size());
        assertEquals("command.rejected", jsCalls.get(0)[0]);
        assertTrue(jsCalls.get(0)[1].contains("BAD_ENVELOPE"));
    }

    @Test
    public void aPayloadWithoutATabIdIsRejected() {
        SessionHandler handler = handler();

        assertTrue(handler.handle("session.send", payload(null, "req-1")));

        assertEquals(1, jsCalls.size());
        assertEquals("command.rejected", jsCalls.get(0)[0]);
        assertTrue(jsCalls.get(0)[1].contains("MISSING_TAB_ID"));
    }

    @Test
    public void aPayloadWithoutARequestIdIsRejected() {
        SessionHandler handler = handler();

        assertTrue(handler.handle("session.send", payload("tab-1", null)));

        assertEquals(1, jsCalls.size());
        assertEquals("command.rejected", jsCalls.get(0)[0]);
        assertTrue(jsCalls.get(0)[1].contains("MISSING_REQUEST_ID"));
    }

    @Test
    public void aReplayedCommandIsExecutedOnlyOnce() {
        SessionHandler handler = handler();
        String command = payload("tab-1", "req-1");

        handler.handle("session.send", command);
        int afterFirst = jsCalls.size();
        handler.handle("session.send", command);

        // The replay is swallowed: no second rejection is reported.
        assertEquals(afterFirst, jsCalls.size());
    }
}
