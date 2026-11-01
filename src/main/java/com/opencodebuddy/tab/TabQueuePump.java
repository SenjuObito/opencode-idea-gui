package com.opencodebuddy.tab;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.opencodebuddy.session.OpencodeSession;

import java.util.List;

/**
 * The single consumer of every tab's queue.
 *
 * <p>Invariants:</p>
 * <ul>
 *   <li>One pump per window, one in-flight item per tab.</li>
 *   <li>FIFO inside a tab; tabs progress independently and in parallel.</li>
 *   <li>Never reads the active tab: the {@link TabRecord} is the routing
 *       decision.</li>
 *   <li>Taking an item is atomic, so repeated drain triggers cannot
 *       double-send.</li>
 * </ul>
 */
public final class TabQueuePump {

    /**
     * The surface the pump needs from the window. Injected instead of reaching
     * into the chat window so the pump stays testable and cannot read "the
     * active tab".
     */
    public interface Host {
        TabRecord getTab(String tabId);

        List<TabRecord> liveTabs();

        /** Push the tab's current queue projection to the views. */
        void publishQueue(TabRecord record);

        /** Report a queue problem to the user (toast in the owning tab). */
        void reportError(TabRecord record, String message);

        boolean isDaemonAlive();

        boolean hasPendingPrompt(String tabId);

        Sender sender();

        /**
         * Dispatch a queued session command (compact/undo/redo/fork/share/unshare).
         * The pump keeps the item in flight until the command publishes its own
         * result, so a queued prompt can never overtake it.
         */
        Outcome executeCommand(TabRecord record, TabQueue.Item item);
    }

    /** Hands one frozen item to the normal {@code session.send} path. */
    public interface Sender {
        Outcome send(String tabId, JsonObject payload, String requestId);
    }

    /** Whether the send was accepted by the tab's session. */
    public record Outcome(boolean ok, String code, String message) {
        public static Outcome accepted() {
            return new Outcome(true, null, null);
        }

        public static Outcome refused(String code, String message) {
            return new Outcome(false, code, message);
        }
    }

    private final Host host;

    public TabQueuePump(Host host) {
        this.host = host;
    }

    /** Append an item and try to consume it right away. */
    public TabQueue.EnqueueResult enqueue(TabRecord record, TabQueue.Item item) {
        TabQueue.EnqueueResult queued = record.getQueue().enqueue(item);
        if (!queued.ok()) {
            host.publishQueue(record);
            return queued;
        }
        host.publishQueue(record);
        drain(record);
        return queued;
    }

    public void remove(TabRecord record, String itemId) {
        if (!record.getQueue().remove(itemId)) {
            return;
        }
        host.publishQueue(record);
        drain(record);
    }

    public void clear(TabRecord record) {
        record.getQueue().clear();
        host.publishQueue(record);
    }

    /**
     * Drop everything because the tab is switching conversations.
     *
     * @return the dropped items, whose attachments the caller must release
     */
    public List<TabQueue.Item> clearForSessionSwitch(TabRecord record) {
        List<TabQueue.Item> removed = record.getQueue().clearAll();
        if (removed.isEmpty()) {
            return removed;
        }
        host.publishQueue(record);
        host.reportError(record, "会话已切换，" + removed.size() + " 条排队消息已清空");
        return removed;
    }

    public void pause(TabRecord record) {
        if (record == null || record.getQueue().isPaused() || record.getQueue().size() == 0) {
            return;
        }
        record.getQueue().pause();
        host.publishQueue(record);
    }

    public void resume(TabRecord record) {
        // A failed item is put back at the head with its error still set; an
        // explicit "continue" is what retries it, so the error counts as a
        // reason to resume even when the queue was never paused.
        if (record == null
                || (!record.getQueue().isPaused() && record.getQueue().getError() == null)) {
            return;
        }
        record.getQueue().resume();
        host.publishQueue(record);
        drain(record);
    }

