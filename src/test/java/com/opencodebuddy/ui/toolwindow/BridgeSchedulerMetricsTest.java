package com.opencodebuddy.ui.toolwindow;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BridgeSchedulerMetricsTest {

    private static final class Harness {
        final List<Runnable> scheduled = new ArrayList<>();
        final List<String> scripts = new ArrayList<>();
        final BridgeScheduler<String> scheduler;

        Harness() {
            scheduler = new BridgeScheduler<>(
                    () -> "browser",
                    () -> false,
                    scheduled::add,
                    (browser, script) -> scripts.add(script));
        }

        void drainScheduled() {
            List<Runnable> copy = new ArrayList<>(scheduled);
            scheduled.clear();
            copy.forEach(Runnable::run);
        }
    }

    @Test
    public void reportsQueueDepthAndDeliveredFrames() {
        Harness h = new Harness();
        h.scheduler.enqueue("onFoo", "a");
        h.scheduler.enqueue("onBar", "b");

        BridgeScheduler.Metrics queued = h.scheduler.metrics();
        assertEquals(2, queued.enqueued);
        assertEquals(2, queued.queueDepth);
        assertEquals(0, queued.delivered);

        h.drainScheduled();

        BridgeScheduler.Metrics sent = h.scheduler.metrics();
        assertEquals(2, sent.delivered);
        assertEquals(1, sent.frames);
        assertEquals(0, sent.queueDepth);
        assertTrue(sent.lastFrameChars > 0);
        assertEquals(1, h.scripts.size());
    }

    @Test
    public void countsMergedDeltasWithoutQueueGrowth() {
        Harness h = new Harness();
        h.scheduler.enqueue("onContentDelta", "ses_1", "a");
        h.scheduler.enqueue("onContentDelta", "ses_1", "b");

        BridgeScheduler.Metrics metrics = h.scheduler.metrics();
        assertEquals(2, metrics.enqueued);
        assertEquals(1, metrics.merged);
        assertEquals(1, metrics.queueDepth);
    }

    @Test
    public void countsADroppedCallWhenALaneIsFull() {
        Harness h = new Harness();
        for (int i = 0; i < 257; i++) {
            h.scheduler.enqueue("onFoo", "arg-" + i);
        }

        BridgeScheduler.Metrics metrics = h.scheduler.metrics();
        assertEquals(257, metrics.enqueued);
        assertEquals(1, metrics.dropped);
        assertEquals(256, metrics.queueDepth);
    }
}
