package com.opencodebuddy.tab;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * One tab's outbox.
 *
 * <p>Why it lives on the host: only the host knows when a turn really settled
 * ({@code turn.settled}, {@code SessionState.isBusy()}), and the webview's
 * {@code loading} flag is a projection that lags a commit behind. The old
 * global queue in the webview consumed "the active tab became idle", so an item
 * enqueued in A was sent to B after a tab switch. A queue owned by the tab
 * cannot be stolen by another tab: every item carries the tab and session it
 * was frozen in.</p>
 *
 * <p>The queue itself is inert — {@link TabQueuePump} is the only consumer and
 * it takes at most one item at a time ({@link #take()}) per tab.</p>
 *
 * <p>Thread-safe: commands arrive from the JCEF thread, the pump runs on the
 * tab's actor, and the daemon reader may settle a turn on its own thread.</p>
 */
public final class TabQueue {

    /** A tab cannot queue more than this many waiting sends. */
    public static final int MAX_ITEMS = 50;

    /** Longer queued text is refused instead of pinning tens of MB per tab. */
    public static final int MAX_TEXT_LENGTH = 256 * 1024;

    /** Session commands the queue may consume in order with the user's prompts. */
    public static final java.util.Set<String> COMMANDS = java.util.Set.of(
            "/compact", "/undo", "/redo", "/fork", "/share", "/unshare");

    public static boolean isCommand(String value) {
        return value != null && COMMANDS.contains(value);
    }

    /** One attachment frozen at enqueue time; the bytes stay in the tab store. */
    public record Attachment(String attachmentId, String fileName, String mediaType) {
    }

    /** One @-file reference frozen at enqueue time. */
    public record FileTag(String displayPath, String absolutePath) {
    }

    /** Arguments a queued command needs when it finally runs. */
    public record CommandArgs(String messageId, String targetTabId) {
        public static final CommandArgs NONE = new CommandArgs(null, null);
    }

    /** The last send failure of an item that was put back at the head. */
    public record Error(String itemId, String message) {
    }

    /**
     * A send frozen at enqueue time.
     *
     * <p>{@code text} is already quote-expanded and stripped of zero-width
     * characters, the attachments are already uploaded under this tab, and the
     * settings are the ones the user saw. None of that may be re-resolved at
     * send time: the active tab (and therefore "current" model/mode/attachments)
     * may belong to someone else by then.</p>
     */
    public static final class Item {

        /** Webview-generated; also used as the {@code session.send} request id. */
        public final String id;
        public final String text;
        /**
         * When set, the item is a session command rather than a prompt: it is
         * dispatched to the tab's command handler instead of {@code session.send}
         * and the queue waits for that command's own result.
         */
        public final String command;
        public final CommandArgs commandArgs;
        public final List<FileTag> fileTags;
        public final List<Attachment> attachments;
        public final String permissionMode;
        public final String model;
        public final String reasoningEffort;
        /** The session this tab was bound to when the item was enqueued. */
        public final String originSessionId;
        public final long originTabEpoch;
        public final long createdAt;

        public Item(
                String id,
                String text,
                List<FileTag> fileTags,
                List<Attachment> attachments,
                String permissionMode,
                String model,
                String reasoningEffort,
                String originSessionId,
                long originTabEpoch,
                long createdAt
        ) {
            this(id, text, null, null, fileTags, attachments, permissionMode, model,
                    reasoningEffort, originSessionId, originTabEpoch, createdAt);
        }

        public Item(
                String id,
                String text,
                String command,
                CommandArgs commandArgs,
                List<FileTag> fileTags,
                List<Attachment> attachments,
                String permissionMode,
                String model,
                String reasoningEffort,
                String originSessionId,
                long originTabEpoch,
                long createdAt
        ) {
            this.id = id;
            this.text = text != null ? text : "";
            this.command = TabQueue.isCommand(command) ? command : null;
            this.commandArgs = commandArgs != null ? commandArgs : CommandArgs.NONE;
            this.fileTags = fileTags != null ? List.copyOf(fileTags) : List.of();
            this.attachments = attachments != null ? List.copyOf(attachments) : List.of();
            this.permissionMode = permissionMode;
            this.model = model;
            this.reasoningEffort = reasoningEffort;
            this.originSessionId = originSessionId;
            this.originTabEpoch = originTabEpoch;
            this.createdAt = createdAt;
        }

        public boolean isCommand() {
            return command != null;
        }

        public boolean isEmpty() {
            return text.isBlank() && attachments.isEmpty();
        }
    }

    /** The result of {@link #enqueue(Item)}. */
    public record EnqueueResult(boolean ok, String reason) {
        public static EnqueueResult accepted() {
            return new EnqueueResult(true, null);
        }

        public static EnqueueResult refused(String reason) {
            return new EnqueueResult(false, reason);
        }
    }

    private final Deque<Item> pending = new ArrayDeque<>();
    private Item inflight;
    private boolean pausedFlag;
    private boolean compactingFlag;
    private Error lastError;

    /**
     * Append one frozen send.
     *
     * <p>A rejected item is not queued; the caller reports the reason. The id
     * must be unique inside this tab, otherwise a replayed command could
     * enqueue the same send twice.</p>
     */
    public synchronized EnqueueResult enqueue(Item item) {
        if (item == null || item.id == null || item.id.isBlank()) {
            return EnqueueResult.refused("queue item is missing an id");
        }
        if (item.isEmpty()) {
            return EnqueueResult.refused("queue item is empty");
        }
        if (item.text.length() > MAX_TEXT_LENGTH) {
            return EnqueueResult.refused("queued message is too long");
        }
        if (pending.size() >= MAX_ITEMS) {
            return EnqueueResult.refused("queue is full (" + MAX_ITEMS + " items)");
        }
        if (containsLocked(item.id)) {
            return EnqueueResult.refused("queue item id is already used");
        }
        pending.addLast(item);
        // A new item is a fresh intent: an old failure banner must not keep the
        // pump stopped forever.
        lastError = null;
        return EnqueueResult.accepted();
    }

    private boolean containsLocked(String id) {
        if (inflight != null && id.equals(inflight.id)) {
            return true;
        }
        for (Item queued : pending) {
            if (id.equals(queued.id)) {
                return true;
            }
        }
        return false;
    }

    /** The waiting items, in send order. */
    public synchronized List<Item> items() {
        return new ArrayList<>(pending);
    }

    public synchronized int size() {
        return pending.size();
    }

    public synchronized boolean isEmpty() {
        return pending.isEmpty() && inflight == null;
    }

    public synchronized Item getInflight() {
        return inflight;
    }

    public synchronized String getInflightId() {
        return inflight != null ? inflight.id : null;
    }

    /** The command the pump is currently executing, or null for a prompt. */
    public synchronized String getInflightCommand() {
        return inflight != null ? inflight.command : null;
    }

    /**
     * Move the head item to {@code inflight}.
     *
     * <p>Atomic on purpose: a duplicated drain trigger must never send the same
     * item twice. Until {@link #complete()} runs, {@code take()} returns
     * {@code null}.</p>
     */
    public synchronized Item take() {
        if (inflight != null) {
            return null;
        }
        Item next = pending.pollFirst();
        if (next == null) {
            return null;
        }
        inflight = next;
        return next;
    }

    /** The in-flight turn settled: the item is consumed. */
    public synchronized void complete() {
        inflight = null;
    }

    /** The send failed: put the item back at the head and stop the pump. */
    public synchronized void fail(String message) {
        Item item = inflight;
        inflight = null;
        if (item != null) {
            pending.addFirst(item);
            lastError = new Error(item.id, message != null ? message : "send failed");
        }
    }

    /** Remove a pending item. Removing the failed item clears the error. */
    public synchronized boolean remove(String itemId) {
        if (itemId == null) {
            return false;
        }
        boolean removed = pending.removeIf(item -> itemId.equals(item.id));
        if (removed && lastError != null && itemId.equals(lastError.itemId())) {
            lastError = null;
        }
        if (pending.isEmpty()) {
            pausedFlag = false;
        }
        return removed;
    }

    /** Drop every pending item; the in-flight turn is already accepted. */
    public synchronized List<Item> clear() {
        List<Item> removed = new ArrayList<>(pending);
        pending.clear();
        lastError = null;
        pausedFlag = false;
        return removed;
    }

    /** Drop everything, including the in-flight item (tab close / rebind). */
    public synchronized List<Item> clearAll() {
        List<Item> removed = clear();
        Item running = inflight;
        inflight = null;
        if (running != null) {
            removed.add(0, running);
        }
        return removed;
    }

    public synchronized void pause() {
        if (!pending.isEmpty()) {
            pausedFlag = true;
        }
    }

    /**
     * User confirmed: resume consuming. A failed item that was put back at the
     * head is retried — the failure banner must not outlive the confirmation
     * and keep the pump stopped.
     */
    public synchronized void resume() {
        pausedFlag = false;
        lastError = null;
    }

    public synchronized boolean isPaused() {
        return pausedFlag;
    }

    public synchronized void setCompacting(boolean compacting) {
        compactingFlag = compacting;
    }

    public synchronized boolean isCompacting() {
        return compactingFlag;
    }

    public synchronized Error getError() {
        return lastError;
    }

    /** The queue as the webview projection expects it. */
    public synchronized JsonObject toJson() {
        JsonObject json = new JsonObject();
        JsonArray items = new JsonArray();
        for (Item item : pending) {
            items.add(describe(item));
        }
        json.add("items", items);
        // The in-flight item is deliberately absent: it is no longer queued, it
        // is already on its way to the session. Only waiting items are projected,
        // so the view's queue list is exactly "what will be sent next".
        json.addProperty("paused", pausedFlag);
        if (lastError != null) {
            JsonObject error = new JsonObject();
            error.addProperty("itemId", lastError.itemId());
            error.addProperty("message", lastError.message());
            json.add("lastError", error);
        } else {
            json.add("lastError", com.google.gson.JsonNull.INSTANCE);
        }
        return json;
    }

    private static JsonObject describe(Item item) {
        JsonObject json = new JsonObject();
        json.addProperty("id", item.id);
        // `text` is the canonical projection field. `content` is kept as a
        // compatibility alias: a webview bundle from an older plugin build
        // reads it, and dropping it made such a bundle render number-only
        // rows. Both must always carry the same value.
        json.addProperty("text", item.text);
        json.addProperty("content", item.text);
        if (item.command != null) {
            json.addProperty("command", item.command);
        }
        json.add("attachments", attachmentsJson(item.attachments));
        if (item.model != null) {
            json.addProperty("model", item.model);
        } else {
            json.add("model", com.google.gson.JsonNull.INSTANCE);
        }
        if (item.permissionMode != null) {
            json.addProperty("permissionMode", item.permissionMode);
        } else {
            json.add("permissionMode", com.google.gson.JsonNull.INSTANCE);
        }
        if (item.reasoningEffort != null) {
            json.addProperty("reasoningEffort", item.reasoningEffort);
        } else {
            json.add("reasoningEffort", com.google.gson.JsonNull.INSTANCE);
        }
        json.addProperty("createdAt", item.createdAt);
        return json;
    }

    private static JsonArray attachmentsJson(List<Attachment> attachments) {
        JsonArray array = new JsonArray();
        for (Attachment attachment : attachments) {
            JsonObject json = new JsonObject();
            json.addProperty("attachmentId", attachment.attachmentId());
            if (attachment.fileName() != null) {
                json.addProperty("fileName", attachment.fileName());
            }
            if (attachment.mediaType() != null) {
                json.addProperty("mediaType", attachment.mediaType());
            }
            array.add(json);
        }
        return array;
    }
}
