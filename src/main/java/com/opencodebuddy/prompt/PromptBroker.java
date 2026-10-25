package com.opencodebuddy.prompt;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns every in-flight prompt and the session/tab it belongs to.
 *
 * <p>The maps used to live in {@code PermissionHandler} next to the dialog
 * rendering code. That made the handler the only place that knew which
 * session a permission/question/plan future belonged to, so clearing a closed
 * tab and settling a late reply had to duplicate the same bookkeeping at
 * every call site. Keeping the state here, keyed by request id with an
 * explicit owner, gives both paths one source of truth.</p>
 *
 * <p>Thread-safe: prompts arrive from daemon reader threads while decisions
 * arrive from the webview/JCEF thread.</p>
 */
public final class PromptBroker {

    /** A question that is waiting for an answer. */
    public static final class PendingQuestion {
        public final String sessionId;
        public final JsonArray questions;
        /** The tool call id of the question block the card is anchored to. */
        public final String callId;
        public final String directory;

        public PendingQuestion(String sessionId, JsonArray questions, String callId, String directory) {
            this.sessionId = sessionId;
            this.questions = questions;
            this.callId = callId;
            this.directory = directory;
        }
    }

    public final Map<String, CompletableFuture<Integer>> permissions = new ConcurrentHashMap<>();
    public final Map<String, String> permissionSessions = new ConcurrentHashMap<>();
    /** The tab that owns each permission request. */
    public final Map<String, String> permissionTabs = new ConcurrentHashMap<>();

    public final Map<String, CompletableFuture<JsonObject>> askFutures = new ConcurrentHashMap<>();
    public final Map<String, String> askSessions = new ConcurrentHashMap<>();
    public final Map<String, String> askTabs = new ConcurrentHashMap<>();
    public final Map<String, PendingQuestion> questions = new ConcurrentHashMap<>();

    public final Map<String, CompletableFuture<JsonObject>> planFutures = new ConcurrentHashMap<>();
    public final Map<String, String> planSessions = new ConcurrentHashMap<>();
    public final Map<String, String> planTabs = new ConcurrentHashMap<>();

    // ===== registration =====

    /** Remember which tab owns a permission request. */
    public void rememberPermissionTab(String requestId, String tabId) {
        if (requestId != null && tabId != null) {
            permissionTabs.put(requestId, tabId);
        }
    }

    /** Remember which tab owns a question. */
    public void rememberQuestionTab(String requestId, String tabId) {
        if (requestId != null && tabId != null) {
            askTabs.put(requestId, tabId);
        }
    }

    /** Remember which tab owns a plan approval. */
    public void rememberPlanTab(String requestId, String tabId) {
        if (requestId != null && tabId != null) {
            planTabs.put(requestId, tabId);
        }
    }

    // ===== ownership =====

    /** The tab that owns a request id, or null when it has no tab. */
    public String tabIdOf(String requestId) {
        if (requestId == null) {
            return null;
        }
        String tabId = permissionTabs.get(requestId);
        if (tabId != null) {
            return tabId;
        }
        tabId = askTabs.get(requestId);
        if (tabId != null) {
            return tabId;
        }
        return planTabs.get(requestId);
    }

    /** The session that owns a request id, or null. */
    public String sessionIdOf(String requestId) {
        if (requestId == null) {
            return null;
        }
        String sessionId = permissionSessions.get(requestId);
        if (sessionId != null) {
            return sessionId;
        }
        sessionId = askSessions.get(requestId);
        if (sessionId != null) {
            return sessionId;
        }
        return planSessions.get(requestId);
    }

    /**
     * The pending prompts of one tab.
     *
     * <p>Used to render the badge of a background tab and to reject exactly
     * that tab's prompts when it is closed.</p>
     */
    public List<PendingPrompt> pendingForTab(String tabId) {
        List<PendingPrompt> result = new ArrayList<>();
        if (tabId == null) {
            return result;
        }
        for (Map.Entry<String, String> entry : permissionTabs.entrySet()) {
            if (tabId.equals(entry.getValue())) {
                result.add(new PendingPrompt(entry.getKey(), PromptKind.PERMISSION,
                        permissionSessions.get(entry.getKey()), entry.getValue(), null, null, null));
            }
        }
        for (Map.Entry<String, String> entry : askTabs.entrySet()) {
            if (tabId.equals(entry.getValue())) {
                PendingQuestion pending = questions.get(entry.getKey());
                result.add(new PendingPrompt(entry.getKey(), PromptKind.QUESTION,
                        askSessions.get(entry.getKey()), entry.getValue(),
                        pending != null ? pending.callId : null,
                        pending != null ? pending.directory : null,
                        pending != null ? pending.questions : null));
            }
        }
        for (Map.Entry<String, String> entry : planTabs.entrySet()) {
            if (tabId.equals(entry.getValue())) {
                result.add(new PendingPrompt(entry.getKey(), PromptKind.PLAN,
                        planSessions.get(entry.getKey()), entry.getValue(), null, null, null));
            }
        }
        return result;
    }

    /**
     * Reject the prompts of one tab.
     *
     * <p>Closing a tab must not leave the daemon waiting for an answer, and it
     * must not touch the prompts of another tab.</p>
     *
     * @return the request ids that were rejected
     */
    public List<String> rejectForTab(String tabId) {
        List<String> rejected = new ArrayList<>();
        if (tabId == null) {
            return rejected;
        }
        for (String requestId : new ArrayList<>(permissionTabs.keySet())) {
            if (tabId.equals(permissionTabs.get(requestId))) {
                CompletableFuture<Integer> future = permissions.remove(requestId);
                permissionSessions.remove(requestId);
                permissionTabs.remove(requestId);
                if (future != null) {
                    future.completeExceptionally(new IllegalStateException("tab closed: " + requestId));
                }
                rejected.add(requestId);
            }
        }
        for (String requestId : new ArrayList<>(askTabs.keySet())) {
            if (tabId.equals(askTabs.get(requestId))) {
                CompletableFuture<JsonObject> future = askFutures.remove(requestId);
                askSessions.remove(requestId);
                askTabs.remove(requestId);
                questions.remove(requestId);
                if (future != null) {
                    future.completeExceptionally(new IllegalStateException("tab closed: " + requestId));
                }
                rejected.add(requestId);
            }
        }
        for (String requestId : new ArrayList<>(planTabs.keySet())) {
            if (tabId.equals(planTabs.get(requestId))) {
                CompletableFuture<JsonObject> future = planFutures.remove(requestId);
                planSessions.remove(requestId);
                planTabs.remove(requestId);
                if (future != null) {
                    future.completeExceptionally(new IllegalStateException("tab closed: " + requestId));
                }
                rejected.add(requestId);
            }
        }
        return rejected;
    }

    public int pendingCount() {
        return permissions.size() + askFutures.size() + planFutures.size();
    }
}
