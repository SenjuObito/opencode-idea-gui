package com.opencodebuddy.handler;

import com.opencodebuddy.handler.core.HandlerContext;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for window-level bridge event dispatch: restored-history DOM
 * commit acknowledgment and surface-damage phase acknowledgments.
 */
public class WindowEventHandlerTest {

    /** Verifies that history_dom_committed is supported and dispatched exactly once. */
    @Test
    public void dispatchesHistoryDomCommittedCallback() {
        AtomicReference<Long> historyCommitEpoch = new AtomicReference<>();
        HandlerContext context = new HandlerContext(null, null, null, new MockJsCallback());
        WindowEventHandler handler = new WindowEventHandler(context, new NoOpCallback() {
            @Override
            public void onHistoryRenderComplete(long commitEpoch) {
                historyCommitEpoch.set(commitEpoch);
            }
        });

        assertTrue(handler.handle("history_dom_committed", "7"));
        assertEquals(Long.valueOf(7L), historyCommitEpoch.get());
    }

    /** tab_activate carries a stable tabId, not a sessionId. */
    @Test
    public void dispatchesTabActivateByTabId() {
        AtomicReference<String> observed = new AtomicReference<>();
        HandlerContext context = new HandlerContext(null, null, null, new MockJsCallback());
        WindowEventHandler handler = new WindowEventHandler(context, new NoOpCallback() {
            @Override
            public void onActivateTab(String tabId) {
                observed.set(tabId);
            }
        });

        assertTrue(handler.handle("tab_activate", "{\"tabId\":\"tab-42\"}"));
        assertEquals("tab-42", observed.get());
    }

    /** Verifies that a tokenized surface phase acknowledgment is parsed without losing identity. */
    @Test
    public void dispatchesSurfaceDamageAppliedCallback() {
        AtomicReference<String> observed = new AtomicReference<>();
        HandlerContext context = new HandlerContext(null, null, null, new MockJsCallback());
        WindowEventHandler handler = new WindowEventHandler(context, new NoOpCallback() {
            @Override
            public void onSurfaceDamageApplied(String token, String phase, boolean applied) {
                observed.set(token + ":" + phase + ":" + applied);
            }
        });

        assertTrue(handler.handle(
                "surface_damage_applied",
                "{\"token\":\"4:5:6:7\",\"phase\":\"B\",\"applied\":true}"));
        assertEquals("4:5:6:7:B:true", observed.get());
    }

    private static final class MockJsCallback implements HandlerContext.JsCallback {
        final List<String> calls = new ArrayList<>();

        @Override
        public void callJavaScript(String functionName, String... args) {
            calls.add(functionName + ":" + (args != null ? String.join(",", args) : ""));
        }

        @Override
        public void publishEvent(com.opencodebuddy.tab.TabEvent event) {
            Object data = event.getPayload().get("data");
            Object args = event.getPayload().get("args");
            if (args != null) {
                calls.add(event.getType() + ":" + args);
            } else {
                boolean empty = data == null || data instanceof com.google.gson.JsonNull;
                calls.add(event.getType() + ":" + (empty ? "" : data));
            }
        }

        @Override
        public String escapeJs(String str) {
            return str != null ? str : "";
        }
    }

    /** Callback adapter that keeps unrelated window events inert for focused event tests. */
    private static class NoOpCallback implements WindowEventHandler.Callback {
        @Override public void onHeartbeat(String content) { }
        @Override public void onTabLoadingChanged(boolean loading) { }
        @Override public void onTabStatusChanged(String status) { }
        @Override public void onFrontendReady() { }
        @Override public void onHistoryRenderComplete(long commitEpoch) { }
        @Override public void onSurfaceDamageApplied(
                String token,
                String phase,
                boolean applied
        ) { }
        @Override public void onRefreshSlashCommands(String tabId) { }
    }
}
