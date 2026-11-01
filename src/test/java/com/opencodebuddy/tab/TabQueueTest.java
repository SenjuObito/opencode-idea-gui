package com.opencodebuddy.tab;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The outbox invariants: one consumer, one in-flight item, and a failure that
 * stays visible instead of swallowing the item.
 */
public class TabQueueTest {

    /** A window stand-in that records what the pump reports. */
    private static final class RecordingHost implements TabQueuePump.Host {
        final TabRecord record;
        final List<String> reports = new ArrayList<>();
        final List<String> sends = new ArrayList<>();
        final List<String> commands = new ArrayList<>();

        RecordingHost(TabRecord record) {
            this.record = record;
        }

        @Override
        public TabRecord getTab(String tabId) {
            return record;
        }

        @Override
        public List<TabRecord> liveTabs() {
            return List.of(record);
        }

        @Override
        public void publishQueue(TabRecord changed) {
        }

        @Override
        public void reportError(TabRecord target, String message) {
            reports.add(message);
        }

        @Override
        public boolean isDaemonAlive() {
            return true;
        }

        @Override
        public boolean hasPendingPrompt(String tabId) {
            return false;
        }

        @Override
        public TabQueuePump.Sender sender() {
            return (tabId, payload, requestId) -> {
                sends.add(payload.get("text").getAsString());
                return TabQueuePump.Outcome.accepted();
            };
        }

        @Override
        public TabQueuePump.Outcome executeCommand(TabRecord target, TabQueue.Item item) {
            commands.add(item.command);
            return TabQueuePump.Outcome.accepted();
        }
    }

    private static TabQueue.Item item(String id, String text) {
        return new TabQueue.Item(id, text, null, null, null, null, null, null, 0L, 1L);
    }

    private static TabQueue.Item command(String id, String command) {
        return new TabQueue.Item(id, command, command, TabQueue.CommandArgs.NONE,
                List.of(), List.of(), null, null, null, null, 0L, 1L);
    }

    @Test
    public void takeIsAtomic() {
        TabQueue queue = new TabQueue();
        assertTrue(queue.enqueue(item("q1", "one")).ok());

        assertEquals("q1", queue.take().id);
        assertNull(queue.take());
    }

    @Test
    public void aFailedSendGoesBackToTheHeadAndReports() {
        TabQueue queue = new TabQueue();
        queue.enqueue(item("q1", "one"));
        queue.enqueue(item("q2", "two"));

        queue.take();
        queue.fail("boom");

        assertNull(queue.getInflightId());
        assertEquals("q1", queue.items().get(0).id);
        assertEquals("q2", queue.items().get(1).id);
        assertEquals("boom", queue.getError().message());

        assertTrue(queue.remove("q1"));
        assertNull(queue.getError());
    }

    @Test
    public void aConfirmedResumeRetriesTheFailedItem() {
        TabQueue queue = new TabQueue();
        queue.enqueue(item("q1", "one"));
        queue.take();
        queue.fail("boom");
        queue.pause();

        queue.resume();

        assertFalse(queue.isPaused());
        assertNull(queue.getError());
        assertEquals("q1", queue.take().id);
    }

    @Test
    public void pausingAnEmptyQueueDoesNotSetPausedFlag() {
        TabQueue queue = new TabQueue();
        queue.pause();
        assertFalse("an empty queue cannot be paused", queue.isPaused());

        // Queueing an item later is immediately ready to be consumed
        queue.enqueue(item("q1", "one"));
        assertEquals("q1", queue.take().id);
    }

    @Test
    public void clearingOrRemovingAllItemsResetsPausedFlag() {
        TabQueue queue = new TabQueue();
        queue.enqueue(item("q1", "one"));
        queue.pause();
        assertTrue(queue.isPaused());

        queue.remove("q1");
        assertFalse("removing the last item clears the pause flag", queue.isPaused());

        queue.enqueue(item("q2", "two"));
        queue.pause();
        assertTrue(queue.isPaused());

        queue.clear();
        assertFalse("clear clears the pause flag", queue.isPaused());
    }

