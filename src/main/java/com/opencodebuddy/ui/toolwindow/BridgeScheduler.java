package com.opencodebuddy.ui.toolwindow;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Bounded, fair scheduler for Java-to-webview calls.
 *
 * <p>One renderer serves every tab, but the calls are not one stream: a
 * snapshot for tab A must never sit behind 200 deltas for tab B. Events are
 * therefore queued per tab lane and taken round-robin, giving the visible tab
 * three calls per pass and every background tab one. Ordering is preserved
 * inside a lane, which is the only ordering the renderer needs.</p>
 *
 * <p>The same pass also builds one typed envelope per call
 * ({@code {v, type, tabId, epoch, payload}}) and hands the batch to
 * {@code window.__opencodeDispatchBatch}. The legacy per-function call remains
 * as a fallback, and raw scripts still run inline so their ordering against
 * surrounding calls is unchanged.</p>
 */
class BridgeScheduler<T> {

    private static final Logger LOG = Logger.getInstance(BridgeScheduler.class);

    static final String GLOBAL_LANE = "__global";
    private static final int MAX_PENDING_EVENTS_PER_LANE = 256;
    private static final int MAX_BATCH_CALLS = 64;
    private static final int MAX_BATCH_ARGUMENT_CHARS = 256_000;
    private static final int ACTIVE_LANE_WEIGHT = 3;

    private final Supplier<T> browserSupplier;
    private final BooleanSupplier disposedSupplier;
    private final Consumer<Runnable> scheduler;
    private final BiConsumer<T, String> scriptExecutor;
    private final Object lock = new Object();
    private final Map<String, Lane<T>> lanes = new LinkedHashMap<>();

    private T queuedBrowser;
    private boolean drainScheduled;
    private boolean draining;
    private boolean disposed;

    private Function<String[], String> laneResolver = args -> GLOBAL_LANE;
    private Supplier<String> activeLaneSupplier = () -> null;
    private Function<String, Long> epochResolver = lane -> null;
    private Consumer<String> snapshotRequiredListener = lane -> { };

    BridgeScheduler(
            Supplier<T> browserSupplier,
            BooleanSupplier disposedSupplier,
            BiConsumer<T, String> scriptExecutor
    ) {
        this(
                browserSupplier,
                disposedSupplier,
                runnable -> ApplicationManager.getApplication().invokeLater(runnable),
                scriptExecutor
        );
    }

    BridgeScheduler(
            Supplier<T> browserSupplier,
            BooleanSupplier disposedSupplier,
            Consumer<Runnable> scheduler,
            BiConsumer<T, String> scriptExecutor
    ) {
        this.browserSupplier = browserSupplier;
        this.disposedSupplier = disposedSupplier;
        this.scheduler = scheduler;
        this.scriptExecutor = scriptExecutor;
    }

    /** Resolve the lane (tab id) of a call from its legacy argument list. */
    void setLaneResolver(Function<String[], String> resolver) {
        this.laneResolver = resolver != null ? resolver : args -> GLOBAL_LANE;
    }

    void setActiveLaneSupplier(Supplier<String> supplier) {
        this.activeLaneSupplier = supplier != null ? supplier : () -> null;
    }

    /** Per-lane epoch used to reject stale envelopes after a rebind/reload. */
    void setEpochResolver(Function<String, Long> resolver) {
        this.epochResolver = resolver != null ? resolver : lane -> null;
    }

    /**
     * Called when a delta had to be dropped to keep a lane bounded. The tab
     * needs a snapshot resync; the listener must not call back into this
     * scheduler synchronously (it is invoked outside the lock).
     */
    void setSnapshotRequiredListener(Consumer<String> listener) {
        this.snapshotRequiredListener = listener != null ? listener : lane -> { };
    }

    void enqueue(String functionName, String... args) {
        T browser = currentBrowser();
        if (browser == null) {
            return;
        }
        enqueue(new JsCall<T>(browser, functionName, copyArgs(args), null));
    }

    void enqueueRaw(String script) {
        if (script == null || script.isEmpty()) {
            return;
        }
        T browser = currentBrowser();
        if (browser == null) {
            return;
        }
        enqueue(new JsCall<T>(browser, null, new String[0], script));
    }

    void browserChanged() {
        synchronized (lock) {
            lanes.clear();
            queuedBrowser = browserSupplier.get();
        }
    }

