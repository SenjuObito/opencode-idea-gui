package com.opencodebuddy.handler.core;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The editor-context decision is read from the tab record at send time; the
 * handler context only proxies the host's answer.
 */
public class HandlerContextEditorContextTest {

    @Test
    public void unknownTabHasNoEditorContext() {
        HandlerContext context = new HandlerContext(null, null, null, new JsCallbackStub());

        assertFalse(context.isAutoOpenFileEnabled(null));
        assertFalse(context.isAutoOpenFileEnabled(""));
        assertFalse(context.isAutoOpenFileEnabled("tab-a"));
    }

    @Test
    public void delegatesTheTabToTheHostRecord() {
        AtomicReference<String> observed = new AtomicReference<>();
        HandlerContext context = new HandlerContext(null, null, null, new JsCallbackStub() {
            @Override
            public boolean isAutoOpenFileEnabled(String tabId) {
                observed.set(tabId);
                return true;
            }
        });

        assertTrue(context.isAutoOpenFileEnabled("tab-a"));
        assertTrue("tab-a".equals(observed.get()));
    }

    private static class JsCallbackStub implements HandlerContext.JsCallback {
        @Override
        public void callJavaScript(String functionName, String... args) {
        }

        @Override
        public String escapeJs(String str) {
            return str;
        }
    }
}