    /**
     * A turn reached its terminal state. The in-flight item is consumed; the
     * next item waits for this event instead of a timer, so exactly one turn is
     * sent per settlement.
     */
    public void onTabSettled(TabRecord record) {
        if (record == null) {
            return;
        }
        // A command item ends on its own result event; a turn settlement must
        // not release it early (a compact or fork can outlive the turn that
        // triggered the drain).
        if (record.getQueue().getInflightId() != null
                && record.getQueue().getInflightCommand() == null) {
            record.getQueue().complete();
            host.publishQueue(record);
        }
        drain(record);
    }

    /**
     * The result of a queued command arrived.
     *
     * <p>Only the item whose command and request id match may be released: the
     * same event can be published by a direct header action while another
     * command is queued.</p>
     */
    public void onCommandSettled(
            TabRecord record,
            java.util.Set<String> expected,
            String requestId,
            boolean ok,
            String message
    ) {
        if (record == null) {
            return;
        }
        String inflightCommand = record.getQueue().getInflightCommand();
        String inflightId = record.getQueue().getInflightId();
        if (inflightCommand == null || inflightId == null || !expected.contains(inflightCommand)) {
            return;
        }
        // A result without our request id is not ours: `revert.changed` is also
        // published by replay and by direct header actions.
        if (requestId == null || !requestId.equals(inflightId)) {
            return;
        }
        record.getQueue().setCompacting(false);
        if (ok) {
            record.getQueue().complete();
            host.publishQueue(record);
            drain(record);
            return;
        }
        String reason = message != null && !message.isBlank()
                ? message
                : inflightCommand + " 执行失败";
        record.getQueue().fail(reason);
        host.publishQueue(record);
        host.reportError(record, reason);
    }

    /** A non-terminal "maybe idle now" signal (resume, daemon ready, replay). */
    public void onTabIdle(TabRecord record) {
        if (record != null) {
            drain(record);
        }
    }

    /**
     * A {@code session.send} was rejected before the turn started.
     *
     * <p>The in-flight item would otherwise wait forever for a settlement that
     * never comes. Only the rejected item is put back; a rejection of another
     * request must not disturb the queue.</p>
     */
    public void onSendRejected(TabRecord record, String requestId, String message) {
        if (record == null || requestId == null) {
            return;
        }
        if (!requestId.equals(record.getQueue().getInflightId())) {
            return;
        }
        record.getQueue().fail(message != null && !message.isBlank() ? message : "发送失败");
        host.publishQueue(record);
        if (message != null && !message.isBlank()) {
            host.reportError(record, message);
        }
    }

    /**
     * The daemon died: the in-flight sends cannot settle, so they are treated
     * as consumed. Waiting items are kept — the daemon restarting is not the
     * user throwing them away.
     */
    public void onDaemonDied() {
        for (TabRecord record : host.liveTabs()) {
            if (record.getQueue().getInflightId() == null) {
                continue;
            }
            record.getQueue().complete();
            record.getQueue().setCompacting(false);
            host.publishQueue(record);
        }
    }

    /** Sweep every live tab (daemon restarted, view rebuilt). */
    public void drainAll() {
        for (TabRecord record : host.liveTabs()) {
            drain(record);
        }
    }

    /** Refresh the projection for every live tab (view resync). */
    public void publishAll() {
        for (TabRecord record : host.liveTabs()) {
            host.publishQueue(record);
        }
    }

    private boolean canDrain(TabRecord record) {
        TabQueue queue = record.getQueue();
        if (record.isClosed()) {
            return false;
        }
        TabState state = record.getState();
        // CREATING is deliberately allowed: it means a provisional session
        // exists and the first send is exactly what turns it into a real one.
        // The in-flight guard below already stops a second item from starting.
        if (state == TabState.STREAMING || state == TabState.LOADING_HISTORY) {
            return false;
        }
        OpencodeSession session = record.getSession();
        if (session != null && (session.isBusy() || session.isLoading())) {
            return false;
        }
        if (queue.isPaused() || queue.isCompacting() || queue.getInflightId() != null) {
            return false;
        }
        if (queue.getError() != null) {
            return false;
        }
        if (queue.size() == 0) {
            return false;
        }
        if (!host.isDaemonAlive()) {
            return false;
        }
        // A pending permission/question/plan card must be answered first; the
        // server would otherwise queue our prompt behind a dialog nobody can
        // see.
        if (host.hasPendingPrompt(record.getTabId().value())) {
            return false;
        }
        return true;
    }