    void dispose() {
        synchronized (lock) {
            disposed = true;
            lanes.clear();
            queuedBrowser = null;
        }
    }

    private T currentBrowser() {
        if (disposed || disposedSupplier.getAsBoolean()) {
            return null;
        }
        return browserSupplier.get();
    }

    private void enqueue(JsCall<T> call) {
        String laneId = call.rawScript != null ? GLOBAL_LANE : resolveLane(call.args);
        call.laneId = laneId;
        call.epoch = call.rawScript != null ? null : epochResolver.apply(laneId);
        boolean scheduleDrain = false;
        String snapshotLane = null;
        synchronized (lock) {
            if (disposed || disposedSupplier.getAsBoolean()) {
                return;
            }
            if (queuedBrowser != call.browser) {
                lanes.clear();
                queuedBrowser = call.browser;
            }
            Lane<T> lane = lanes.get(laneId);
            if (lane == null) {
                lane = new Lane<>();
                lanes.put(laneId, lane);
            }

            if (isDelta(call) && mergeWithTailDelta(lane, call)) {
                return;
            }
            if (isLatestOnly(call) && mergeWithTailLatest(lane, call)) {
                return;
            }
            if (isMessageSnapshot(call)) {
                removeQueuedSnapshot(lane, call.functionName);
            }

            boolean wasSnapshotRequired = lane.snapshotRequired;
            if (lane.pending.size() >= MAX_PENDING_EVENTS_PER_LANE) {
                boolean madeRoom = dropOneDisposable(lane)
                        || dropOneDelta(lane)
                        || (isLifecycle(call) && dropOneNonLifecycle(lane));
                if (!madeRoom) {
                    if (isLifecycle(call)) {
                        // Terminal events and snapshot boundaries are the only
                        // way the renderer converges; they may overflow a lane
                        // that is already all lifecycle events.
                        lane.pending.addLast(call);
                    } else {
                        LOG.warn("Dropping webview event because lane " + laneId + " is full: "
                                + (call.functionName != null ? call.functionName : "raw"));
                        if (isDelta(call)) {
                            lane.snapshotRequired = true;
                        }
                    }
                } else {
                    lane.pending.addLast(call);
                }
            } else {
                lane.pending.addLast(call);
            }
            if (!wasSnapshotRequired && lane.snapshotRequired) {
                snapshotLane = laneId;
            }
            if (!drainScheduled && !draining) {
                drainScheduled = true;
                scheduleDrain = true;
            }
        }
        if (snapshotLane != null) {
            notifySnapshotRequired(snapshotLane);
        }
        if (scheduleDrain) {
            scheduleDrain();
        }
    }

    private String resolveLane(String[] args) {
        try {
            String lane = laneResolver.apply(args);
            if (lane == null || lane.isBlank()) {
                return GLOBAL_LANE;
            }
            return lane;
        } catch (RuntimeException e) {
            LOG.debug("Lane resolver failed; using global lane: " + e.getMessage());
            return GLOBAL_LANE;
        }
    }

    private void scheduleDrain() {
        try {
            scheduler.accept(this::drain);
        } catch (RuntimeException e) {
            synchronized (lock) {
                drainScheduled = false;
            }
            LOG.warn("Failed to schedule webview event drain: " + e.getMessage(), e);
        }
    }

    private void drain() {
        List<JsCall<T>> batch;
        T targetBrowser;
        synchronized (lock) {
            drainScheduled = false;
            if (disposed || lanes.isEmpty()) {
                return;
            }
            targetBrowser = browserSupplier.get();
            if (targetBrowser == null || targetBrowser != queuedBrowser || disposedSupplier.getAsBoolean()) {
                lanes.clear();
                queuedBrowser = targetBrowser;
                return;
            }
            draining = true;
            batch = takeBatch(targetBrowser);
        }

        try {
            if (!batch.isEmpty()) {
                scriptExecutor.accept(targetBrowser, buildBatchScript(batch));
            }
        } catch (Exception | LinkageError e) {
            LOG.warn("Failed to execute queued webview events: " + e.getMessage(), e);
        } finally {
            boolean scheduleDrain = false;
            synchronized (lock) {
                draining = false;
                if (!disposed && hasPending() && !drainScheduled) {
                    drainScheduled = true;
                    scheduleDrain = true;
                }
            }
            if (scheduleDrain) {
                scheduleDrain();
            }
        }
    }

