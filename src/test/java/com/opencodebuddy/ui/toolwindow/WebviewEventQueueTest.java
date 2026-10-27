package com.opencodebuddy.ui.toolwindow;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WebviewEventQueueTest {

    @Test
    public void routesCallsThroughTheUnifiedDispatcherWithDirectFallback() {
        WebviewEventQueue.JsCall<Object> call = new WebviewEventQueue.JsCall<>(
                new Object(), "onTodoUpdated", new String[]{"ses_1", "[]"}, null);

        String script = WebviewEventQueue.buildBatchScript(List.of(call));

        assertTrue(script.contains("window.__opencodeDispatch('todos.updated', ['ses_1', '[]'])"));
        assertTrue(script.contains("window.onTodoUpdated('ses_1', '[]')"));
    }

    @Test
    public void mergesAdjacentContentDeltasIntoOneOrderedCall() {
        AtomicReference<Object> browser = new AtomicReference<>(new Object());
        AtomicBoolean disposed = new AtomicBoolean();
        List<Runnable> scheduled = new ArrayList<>();
        List<String> scripts = new ArrayList<>();
        WebviewEventQueue<Object> queue = newQueue(browser, disposed, scheduled, scripts);

        queue.enqueue("onContentDelta", "a");
        queue.enqueue("onContentDelta", "b");

        assertEquals(1, scheduled.size());
        scheduled.remove(0).run();

        assertEquals(1, scripts.size());
        assertTrue(scripts.get(0).contains("window.onContentDelta('ab')"));
        queue.dispose();
    }

    @Test
    public void coalescesALargeDeltaBurstIntoOneBoundedDispatch() {
        AtomicReference<Object> browser = new AtomicReference<>(new Object());
        AtomicBoolean disposed = new AtomicBoolean();
        List<Runnable> scheduled = new ArrayList<>();
        List<String> scripts = new ArrayList<>();
        WebviewEventQueue<Object> queue = newQueue(browser, disposed, scheduled, scripts);

        for (int i = 0; i < 5_000; i++) {
            queue.enqueue("onContentDelta", "x");
        }

        assertEquals(1, scheduled.size());
        scheduled.remove(0).run();
        assertEquals(1, scripts.size());
        assertTrue(scripts.get(0).contains("window.__opencodeDispatch('content.delta'"));
        queue.dispose();
    }

    @Test
    public void mergesAdjacentContentDeltasWithTargetSessionIdWithoutCorruptingSessionId() {
        AtomicReference<Object> browser = new AtomicReference<>(new Object());
        AtomicBoolean disposed = new AtomicBoolean();
        List<Runnable> scheduled = new ArrayList<>();
        List<String> scripts = new ArrayList<>();
        WebviewEventQueue<Object> queue = newQueue(browser, disposed, scheduled, scripts);

        queue.enqueue("onContentDelta", "ses_123", "Hello ");
        queue.enqueue("onContentDelta", "ses_123", "World!");

        assertEquals(1, scheduled.size());
        scheduled.remove(0).run();

        assertEquals(1, scripts.size());
        assertTrue(scripts.get(0).contains("window.onContentDelta('ses_123', 'Hello World!')"));
        assertFalse(scripts.get(0).contains("ses_123ses_123"));
        queue.dispose();
    }

    @Test
    public void doesNotMergeDeltasOfDifferentSessions() {
        AtomicReference<Object> browser = new AtomicReference<>(new Object());
        AtomicBoolean disposed = new AtomicBoolean();
        List<Runnable> scheduled = new ArrayList<>();
        List<String> scripts = new ArrayList<>();
        WebviewEventQueue<Object> queue = newQueue(browser, disposed, scheduled, scripts);

        queue.enqueue("onContentDelta", "ses_1", "part1");
        queue.enqueue("onContentDelta", "ses_2", "part2");

        assertEquals(1, scheduled.size());
        scheduled.remove(0).run();

        assertEquals(1, scripts.size());
        assertTrue(scripts.get(0).contains("window.onContentDelta('ses_1', 'part1')"));
        assertTrue(scripts.get(0).contains("window.onContentDelta('ses_2', 'part2')"));
        queue.dispose();
    }

    @Test
    public void mergesTailLatestOnlyEvents() {
        AtomicReference<Object> browser = new AtomicReference<>(new Object());
        AtomicBoolean disposed = new AtomicBoolean();
        List<Runnable> scheduled = new ArrayList<>();
        List<String> scripts = new ArrayList<>();
        WebviewEventQueue<Object> queue = newQueue(browser, disposed, scheduled, scripts);

        queue.enqueue("updateUsage", "{\"percentage\":10}");
        queue.enqueue("updateUsage", "{\"percentage\":20}");

        assertEquals(1, scheduled.size());
        scheduled.remove(0).run();

        assertEquals(1, scripts.size());
        assertTrue(scripts.get(0).contains("window.updateUsage('{\"percentage\":20}')"));
        assertFalse(scripts.get(0).contains("window.updateUsage('{\"percentage\":10}')"));
        queue.dispose();
    }

    @Test
    public void keepsSetSessionIdForEverySessionInTheSameBatch() {
        AtomicReference<Object> browser = new AtomicReference<>(new Object());
        AtomicBoolean disposed = new AtomicBoolean();
        List<Runnable> scheduled = new ArrayList<>();
        List<String> scripts = new ArrayList<>();
        WebviewEventQueue<Object> queue = newQueue(browser, disposed, scheduled, scripts);

        queue.enqueue("setSessionId", "ses_new_a", "tab-1");
        queue.enqueue("setSessionId", "ses_new_b", "tab-2");

        assertEquals(1, scheduled.size());
        scheduled.remove(0).run();

        assertEquals(1, scripts.size());
        String script = scripts.get(0);
        assertTrue(script.contains("window.setSessionId('ses_new_a', 'tab-1')"));
        assertTrue(script.contains("window.setSessionId('ses_new_b', 'tab-2')"));
        queue.dispose();
    }

    @Test
    public void preservesLifecycleOrderInsteadOfCollapsingStateAcrossBoundaries() {
        AtomicReference<Object> browser = new AtomicReference<>(new Object());
        AtomicBoolean disposed = new AtomicBoolean();
        List<Runnable> scheduled = new ArrayList<>();
        List<String> scripts = new ArrayList<>();
        WebviewEventQueue<Object> queue = newQueue(browser, disposed, scheduled, scripts);

        queue.enqueue("showLoading", "true");
        queue.enqueue("onStreamStart");
        queue.enqueue("showLoading", "false");
        scheduled.remove(0).run();

        String script = scripts.get(0);
        assertTrue(script.indexOf("window.showLoading('true')")
                < script.indexOf("window.onStreamStart()"));
        assertTrue(script.indexOf("window.onStreamStart()")
                < script.indexOf("window.showLoading('false')"));
        queue.dispose();
    }

    @Test
    public void separatesSnapshotFromStreamStartSoStartCannotCancelIt() {
        AtomicReference<Object> browser = new AtomicReference<>(new Object());
        AtomicBoolean disposed = new AtomicBoolean();
        List<Runnable> scheduled = new ArrayList<>();
        List<String> scripts = new ArrayList<>();
        WebviewEventQueue<Object> queue = newQueue(browser, disposed, scheduled, scripts);

        queue.enqueue("updateMessages", "snapshot", "1");
        queue.enqueue("onStreamStart");
        scheduled.remove(0).run();
        scheduled.remove(0).run();

        assertEquals(2, scripts.size());
        assertTrue(scripts.get(0).contains("window.updateMessages('snapshot', '1')"));
        assertTrue(scripts.get(1).contains("window.onStreamStart()"));
        queue.dispose();
    }

    @Test
    public void executesRawScriptInBatch() {
        AtomicReference<Object> browser = new AtomicReference<>(new Object());
        AtomicBoolean disposed = new AtomicBoolean();
        List<Runnable> scheduled = new ArrayList<>();
        List<String> scripts = new ArrayList<>();
        WebviewEventQueue<Object> queue = newQueue(browser, disposed, scheduled, scripts);

        queue.enqueueRaw("console.log('test-raw');");
        queue.enqueue("onModelConfirmed", "claude-3-7-sonnet", "opencode");
        scheduled.remove(0).run();

        assertEquals(1, scripts.size());
        String batch = scripts.get(0);
        assertTrue(batch.contains("console.log('test-raw');"));
        assertTrue(batch.contains("window.onModelConfirmed('claude-3-7-sonnet', 'opencode')"));
        queue.dispose();
    }

    @Test
    public void clearsPendingOnBrowserChanged() {
        AtomicReference<Object> browser = new AtomicReference<>(new Object());
        AtomicBoolean disposed = new AtomicBoolean();
        List<Runnable> scheduled = new ArrayList<>();
        List<String> scripts = new ArrayList<>();
        WebviewEventQueue<Object> queue = newQueue(browser, disposed, scheduled, scripts);

        queue.enqueue("onContentDelta", "hello");
        browser.set(new Object());
        queue.browserChanged();

        scheduled.remove(0).run();
        assertEquals(0, scripts.size());
        queue.dispose();
    }

    private static WebviewEventQueue<Object> newQueue(
            AtomicReference<Object> browser,
            AtomicBoolean disposed,
            List<Runnable> scheduled,
            List<String> scripts
    ) {
        return new WebviewEventQueue<>(
                browser::get,
                disposed::get,
                scheduled::add,
                (ignoredBrowser, script) -> scripts.add(script)
        );
    }
}
