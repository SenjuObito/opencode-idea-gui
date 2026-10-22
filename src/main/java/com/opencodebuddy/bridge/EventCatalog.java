package com.opencodebuddy.bridge;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The semantic event catalog.
 *
 * <p>The bridge used to name an event after the JavaScript function it called
 * ({@code updateMessages}, {@code onContentDelta}, …). That made the wire
 * protocol a mirror of the webview's internals: a renamed handler was a
 * protocol change, and the catalog of "what can happen" was spread over
 * thousands of lines of callbacks.</p>
 *
 * <p>Every host event now has a stable, semantic name — {@code content.delta},
 * {@code message.snapshot}, {@code turn.ended} — and the legacy function name
 * travels next to it as {@code fn}. The webview resolves the semantic name
 * first and falls back to the legacy name, so the protocol can be completed
 * step by step without breaking the running plugin.</p>
 */
public final class EventCatalog {

    private EventCatalog() {
    }

    /** semantic type -> legacy function name */
    private static final Map<String, String> FUNCTION_BY_TYPE;
    /** legacy function name -> semantic type */
    private static final Map<String, String> TYPE_BY_FUNCTION;

    static {
        Map<String, String> byType = new LinkedHashMap<>();

        // ── lifecycle ──────────────────────────────────────────────────────
        byType.put("tab.resync", "onTabResync");
        byType.put("session.bound", "setSessionId");
        byType.put("session.loaded", "historyLoadComplete");
        byType.put("session.cleared", "clearMessages");
        byType.put("command.rejected", "commandRejected");
        byType.put("attachment.failed", "onAttachmentFailed");

        // ── turn & message ─────────────────────────────────────────────────
        byType.put("turn.started", "onStreamStart");
        byType.put("turn.ended", "onStreamEnd");
        byType.put("turn.settled", "onTurnEnd");
        byType.put("turn.retry", "updateRetryStatus");
        byType.put("turn.heartbeat", "onStreamingHeartbeat");
        byType.put("content.delta", "onContentDelta");
        byType.put("thinking.delta", "onThinkingDelta");
        byType.put("thinking.status", "showThinkingStatus");
        byType.put("message.snapshot", "updateMessages");
        byType.put("message.tail", "updateMessageTail");
        byType.put("message.prepended", "updateMessagesPrepend");
        byType.put("message.added", "addUserMessage");
        byType.put("message.history", "addHistoryMessage");
        byType.put("message.removed", "onMessagesRemoved");
        byType.put("message.uuid.patched", "patchMessageUuid");
        byType.put("message.block.reset", "onBlockReset");

        // ── status & usage ─────────────────────────────────────────────────
        byType.put("status.updated", "updateStatus");
        byType.put("session.loading", "showLoading");
        byType.put("session.summary", "showSummary");
        byType.put("usage.updated", "onUsageUpdate");

        // ── tab & settings ─────────────────────────────────────────────────
        byType.put("tab.title.changed", "updateSessionTitle");
        byType.put("settings.mode", "onModeChanged");

        // ── prompts ────────────────────────────────────────────────────────
        byType.put("permission.requested", "showPermissionDialog");
        byType.put("question.requested", "showAskUserQuestionDialog");
        byType.put("question.answered", "onQuestionAnswered");
        byType.put("plan.requested", "showPlanApprovalDialog");
        byType.put("prompt.closed", "forceClosePermissionDialog");
        byType.put("prompt.question.closed", "forceCloseAskUserQuestionDialog");
        byType.put("prompt.plan.closed", "forceClosePlanApprovalDialog");

        // ── status panel ───────────────────────────────────────────────────
        byType.put("todos.updated", "onTodoUpdated");
        byType.put("task.event", "onTaskEvent");
        byType.put("subagent.history", "onSubagentHistoryLoaded");
        byType.put("revert.changed", "onRevertStateUpdate");

        Map<String, String> byFunction = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : byType.entrySet()) {
            // The first mapping wins: a function may serve several semantic
            // types (the prompt close signals share one implementation).
            byFunction.putIfAbsent(entry.getValue(), entry.getKey());
        }

        FUNCTION_BY_TYPE = Collections.unmodifiableMap(byType);
        TYPE_BY_FUNCTION = Collections.unmodifiableMap(byFunction);
    }

    /**
     * The semantic type of a host event.
     *
     * @return the semantic type, or the function name itself when the event is
     *         not (yet) part of the catalog
     */
    public static String semanticType(String functionName) {
        if (functionName == null || functionName.isBlank()) {
            return functionName;
        }
        String bare = functionName.startsWith("window.")
                ? functionName.substring("window.".length())
                : functionName;
        String type = TYPE_BY_FUNCTION.get(bare);
        return type != null ? type : bare;
    }

    /**
     * The legacy function name of a semantic type.
     *
     * @return the function name, or {@code null} when the type is unknown
     */
    public static String functionName(String semanticType) {
        if (semanticType == null) {
            return null;
        }
        return FUNCTION_BY_TYPE.get(semanticType);
    }

    public static Map<String, String> catalog() {
        return FUNCTION_BY_TYPE;
    }

    public static int size() {
        return FUNCTION_BY_TYPE.size();
    }
}