    private void drain(TabRecord record) {
        if (!canDrain(record)) {
            return;
        }
        // The router already runs its command on this actor; submitting again
        // only queues this task behind it, which is the ordering we want.
        record.getActor().submit(() -> {
            // Re-check inside the actor: the tab may have started a turn or
            // been closed between the trigger and this task.
            if (!canDrain(record)) {
                return;
            }
            TabQueue.Item item = record.getQueue().take();
            if (item == null) {
                return;
            }
            String tabId = record.getTabId().value();
            String bound = record.getSessionIdString();
            if (item.originSessionId != null && !item.originSessionId.equals(bound)) {
                record.getQueue().fail("会话已切换");
                host.publishQueue(record);
                host.reportError(record, "排队消息所属的会话已切换，已丢弃。");
                return;
            }

            if ("/compact".equals(item.command)) {
                // Compaction is a long request: block the next item until its
                // own result event arrives instead of relying on a busy flag.
                record.getQueue().setCompacting(true);
            }
            host.publishQueue(record);

            if (item.command != null) {
                Outcome dispatched = host.executeCommand(record, item);
                if (dispatched == null || !dispatched.ok()) {
                    record.getQueue().setCompacting(false);
                    String message = dispatched != null && dispatched.message() != null
                            ? dispatched.message() : item.command + " 无法执行";
                    record.getQueue().fail(message);
                    host.publishQueue(record);
                    host.reportError(record, message);
                }
                return;
            }

            Sender sender = host.sender();
            if (sender == null) {
                record.getQueue().fail("宿主发送通道未就绪");
                host.publishQueue(record);
                host.reportError(record, "发送通道未就绪，排队消息已保留。");
                return;
            }

            Outcome outcome = sender.send(tabId, toPayload(record, item), item.id);
            if (outcome == null || !outcome.ok()) {
                String message = outcome != null && outcome.message() != null
                        ? outcome.message() : "发送失败";
                record.getQueue().fail(message);
                host.publishQueue(record);
                host.reportError(record, message);
                return;
            }
            host.publishQueue(record);
        });
    }

    /**
     * Build the normal {@code session.send} payload from the frozen item.
     *
     * <p>Nothing here reads the active tab: the tab of the record names the
     * session, the settings and the attachments.</p>
     */
    private static JsonObject toPayload(TabRecord record, TabQueue.Item item) {
        JsonObject payload = new JsonObject();
        payload.addProperty("text", item.text);
        payload.addProperty("requestId", item.id);
        payload.addProperty("tabId", record.getTabId().value());
        String sessionId = record.getSessionIdString();
        if (sessionId != null && !sessionId.isBlank()) {
            payload.addProperty("sessionId", sessionId);
        }
        if (item.permissionMode != null) {
            payload.addProperty("permissionMode", item.permissionMode);
        }
        if (item.model != null) {
            payload.addProperty("model", item.model);
        }
        if (item.reasoningEffort != null) {
            payload.addProperty("reasoningEffort", item.reasoningEffort);
        }
        if (!item.fileTags.isEmpty()) {
            JsonArray tags = new JsonArray();
            for (TabQueue.FileTag tag : item.fileTags) {
                JsonObject json = new JsonObject();
                json.addProperty("displayPath", tag.displayPath());
                json.addProperty("absolutePath", tag.absolutePath());
                tags.add(json);
            }
            payload.add("fileTags", tags);
        }
        if (!item.attachments.isEmpty()) {
            JsonArray attachments = new JsonArray();
            for (TabQueue.Attachment attachment : item.attachments) {
                JsonObject json = new JsonObject();
                json.addProperty("attachmentId", attachment.attachmentId());
                if (attachment.fileName() != null) {
                    json.addProperty("fileName", attachment.fileName());
                }
                if (attachment.mediaType() != null) {
                    json.addProperty("mediaType", attachment.mediaType());
                }
                attachments.add(json);
            }
            payload.add("attachments", attachments);
        }
        return payload;
    }
}