    @Test
    public void pumpPauseIgnoresEmptyQueue() {
        TabRecord record = new TabRecord(TabId.of("tab-a"), "/mock/workspace");
        RecordingHost host = new RecordingHost(record);
        TabQueuePump pump = new TabQueuePump(host);

        pump.pause(record);
        assertFalse(record.getQueue().isPaused());

        // Subsequent enqueue immediately sends because queue was never paused
        pump.enqueue(record, item("q1", "first"));
        await("the prompt to be sent", () -> host.sends.size() == 1);
        assertEquals(List.of("first"), host.sends);
    }

    @Test
    public void theSameIdCannotBeQueuedTwice() {
        TabQueue queue = new TabQueue();
        assertTrue(queue.enqueue(item("q1", "one")).ok());
        queue.take();

        TabQueue.EnqueueResult duplicate = queue.enqueue(item("q1", "one again"));
        assertFalse(duplicate.ok());
    }

    @Test
    public void anEmptyItemIsRefused() {
        TabQueue queue = new TabQueue();
        assertFalse(queue.enqueue(item("q1", "   ")).ok());
    }

    @Test
    public void theProjectionCarriesThePauseFlagAndNoStaleError() {
        TabQueue queue = new TabQueue();
        queue.enqueue(item("q1", "one"));
        queue.enqueue(item("q2", "two"));
        queue.take();
        queue.fail("boom");
        queue.resume();
        queue.pause();

        JsonObject json = queue.toJson();

        assertEquals(2, json.getAsJsonArray("items").size());
        assertTrue(json.get("lastError").isJsonNull());
        assertTrue(json.get("paused").getAsBoolean());
    }

    @Test
    public void theProjectionListsOnlyWaitingItems() {
        TabQueue queue = new TabQueue();
        queue.enqueue(item("q1", "one"));
        queue.enqueue(item("q2", "two"));

        assertEquals(2, queue.toJson().getAsJsonArray("items").size());

        // The item being sent is no longer queued: the projection must not list it.
        queue.take();

        JsonObject json = queue.toJson();
        JsonArray items = json.getAsJsonArray("items");
        assertEquals(1, items.size());
        assertEquals("q2", items.get(0).getAsJsonObject().get("id").getAsString());
        assertFalse(json.has("inflight"));
    }

    @Test
    public void onSendRejectedPutsBackOnlyTheRejectedItem() {
        TabRecord record = new TabRecord(TabId.of("tab-a"), "/mock/workspace");
        RecordingHost host = new RecordingHost(record);
        TabQueuePump pump = new TabQueuePump(host);

        record.getQueue().enqueue(item("q1", "one"));
        record.getQueue().enqueue(item("q2", "two"));
        record.getQueue().take();

        pump.onSendRejected(record, "q2", "not mine");
        assertEquals("q1", record.getQueue().getInflightId());
        assertNull(record.getQueue().getError());
        assertTrue(host.reports.isEmpty());

        pump.onSendRejected(record, "q1", "boom");
        assertNull(record.getQueue().getInflightId());
        assertEquals("q1", record.getQueue().items().get(0).id);
        assertEquals("boom", record.getQueue().getError().message());
        assertEquals(List.of("boom"), host.reports);
    }

