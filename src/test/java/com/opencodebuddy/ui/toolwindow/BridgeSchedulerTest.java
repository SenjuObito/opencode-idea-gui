package com.opencodebuddy.ui.toolwindow;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BridgeSchedulerTest {

    private static BridgeScheduler<Object> newScheduler(
            AtomicReference<Object> browser,
            AtomicBoolean disposed,
            List<Runnable> scheduled,
            List<String> scripts
    ) {
        return new BridgeScheduler<>(
                browser::get,
                disposed::get,
                scheduled::add,
                (ignored, script) -> scripts.add(script)
        );
    }

    private static int position(String script, String needle) {
        int index = script.indexOf(needle);
        assertTrue("missing " + needle + " in " + script, index >= 0);
        return index;
    }

    @Test
    public void activeLaneGetsThreeCallsPerPassAndDoesNotStarveBackgroundLane() {
        AtomicReference<Object> browser = new AtomicReference<>(new Object());
        AtomicBoolean disposed = new AtomicBoolean();
        List<Runnable> scheduled = new ArrayList<>();
        List<String> scripts = new ArrayList<>();
        BridgeScheduler<Object> scheduler = newScheduler(browser, disposed, scheduled, scripts);
        scheduler.setLaneResolver(args -> args[0]);
        scheduler.setActiveLaneSupplier(() -> "A");

        for (int i = 1; i <= 5; i++) {
            scheduler.enqueue("probe", "A", "a" + i);
            scheduler.enqueue("probe", "B", "b" + i);
        }

        scheduled.remove(0).run();
        assertEquals(1, scripts.size());
        String script = scripts.get(0);

        assertTrue(position(script, "a1") < position(script, "a2"));
        assertTrue(position(script, "a2") < position(script, "a3"));
        assertTrue(position(script, "a3") < position(script, "b1"));
        assertTrue("background lane must run before the active lane's fourth call",
                position(script, "b1") < position(script, "a4"));
        scheduler.dispose();
    }

    @Test
    public void buildsATypedEnvelopeWithTabAndEpoch() {
        BridgeScheduler.JsCall<Object> call = new BridgeScheduler.JsCall<>(
                new Object(), "onContentDelta", new String[]{"ses_1", "hi"}, null, "tab-a", 7L);

        String script = BridgeScheduler.buildBatchScript(List.of(call));

        assertTrue(script.contains("\"v\":2"));
        // The protocol names the event semantically; the legacy function name
        // travels next to it so the webview can still resolve the handler.
        assertTrue(script.contains("\"type\":\"content.delta\""));
        assertTrue(script.contains("\"fn\":\"onContentDelta\""));
        assertTrue(script.contains("\"sessionId\":\"ses_1\""));
        assertTrue(script.contains("\"tabId\":\"tab-a\""));
        assertTrue(script.contains("\"epoch\":7"));
        assertTrue(script.contains("\"args\":[\"ses_1\",\"hi\"]"));
        assertTrue("legacy fallback must remain", script.contains("window.onContentDelta('ses_1', 'hi')"));
    }

    @Test
    public void escapedJsonArgIsDecodedOnceForTheEnvelope() {
        String json = "{\"alive\":true,\"serveReady\":true}";
        BridgeScheduler.JsCall<Object> call = new BridgeScheduler.JsCall<>(
                new Object(), "updateDaemonStatus", new String[]{
                        com.opencodebuddy.util.JsUtils.escapeJs(json)}, null, null, null);

        String script = BridgeScheduler.buildBatchScript(List.of(call));

        assertTrue(script.contains("\"args\":[\"{\\\"alive\\\":true,\\\"serveReady\\\":true}\"]"));
        assertTrue("legacy fallback still relies on JS literal decoding",
                script.contains("window.updateDaemonStatus('{\\\"alive\\\":true,\\\"serveReady\\\":true}')"));
    }

    @Test
    public void rawJsonArgIsNotDecodedAgain() {
        String json = "{\"path\":\"C:\\\\tmp\",\"text\":\"a\\nb\"}";
        BridgeScheduler.JsCall<Object> call = new BridgeScheduler.JsCall<>(
                new Object(), "onCommandsList", new String[]{json}, null, null, null);

        String script = BridgeScheduler.buildBatchScript(List.of(call));

        assertTrue(script.contains("\"args\":[\"{\\\"path\\\":\\\"C:\\\\\\\\tmp\\\",\\\"text\\\":\\\"a\\\\nb\\\"}\"]"));
    }

    @Test
    public void separatesASnapshotFromAStreamStartInTheSameLane() {
        AtomicReference<Object> browser = new AtomicReference<>(new Object());
        AtomicBoolean disposed = new AtomicBoolean();
        List<Runnable> scheduled = new ArrayList<>();
        List<String> scripts = new ArrayList<>();
        BridgeScheduler<Object> scheduler = newScheduler(browser, disposed, scheduled, scripts);

        scheduler.enqueue("updateMessages", "snapshot", "1");
        scheduler.enqueue("onStreamStart");

        scheduled.remove(0).run();
        scheduled.remove(0).run();

        assertEquals(2, scripts.size());
        assertTrue(scripts.get(0).contains("window.updateMessages('snapshot', '1')"));
        assertTrue(scripts.get(1).contains("window.onStreamStart()"));
        scheduler.dispose();
    }

    @Test
    public void droppingADeltaMarksTheLaneForSnapshotResync() {
        AtomicReference<Object> browser = new AtomicReference<>(new Object());
        AtomicBoolean disposed = new AtomicBoolean();
        List<Runnable> scheduled = new ArrayList<>();
        List<String> scripts = new ArrayList<>();
        BridgeScheduler<Object> scheduler = newScheduler(browser, disposed, scheduled, scripts);
        scheduler.setLaneResolver(args -> "tab-a");
        List<String> resync = new ArrayList<>();
        scheduler.setSnapshotRequiredListener(resync::add);

        for (int i = 0; i < 300; i++) {
            scheduler.enqueue("onContentDelta", "x");
            scheduler.enqueue("probe", "filler-" + i);
        }

        assertTrue(scheduler.isSnapshotRequired("tab-a"));
        assertFalse(resync.isEmpty());
        assertEquals("tab-a", resync.get(0));
        scheduler.dispose();
    }

    @Test
    public void rawScriptBatchesKeepLegacyPerCallOrdering() {
        AtomicReference<Object> browser = new AtomicReference<>(new Object());
        AtomicBoolean disposed = new AtomicBoolean();
        List<Runnable> scheduled = new ArrayList<>();
        List<String> scripts = new ArrayList<>();
        BridgeScheduler<Object> scheduler = newScheduler(browser, disposed, scheduled, scripts);

        scheduler.enqueueRaw("console.log('raw');");
        scheduler.enqueue("probe", "one");
        scheduler.enqueue("probe", "two");

        scheduled.remove(0).run();
        String script = scripts.get(0);
        assertTrue(position(script, "console.log('raw');") < position(script, "window.probe('one')"));
        assertTrue(position(script, "window.probe('one')") < position(script, "window.probe('two')"));
        scheduler.dispose();
    }

    @Test
    public void hugeFirstLaneDoesNotStarveLaterLanesAcrossBatches() {
        AtomicReference<Object> browser = new AtomicReference<>(new Object());
        AtomicBoolean disposed = new AtomicBoolean();
        List<Runnable> scheduled = new ArrayList<>();
        List<String> scripts = new ArrayList<>();
        BridgeScheduler<Object> scheduler = newScheduler(browser, disposed, scheduled, scripts);
        scheduler.setLaneResolver(args -> args[0]);
        scheduler.setActiveLaneSupplier(() -> "A");

        String huge = "x".repeat(200_000);
        scheduler.enqueue("probe", "A", huge + "a1");
        scheduler.enqueue("probe", "A", huge + "a2");
        scheduler.enqueue("probe", "B", "b1");

        scheduled.remove(0).run();
        assertEquals(1, scripts.size());
        assertTrue("a huge call fills the first batch", scripts.get(0).contains("a1"));
        assertFalse("B cannot fit next to a 200k call", scripts.get(0).contains("b1"));

        scheduled.remove(0).run();
        assertTrue("the next batch must start at B", scripts.get(1).contains("b1"));
        scheduler.dispose();
    }

    @Test
    public void terminalEventsAreKeptWhenALaneIsFull() {
        AtomicReference<Object> browser = new AtomicReference<>(new Object());
        AtomicBoolean disposed = new AtomicBoolean();
        List<Runnable> scheduled = new ArrayList<>();
        List<String> scripts = new ArrayList<>();
        BridgeScheduler<Object> scheduler = newScheduler(browser, disposed, scheduled, scripts);

        for (int i = 0; i < 257; i++) {
            scheduler.enqueue("showPermissionDialog", "permission-" + i);
        }
        scheduled.remove(0).run();
        while (!scheduled.isEmpty()) {
            scheduled.remove(0).run();
        }

        assertEquals("the 257th terminal event must survive a full lane",
                257, count(String.join("", scripts), "\"type\":\"permission.requested\""));
        scheduler.dispose();
    }

    /**
     * Host-side half of the 6-tab / 3-stream / 10k-message benchmark.
     *
     * <p>JCEF itself needs the IDE runtime (see
     * {@code docs/jcef-benchmark.md}), but the part that decides how many
     * scripts the renderer receives is pure Java. This pins the two properties
     * the real run is meant to show: 10k stream chunks collapse into one
     * envelope per streaming tab, and 10k discrete messages stay ordered and
     * bounded per batch.</p>
     */
    @Test(timeout = 30_000)
    public void sixTabsThreeStreamsTenThousandMessagesStayBounded() {
        AtomicReference<Object> browser = new AtomicReference<>(new Object());
        AtomicBoolean disposed = new AtomicBoolean();
        Deque<Runnable> scheduled = new ArrayDeque<>();
        List<String> scripts = new ArrayList<>();
        BridgeScheduler<Object> scheduler = new BridgeScheduler<>(
                browser::get,
                disposed::get,
                scheduled::add,
                (ignored, script) -> scripts.add(script));
        scheduler.setLaneResolver(args -> args[0]);
        scheduler.setActiveLaneSupplier(() -> "tab-1");
        scheduler.setEpochResolver(lane -> 1L);

        final int tabs = 6;
        final int streams = 3;
        final int messages = 10_000;

        long started = System.nanoTime();
        for (int i = 0; i < messages; i++) {
            scheduler.enqueue("onContentDelta", "tab-" + (i % streams + 1), "chunk-" + i + ";");
        }
        drainAll(scheduled);

        int deltaScripts = scripts.size();
        String merged = String.join("", scripts);
        int[] lastChunk = {messages - 1, messages - 3, messages - 2};
        for (int lane = 1; lane <= streams; lane++) {
            assertTrue("missing first chunk for tab-" + lane,
                    merged.contains("chunk-" + (lane - 1) + ";"));
            assertTrue("missing last chunk for tab-" + lane,
                    merged.contains("chunk-" + lastChunk[lane - 1] + ";"));
        }

        scripts.clear();
        for (int i = 0; i < messages; i++) {
            scheduler.enqueue("probe", "tab-" + (i % tabs + 1), "probe-" + i);
            if (i % 32 == 0) {
                drainAll(scheduled);
            }
        }
        drainAll(scheduled);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        String all = String.join("", scripts);
        int probes = count(all, "\"type\":\"probe\"");
        assertEquals("one envelope per discrete message", messages, probes);
        assertTrue("a batch must stay bounded", scripts.size() < messages);
        for (String script : scripts) {
            assertTrue(script.startsWith("(function() {"));
        }

        System.out.println("[BridgeBenchmark] tabs=" + tabs + " streams=" + streams
                + " streamChunks=" + messages + " deltaScripts=" + deltaScripts
                + " discreteMessages=" + messages + " scripts=" + scripts.size()
                + " bytes=" + all.length() + " elapsedMs=" + elapsedMs);
        scheduler.dispose();
    }

    private static void drainAll(Deque<Runnable> scheduled) {
        Runnable next;
        while ((next = scheduled.poll()) != null) {
            next.run();
        }
    }

    private static int count(String haystack, String needle) {
        int total = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            total++;
            index += needle.length();
        }
        return total;
    }
}