    private boolean hasPending() {
        for (Lane<T> lane : lanes.values()) {
            if (!lane.pending.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private List<JsCall<T>> takeBatch(T targetBrowser) {
        List<JsCall<T>> batch = new ArrayList<>();
        int estimatedChars = 0;
        String activeLane = activeLaneSupplier.get();
        Set<String> lanesWithSnapshot = new HashSet<>();
        boolean stop = false;

        while (!stop && batch.size() < MAX_BATCH_CALLS) {
            boolean tookAny = false;
            Iterator<Map.Entry<String, Lane<T>>> laneIterator = lanes.entrySet().iterator();
            while (laneIterator.hasNext() && !stop) {
                Map.Entry<String, Lane<T>> entry = laneIterator.next();
                String laneId = entry.getKey();
                Lane<T> lane = entry.getValue();
                int quota = laneId.equals(activeLane) ? ACTIVE_LANE_WEIGHT : 1;

                for (int i = 0; i < quota && batch.size() < MAX_BATCH_CALLS; i++) {
                    JsCall<T> call = lane.pending.peekFirst();
                    if (call == null) {
                        break;
                    }
                    if (call.browser != targetBrowser) {
                        lane.pending.pollFirst();
                        continue;
                    }
                    if (lanesWithSnapshot.contains(laneId) && isSnapshotResetBoundary(call)) {
                        break;
                    }
                    int callChars = call.estimatedChars();
                    if (!batch.isEmpty()
                            && estimatedChars + callChars > MAX_BATCH_ARGUMENT_CHARS) {
                        stop = true;
                        break;
                    }
                    lane.pending.pollFirst();
                    batch.add(call);
                    tookAny = true;
                    estimatedChars += callChars;
                    if (isMessageSnapshot(call)) {
                        lanesWithSnapshot.add(laneId);
                    }
                }

                if (lane.pending.isEmpty()) {
                    laneIterator.remove();
                }
            }
            if (!tookAny) {
                break;
            }
        }

        // Rotate the map head to the tail: a lane whose huge call filled this
        // batch must not also fill the next one, or later lanes starve.
        if (!lanes.isEmpty()) {
            String head = lanes.keySet().iterator().next();
            Lane<T> lane = lanes.remove(head);
            if (lane != null) {
                lanes.put(head, lane);
            }
        }
        return batch;
    }

    static String buildBatchScript(List<? extends JsCall<?>> calls) {
        boolean hasRaw = false;
        for (JsCall<?> call : calls) {
            if (call.rawScript != null) {
                hasRaw = true;
                break;
            }
        }

        StringBuilder script = new StringBuilder("(function() {\n");
        if (!hasRaw) {
            JsonArray envelopes = new JsonArray();
            for (JsCall<?> call : calls) {
                envelopes.add(envelope(call));
            }
            script.append("if (typeof window.__opencodeDispatchBatch === 'function') {\n")
                    .append("  try { window.__opencodeDispatchBatch(")
                    .append(envelopes)
                    .append("); } catch (e) { console.error('[Backend->Frontend] Batch dispatch failed:', e); }\n")
                    .append("} else {\n")
                    .append(legacyCalls(calls))
                    .append("}\n");
        } else {
            // A raw script must keep its exact position, so a batch containing
            // one is executed call-by-call instead of as envelopes.
            for (JsCall<?> call : calls) {
                if (call.rawScript != null) {
                    script.append("try { (function() {\n")
                            .append(call.rawScript)
                            .append("\n})(); } catch (e) { console.error('[Backend->Frontend] Raw JS failed:', e); }\n");
                } else {
                    script.append(legacyCall(call));
                }
            }
        }
        return script.append("})();").toString();
    }

    private static JsonObject envelope(JsCall<?> call) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("v", 2);
        envelope.addProperty("type", call.functionName);
        if (call.laneId != null && !GLOBAL_LANE.equals(call.laneId)) {
            envelope.addProperty("tabId", call.laneId);
        }
        if (call.epoch != null && call.epoch > 0) {
            envelope.addProperty("epoch", call.epoch);
        }
        JsonObject payload = new JsonObject();
        JsonArray args = new JsonArray();
        for (String arg : call.args) {
            args.add(arg == null ? "" : arg);
        }
        payload.add("args", args);
        envelope.add("payload", payload);
        return envelope;
    }

    private static StringBuilder legacyCalls(List<? extends JsCall<?>> calls) {
        StringBuilder script = new StringBuilder();
        for (JsCall<?> call : calls) {
            script.append(legacyCall(call));
        }
        return script;
    }

    private static String legacyCall(JsCall<?> call) {
        String callee = call.functionName.contains(".")
                ? call.functionName : "window." + call.functionName;
        StringBuilder argList = new StringBuilder();
        for (int i = 0; i < call.args.length; i++) {
            if (i > 0) {
                argList.append(", ");
            }
            argList.append('\'')
                    .append(sanitizeArgForJsLiteral(call.args[i]))
                    .append('\'');
        }
        return "  try { if (typeof window.__opencodeDispatch === 'function') { window.__opencodeDispatch('"
                + sanitizeArgForJsLiteral(call.functionName)
                + "', ["
                + argList
                + "]); } else if (typeof "
                + callee
                + " === 'function') { "
                + callee
                + '('
                + argList
                + "); } } catch (e) { console.error('[Backend->Frontend] Failed to call "
                + call.functionName
                + "', e); }\n";
    }

    static String sanitizeArgForJsLiteral(String arg) {
        if (arg == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(arg.length() + 8);
        for (int i = 0; i < arg.length(); i++) {
            char c = arg.charAt(i);
            if (c == '\'') {
                if (i == 0 || arg.charAt(i - 1) != '\\') {
                    sb.append('\\');
                }
                sb.append('\'');
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\u2028') {
                sb.append("\\u2028");
            } else if (c == '\u2029') {
                sb.append("\\u2029");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private boolean mergeWithTailDelta(Lane<T> lane, JsCall<T> call) {
        JsCall<T> tail = lane.pending.peekLast();
        if (tail == null || !isDelta(tail) || tail.browser != call.browser
                || !tail.functionName.equals(call.functionName)) {
            return false;
        }
        if (tail.args.length >= 2 && call.args.length >= 2) {
            String tailSessionId = tail.args[0] != null ? tail.args[0] : "";
            String callSessionId = call.args[0] != null ? call.args[0] : "";
            if (!tailSessionId.equals(callSessionId)) {
                return false;
            }
            tail.args[1] = (tail.args[1] != null ? tail.args[1] : "")
                    + (call.args[1] != null ? call.args[1] : "");
            return true;
        }
        if (tail.args.length == 1 && call.args.length == 1) {
            tail.args[0] = (tail.args[0] != null ? tail.args[0] : "")
                    + (call.args[0] != null ? call.args[0] : "");
            return true;
        }
        return false;
    }

    private boolean mergeWithTailLatest(Lane<T> lane, JsCall<T> call) {
        JsCall<T> tail = lane.pending.peekLast();
        if (tail == null || !isLatestOnly(tail) || tail.browser != call.browser
                || !tail.functionName.equals(call.functionName)) {
            return false;
        }
        tail.args = call.args;
        return true;
    }

    private void removeQueuedSnapshot(Lane<T> lane, String functionName) {
        Iterator<JsCall<T>> iterator = lane.pending.iterator();
        while (iterator.hasNext()) {
            JsCall<T> queued = iterator.next();
            if (queued.rawScript == null && functionName.equals(queued.functionName)) {
                iterator.remove();
            }
        }
    }

    private boolean dropOneDisposable(Lane<T> lane) {
        Iterator<JsCall<T>> iterator = lane.pending.iterator();
        while (iterator.hasNext()) {
            if (isDisposable(iterator.next())) {
                iterator.remove();
                return true;
            }
        }
        return false;
    }

    private boolean dropOneDelta(Lane<T> lane) {
        Iterator<JsCall<T>> iterator = lane.pending.iterator();
        while (iterator.hasNext()) {
            if (isDelta(iterator.next())) {
                iterator.remove();
                lane.snapshotRequired = true;
                return true;
            }
        }
        return false;
    }

    private boolean dropOneNonLifecycle(Lane<T> lane) {
        Iterator<JsCall<T>> iterator = lane.pending.iterator();
        while (iterator.hasNext()) {
            if (!isLifecycle(iterator.next())) {
                iterator.remove();
                return true;
            }
        }
        return false;
    }

    private void notifySnapshotRequired(String laneId) {
        try {
            snapshotRequiredListener.accept(laneId);
        } catch (RuntimeException e) {
            LOG.warn("Snapshot-required listener failed for " + laneId, e);
        }
    }

    boolean isSnapshotRequired(String laneId) {
        synchronized (lock) {
            Lane<T> lane = lanes.get(laneId);
            return lane != null && lane.snapshotRequired;
        }
    }

    void clearSnapshotRequired(String laneId) {
        synchronized (lock) {
            Lane<T> lane = lanes.get(laneId);
            if (lane != null) {
                lane.snapshotRequired = false;
            }
        }
    }

    private static boolean isMessageSnapshot(JsCall<?> call) {
        return call.rawScript == null
                && ("updateMessages".equals(call.functionName)
                || "updateMessageTail".equals(call.functionName));
    }

    private static boolean isSnapshotResetBoundary(JsCall<?> call) {
        return call.rawScript != null
                || "onStreamStart".equals(call.functionName)
                || "clearMessages".equals(call.functionName);
    }

    private static boolean isLifecycle(JsCall<?> call) {
        if (call.rawScript != null) {
            return true;
        }
        String fn = call.functionName;
        return isMessageSnapshot(call)
                || isSnapshotResetBoundary(call)
                || "onStreamEnd".equals(fn)
                || "onBlockReset".equals(fn)
                || "historyLoadComplete".equals(fn)
                || "window.historyLoadComplete".equals(fn)
                || "showPermissionDialog".equals(fn)
                || "window.showPermissionDialog".equals(fn)
                || "showAskUserQuestionDialog".equals(fn)
                || "window.showAskUserQuestionDialog".equals(fn)
                || "showPlanApprovalDialog".equals(fn)
                || "window.showPlanApprovalDialog".equals(fn);
    }

    private static boolean isLatestOnly(JsCall<?> call) {
        if (call.rawScript != null) {
            return false;
        }
        return "updateStatus".equals(call.functionName)
                || "showLoading".equals(call.functionName)
                || "showThinkingStatus".equals(call.functionName)
                || "updateRetryStatus".equals(call.functionName)
                || "onUsageUpdate".equals(call.functionName)
                || "updateUsage".equals(call.functionName)
                || "updateContextLimit".equals(call.functionName)
                || "updateDaemonStatus".equals(call.functionName)
                || "onModelConfirmed".equals(call.functionName)
                || "onStreamingHeartbeat".equals(call.functionName);
    }

    private static boolean isDelta(JsCall<?> call) {
        return call.rawScript == null
                && ("onContentDelta".equals(call.functionName)
                || "onThinkingDelta".equals(call.functionName));
    }

    private static boolean isDisposable(JsCall<?> call) {
        return call.rawScript == null
                && ("updateStatus".equals(call.functionName)
                || "onUsageUpdate".equals(call.functionName)
                || "updateUsage".equals(call.functionName)
                || "onStreamingHeartbeat".equals(call.functionName));
    }

    private static String[] copyArgs(String[] args) {
        return args == null ? new String[0] : args.clone();
    }

    private static final class Lane<T> {
        private final Deque<JsCall<T>> pending = new ArrayDeque<>();
        private boolean snapshotRequired;
    }

    static final class JsCall<T> {
        private final T browser;
        private final String functionName;
        private String[] args;
        private final String rawScript;
        private String laneId;
        private Long epoch;

        JsCall(T browser, String functionName, String[] args, String rawScript) {
            this(browser, functionName, args, rawScript, null, null);
        }

        JsCall(T browser, String functionName, String[] args, String rawScript,
               String laneId, Long epoch) {
            this.browser = browser;
            this.functionName = functionName;
            this.args = args;
            this.rawScript = rawScript;
            this.laneId = laneId;
            this.epoch = epoch;
        }

        String getFunctionName() {
            return functionName;
        }

        String[] getArgs() {
            return args;
        }

        String getRawScript() {
            return rawScript;
        }

        private int estimatedChars() {
            if (rawScript != null) {
                return rawScript.length();
            }
            int length = functionName.length() + 32;
            for (String arg : args) {
                length += arg == null ? 0 : arg.length();
            }
            return length;
        }
    }
}