    /**
     * The pump drains through the tab actor, which runs on its own thread: a
     * test must wait for the effect instead of assuming it already happened.
     */
    private static void await(String what, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() >= deadline) {
                fail("timed out waiting for " + what);
            }
            try {
                Thread.sleep(5L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted while waiting for " + what);
            }
        }
    }

    @Test
    public void aQueuedCommandRunsBetweenThePromptsAroundIt() {
        TabRecord record = new TabRecord(TabId.of("tab-a"), "/mock/workspace");
        RecordingHost host = new RecordingHost(record);
        TabQueuePump pump = new TabQueuePump(host);

        pump.enqueue(record, item("q1", "first"));
        pump.enqueue(record, command("q2", "/compact"));
        pump.enqueue(record, item("q3", "third"));

        await("the first prompt to be sent", () -> host.sends.size() == 1);
        assertEquals(List.of("first"), host.sends);
        assertEquals(List.of("q2", "q3"), record.getQueue().items().stream().map(i -> i.id).toList());

        // The first turn ends: the command runs before the next prompt.
        pump.onTabSettled(record);
        await("the command to be dispatched", () -> !host.commands.isEmpty());
        assertEquals(List.of("/compact"), host.commands);
        assertEquals("q2", record.getQueue().getInflightId());
        assertEquals("/compact", record.getQueue().getInflightCommand());
        assertTrue(record.getQueue().isCompacting());
        assertEquals(List.of("first"), host.sends);

        // A turn settlement must not release a command item early.
        pump.onTabSettled(record);
        assertEquals("q2", record.getQueue().getInflightId());

        // Only its own result releases it, then the next prompt is sent.
        pump.onCommandSettled(record, java.util.Set.of("/compact"), "q1", true, null);
        assertEquals("a foreign request id is ignored", "q2", record.getQueue().getInflightId());
        pump.onCommandSettled(record, java.util.Set.of("/compact"), "q2", true, null);
        await("the next prompt to be sent", () -> host.sends.size() == 2);
        assertNull(record.getQueue().getInflightCommand());
        assertFalse(record.getQueue().isCompacting());
        assertEquals(List.of("first", "third"), host.sends);
    }

    @Test
    public void aFailedCommandGoesBackToTheHeadUntilTheUserResumes() {
        TabRecord record = new TabRecord(TabId.of("tab-a"), "/mock/workspace");
        RecordingHost host = new RecordingHost(record);
        TabQueuePump pump = new TabQueuePump(host);

        pump.enqueue(record, command("q1", "/compact"));
        await("the command to be in flight", () -> "q1".equals(record.getQueue().getInflightId()));

        pump.onCommandSettled(record, java.util.Set.of("/compact"), "q1", false, "boom");

        assertNull(record.getQueue().getInflightId());
        assertEquals("q1", record.getQueue().items().get(0).id);
        assertEquals("boom", record.getQueue().getError().message());
        assertEquals(List.of("boom"), host.reports);
        assertFalse(record.getQueue().isCompacting());

        pump.resume(record);
        await("the command to be retried", () -> host.commands.size() == 2);
        assertEquals("the same command is retried", List.of("/compact", "/compact"), host.commands);
    }

    @Test
    public void theProjectionCarriesTheCommand() {
        TabQueue queue = new TabQueue();
        queue.enqueue(command("q1", "/undo"));

        JsonObject item = queue.toJson().getAsJsonArray("items").get(0).getAsJsonObject();
        assertEquals("/undo", item.get("command").getAsString());
        assertEquals("/undo", item.get("text").getAsString());

        // An arbitrary string can never be queued as a command.
        TabQueue.Item forged = new TabQueue.Item("q2", "x", "/drop-tables", null,
                List.of(), List.of(), null, null, null, null, 0L, 1L);
        assertNull(forged.command);
    }

    @Test
    public void theProjectionCarriesTextUnderBothNames() {
        // A webview bundle from an older plugin build reads `content`; the
        // current one reads `text`. Dropping either makes the queue render
        // number-only rows, so both must always carry the same value.
        TabQueue queue = new TabQueue();
        queue.enqueue(item("q1", "hello queue"));

        JsonObject item = queue.toJson().getAsJsonArray("items").get(0).getAsJsonObject();
        assertEquals("hello queue", item.get("text").getAsString());
        assertEquals("hello queue", item.get("content").getAsString());
    }

    @Test
    public void aDaemonDeathConsumesTheInflightItemAndKeepsTheRest() {
        TabRecord record = new TabRecord(TabId.of("tab-a"), "/mock/workspace");
        TabQueuePump pump = new TabQueuePump(new RecordingHost(record));

        record.getQueue().enqueue(item("q1", "one"));
        record.getQueue().enqueue(item("q2", "two"));
        record.getQueue().take();

        pump.onDaemonDied();

        assertNull("the dead turn is consumed", record.getQueue().getInflightId());
        assertEquals(1, record.getQueue().size());
        assertEquals("q2", record.getQueue().items().get(0).id);
    }
}
