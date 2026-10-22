package com.opencodebuddy.handler;

import com.opencodebuddy.handler.core.BaseMessageHandler;
import com.opencodebuddy.handler.core.HandlerContext;

import com.opencodebuddy.permission.PermissionRequest;
import com.opencodebuddy.prompt.PromptBroker;
import com.opencodebuddy.provider.opencode.OpenCodeSDKBridge;
import com.opencodebuddy.session.OpencodeSession;
import com.opencodebuddy.settings.OpenCodeBuddySettingsService;
import com.opencodebuddy.util.SoundNotificationService;
import com.opencodebuddy.util.SystemNotificationService;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Permission handler.
 * Handles permission dialog display and decision processing.
 */
public class PermissionHandler extends BaseMessageHandler {

    private static final Logger LOG = Logger.getInstance(PermissionHandler.class);

    /**
     * Wire values expected by the core permission callback.
     */
    public enum PermissionResponse {
        ALLOW(1),
        ALLOW_ALWAYS(2),
        DENY(3);

        private final int value;

        PermissionResponse(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }

    private static final String[] SUPPORTED_TYPES = {
        "permission_decision",
        "ask_user_question_response",
        "ask_user_question_reject",
        "plan_approval_response"
    };

    private final PromptBroker prompts = new PromptBroker();

    private static int payloadLength(String value) {
        return value == null ? 0 : value.length();
    }

    private static String errorClass(Exception error) {
        return error.getClass().getSimpleName();
    }

    interface CancellableTask {
        void cancel();
    }

    interface SafetyNetScheduler {
        CancellableTask schedule(Runnable task, long delaySeconds);
    }

    interface AskUserQuestionVisualNotifier {
        void remind();
    }

    interface AskUserQuestionSoundNotifier {
        void play();
    }

    private static final SafetyNetScheduler DEFAULT_SAFETY_NET_SCHEDULER = (task, delaySeconds) -> {
        ScheduledFuture<?> scheduledFuture = AppExecutorUtil.getAppScheduledExecutorService()
                .schedule(task, delaySeconds, TimeUnit.SECONDS);
        return () -> scheduledFuture.cancel(false);
    };

    private final SafetyNetScheduler safetyNetScheduler;
    private final AskUserQuestionVisualNotifier askUserQuestionVisualNotifier;
    private final AskUserQuestionSoundNotifier askUserQuestionSoundNotifier;

    // Permission denied callback
    public interface PermissionDeniedCallback {
        void onPermissionDenied();
    }

    private PermissionDeniedCallback deniedCallback;

    public PermissionHandler(HandlerContext context) {
        this(context, DEFAULT_SAFETY_NET_SCHEDULER);
    }

    PermissionHandler(HandlerContext context, SafetyNetScheduler safetyNetScheduler) {
        this(context, safetyNetScheduler,
                () -> SystemNotificationService.getInstance()
                        .showAskUserQuestionReminderToast(context.getProject()),
                () -> SoundNotificationService.getInstance()
                        .playAskUserQuestionReminderSound());
    }

    PermissionHandler(HandlerContext context, SafetyNetScheduler safetyNetScheduler,
                      AskUserQuestionVisualNotifier askUserQuestionVisualNotifier,
                      AskUserQuestionSoundNotifier askUserQuestionSoundNotifier) {
        super(context);
        this.safetyNetScheduler = safetyNetScheduler;
        this.askUserQuestionVisualNotifier = askUserQuestionVisualNotifier;
        this.askUserQuestionSoundNotifier = askUserQuestionSoundNotifier;
    }

    long getSafetyNetTimeoutSeconds() {
        OpenCodeBuddySettingsService settingsService = context.getSettingsService();
        if (settingsService == null) {
            // Fall back to DEFAULT (not MAX) so a missing settings service doesn't turn the
            // safety net into a one-hour hang for an error that's almost always transient.
            return OpenCodeBuddySettingsService.DEFAULT_PERMISSION_DIALOG_TIMEOUT_SECONDS
                    + OpenCodeBuddySettingsService.PERMISSION_SAFETY_NET_BUFFER_SECONDS;
        }
        try {
            return settingsService.getPermissionDialogTimeoutSeconds()
                    + OpenCodeBuddySettingsService.PERMISSION_SAFETY_NET_BUFFER_SECONDS;
        } catch (Exception e) {
            LOG.warn("[PERM_SHOW] Failed to read permission dialog timeout for safety net; errorClass="
                    + e.getClass().getSimpleName(), e);
            return OpenCodeBuddySettingsService.DEFAULT_PERMISSION_DIALOG_TIMEOUT_SECONDS
                    + OpenCodeBuddySettingsService.PERMISSION_SAFETY_NET_BUFFER_SECONDS;
        }
    }

    void scheduleSafetyNet(CompletableFuture<?> future, Runnable timeoutTask) {
        CancellableTask cancellableTask = safetyNetScheduler.schedule(timeoutTask, getSafetyNetTimeoutSeconds());
        future.whenComplete((ignored, error) -> cancellableTask.cancel());
    }

    /**
     * Push a force-close signal to the webview's dialog manager. Used after the
     * Java side has auto-resolved a permission/ask/plan dialog future (e.g.
     * safety-net timeout) so the React dialog state cannot stay stuck on a
     * resolved request and silently block every subsequent show*Dialog call.
     *
     * @param fnName       webview function: forceClosePermissionDialog /
     *                     forceCloseAskUserQuestionDialog /
     *                     forceClosePlanApprovalDialog
     * @param targetId     channelId (permission) or requestId (ask/plan);
     *                     null clears every open dialog of that kind.
     */
    private void forceCloseFrontendDialog(String fnName, String targetId) {
        String safeId = targetId == null ? "" : targetId;
        String escapedId = escapeJs(safeId);
        callJavaScript("window." + fnName, escapedId);
    }

    public void setPermissionDeniedCallback(PermissionDeniedCallback callback) {
        this.deniedCallback = callback;
    }

    @Override
    public String[] getSupportedTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public boolean handle(String type, String content) {
        if ("permission_decision".equals(type)) {
            LOG.debug("[PERM_DEBUG][BRIDGE_RECV] Received permission_decision from JS");
            LOG.debug("[PERM_DEBUG][BRIDGE_RECV] payloadLength=" + payloadLength(content));
            handlePermissionDecision(content);
            return true;
        } else if ("ask_user_question_response".equals(type)) {
            LOG.debug("[ASK_USER_QUESTION][BRIDGE_RECV] Received ask_user_question_response from JS");
            LOG.debug("[ASK_USER_QUESTION][BRIDGE_RECV] payloadLength=" + payloadLength(content));
            handleAskUserQuestionResponse(content);
            return true;
        } else if ("ask_user_question_reject".equals(type)) {
            LOG.debug("[ASK_USER_QUESTION][BRIDGE_RECV] Received ask_user_question_reject from JS");
            LOG.debug("[ASK_USER_QUESTION][BRIDGE_RECV] payloadLength=" + payloadLength(content));
            handleAskUserQuestionReject(content);
            return true;
        } else if ("plan_approval_response".equals(type)) {
            LOG.debug("[PLAN_APPROVAL][BRIDGE_RECV] Received plan_approval_response from JS");
            LOG.debug("[PLAN_APPROVAL][BRIDGE_RECV] payloadLength=" + payloadLength(content));
            handlePlanApprovalResponse(content);
            return true;
        }
        return false;
    }

    public void showPermissionDialog(PermissionRequest request) {
        showPermissionDialog(request, null);
    }

    /**
     * Show permission request dialog (from PermissionRequest) with target session ID.
     */
    public void showPermissionDialog(PermissionRequest request, String targetSessionId) {
        LOG.info("[PermissionHandler] 显示权限请求对话框: " + request.getToolName() + ", sessionId=" + targetSessionId);

        try {
            Gson gson = new Gson();
            JsonObject requestData = new JsonObject();
            requestData.addProperty("channelId", request.getChannelId());
            requestData.addProperty("toolName", request.getToolName());

            // Identity comes from the request itself (filled by the daemon marker
            // handler from the normalized payload) — NOT from "whatever session is
            // currently active". Falling back to the active session is exactly how
            // a prompt from session B got rendered in session A, and how a prompt
            // for a session that was not active at all ended up nowhere while the
            // server settled it as rejected ("已取消" with no dialog).
            String sessionId = request.getSessionId();
            if ((sessionId == null || sessionId.isBlank()) && targetSessionId != null && !targetSessionId.isBlank()) {
                sessionId = targetSessionId;
            }
            if (sessionId == null || sessionId.isBlank()) {
                LOG.warn("[PermissionHandler] permission request carries no session identity"
                        + " (channelId=" + request.getChannelId() + ", tool=" + request.getToolName()
                        + "); refusing to guess the active session");
                sessionId = null;
            }
            if (sessionId != null) {
                requestData.addProperty("sessionId", sessionId);
                prompts.permissionSessions.put(request.getChannelId(), sessionId);
                // The tab owns the prompt: closing the tab has to reject it,
                // and the badge of a background tab has to find it.
                prompts.rememberPermissionTab(request.getChannelId(), context.getTabIdForSession(sessionId));
                // The tab is the identity the webview routes by; the session id
                // alone only says which conversation the prompt belongs to.
                String tabId = context.getTabIdForSession(sessionId);
                if (tabId != null) {
                    requestData.addProperty("tabId", tabId);
                }
            }

            JsonObject inputsJson = request.getInputs() != null
                    ? gson.toJsonTree(request.getInputs()).getAsJsonObject()
                    : new JsonObject();
            requestData.add("inputs", inputsJson);

            if (request.getSuggestions() != null) {
                requestData.add("suggestions", request.getSuggestions());
            }

            String requestJson = gson.toJson(requestData);
            String escapedJson = escapeJs(requestJson);

            // Trigger permission reminder sound
            try {
                askUserQuestionSoundNotifier.play();
            } catch (Exception ignored) {
            }

            callJavaScript("window.showPermissionDialog", escapedJson);

        } catch (Exception e) {
            LOG.error("[PermissionHandler] 显示权限弹窗失败: errorClass=" + errorClass(e), e);
            OpencodeSession target = this.context.getSession(request.getSessionId());
            if (target != null) {
                target.handlePermissionDecision(
                    request.getChannelId(),
                    false,
                    false,
                    "Failed to show permission dialog"
                );
            }
            notifyPermissionDenied();
        }
    }

    /**
     * Handle permission decision messages from JavaScript.
     */
    private void handlePermissionDecision(String jsonContent) {
        LOG.info("[PERM_DECISION] Received permission decision from JS");
        LOG.debug("[PERM_DEBUG][HANDLE_DECISION] payloadLength=" + payloadLength(jsonContent));
        try {
            Gson gson = new Gson();
            JsonObject decision = gson.fromJson(jsonContent, JsonObject.class);

            String channelId = decision.get("channelId").getAsString();
            String decisionSessionId = decision.has("sessionId") && !decision.get("sessionId").isJsonNull()
                    ? decision.get("sessionId").getAsString() : null;
            boolean allow = decision.get("allow").getAsBoolean();
            boolean remember = decision.get("remember").getAsBoolean();
            String rejectMessage = "";
            if (decision.has("rejectMessage") && !decision.get("rejectMessage").isJsonNull()) {
                rejectMessage = decision.get("rejectMessage").getAsString();
            }

            LOG.info("[PERM_DECISION] channelId=" + channelId + ", allow=" + allow + ", remember=" + remember);
            LOG.info("[PERM_DECISION] pending permissions size before remove: " + prompts.permissions.size());

            CompletableFuture<Integer> pendingFuture = prompts.permissions.remove(channelId);
            prompts.permissionSessions.remove(channelId);

            if (pendingFuture != null) {
                LOG.info("[PERM_DECISION] Found pending future, completing with allow=" + allow);
                int responseValue;
                if (allow) {
                    responseValue = remember ?
                        PermissionResponse.ALLOW_ALWAYS.getValue() :
                        PermissionResponse.ALLOW.getValue();
                } else {
                    responseValue = PermissionResponse.DENY.getValue();
                }
                pendingFuture.complete(responseValue);
                LOG.info("[PERM_DECISION] Future completed with value=" + responseValue);

                if (!allow) {
                    notifyPermissionDenied();
                }
            } else {
                LOG.warn("[PERM_DECISION] No pending future found for channelId=" + channelId + ", falling back to session handler");
                LOG.warn("[PERM_DECISION] Current pending permission keys: " + prompts.permissions.keySet());
                // Handle a legacy/local permission request. Route by the
                // decision's own session id; never borrow whatever session is
                // currently active.
                OpencodeSession target = context.getSession(decisionSessionId);
                if (target == null) {
                    LOG.warn("[PERM_DECISION] No pending future and no session for channelId="
                            + channelId + ", sessionId=" + decisionSessionId);
                } else if (remember) {
                    target.handlePermissionDecisionAlways(channelId, allow);
                } else {
                    target.handlePermissionDecision(channelId, allow, false, rejectMessage);
                }
                if (!allow) {
                    notifyPermissionDenied();
                }
            }
        } catch (Exception e) {
            LOG.error("[PERM_DECISION] ERROR: errorClass=" + errorClass(e), e);
        }
    }

    /**
     * Notify that permission was denied.
     */
    private void notifyPermissionDenied() {
        if (deniedCallback != null) {
            deniedCallback.onPermissionDenied();
        }
    }

    /**
     * Clear every pending prompt. Used by the legacy whole-window reset path.
     */
    public void clearPendingRequests() {
        clearPendingRequests(null);
    }

    /**
     * Clear the prompts owned by one tab.
     *
     * <p>The tab is the owner of its prompts: closing it rejects exactly its
     * own requests and leaves every other tab's prompts alone. The session id
     * alone is not enough — a tab can hold a prompt for a session that was
     * never bound (a brand-new tab) or that has already been replaced.</p>
     *
     * @return the request ids that were rejected
     */
    public java.util.List<String> clearPendingRequestsForTab(String tabId) {
        if (tabId == null || tabId.isBlank()) {
            return java.util.Collections.emptyList();
        }
        java.util.List<String> rejected = prompts.rejectForTab(tabId);
        if (!rejected.isEmpty()) {
            LOG.info("[PERM_CLEAR] Rejected " + rejected.size() + " prompt(s) of tab " + tabId);
        }
        return rejected;
    }

    /**
     * Clear prompts owned by one session (or every session when {@code sessionId}
     * is null). Closing a tab must not resolve prompts that belong to another
     * tab, and an opencode prompt without a CompletableFuture still needs an
     * explicit deny so the server-side turn does not hang.
     */
    public void clearPendingRequests(String sessionId) {
        boolean all = sessionId == null || sessionId.isBlank();
        String filter = all ? null : sessionId;
        LOG.info("[PERM_CLEAR] Clearing pending requests for " + (all ? "all sessions" : "session " + filter));

        int permissionCount = 0;
        int askUserCount = 0;
        int planCount = 0;

        for (String channelId : new java.util.ArrayList<>(prompts.permissionSessions.keySet())) {
            String owner = prompts.permissionSessions.get(channelId);
            if (!all && !filter.equals(owner)) continue;
            CompletableFuture<Integer> future = prompts.permissions.remove(channelId);
            prompts.permissionSessions.remove(channelId);
            if (future != null) {
                future.complete(PermissionResponse.DENY.getValue());
            } else if (owner != null) {
                OpencodeSession target = context.getSession(owner);
                if (target != null) {
                    target.handlePermissionDecision(channelId, false, false, "Tab closed");
                }
            }
            forceCloseFrontendDialog("forceClosePermissionDialog", channelId);
            permissionCount++;
        }
        // Futures without metadata (legacy calls) are still cleared globally.
        if (all) {
            for (Map.Entry<String, CompletableFuture<Integer>> entry : prompts.permissions.entrySet()) {
                entry.getValue().complete(PermissionResponse.DENY.getValue());
            }
            prompts.permissions.clear();
        }

        for (String requestId : new java.util.ArrayList<>(prompts.askSessions.keySet())) {
            String owner = prompts.askSessions.get(requestId);
            if (!all && !filter.equals(owner)) continue;
            CompletableFuture<JsonObject> future = prompts.askFutures.remove(requestId);
            PromptBroker.PendingQuestion pending = prompts.questions.remove(requestId);
            prompts.askSessions.remove(requestId);
            if (future != null) {
                future.complete(null);
            }
            if (pending != null || future != null) {
                forceCloseFrontendDialog("forceCloseAskUserQuestionDialog", requestId);
                askUserCount++;
            }
        }
        for (Map.Entry<String, PromptBroker.PendingQuestion> entry : prompts.questions.entrySet()) {
            PromptBroker.PendingQuestion pending = entry.getValue();
            if (all || (pending.sessionId != null && pending.sessionId.equals(filter))) {
                prompts.questions.remove(entry.getKey());
                forceCloseFrontendDialog("forceCloseAskUserQuestionDialog", entry.getKey());
                askUserCount++;
            }
        }

        for (String requestId : new java.util.ArrayList<>(prompts.planSessions.keySet())) {
            String owner = prompts.planSessions.get(requestId);
            if (!all && !filter.equals(owner)) continue;
            CompletableFuture<JsonObject> future = prompts.planFutures.remove(requestId);
            prompts.planSessions.remove(requestId);
            if (future != null) {
                JsonObject rejected = new JsonObject();
                rejected.addProperty("approved", false);
                rejected.addProperty("message", "Session changed");
                future.complete(rejected);
            }
            forceCloseFrontendDialog("forceClosePlanApprovalDialog", requestId);
            planCount++;
        }
        if (all) {
            for (Map.Entry<String, CompletableFuture<JsonObject>> entry : prompts.planFutures.entrySet()) {
                JsonObject rejected = new JsonObject();
                rejected.addProperty("approved", false);
                rejected.addProperty("message", "Session changed");
                entry.getValue().complete(rejected);
            }
            prompts.planFutures.clear();
            if (prompts.askFutures.size() > 0) {
                for (CompletableFuture<JsonObject> future : prompts.askFutures.values()) {
                    future.complete(null);
                }
                prompts.askFutures.clear();
            }
        }

        LOG.info("[PERM_CLEAR] Cleared: " + permissionCount + " permission, "
                + askUserCount + " askUser, " + planCount + " plan requests");
    }

    /**
     * Show AskUserQuestion dialog (from OpenCode daemon/SSE).
     */
    public void onQuestionRequested(String jsonContent) {
        onQuestionRequested(jsonContent, null);
    }

    /**
     * Show AskUserQuestion dialog (from OpenCode daemon/SSE) with target session ID.
     */
    public void onQuestionRequested(String jsonContent, String targetSessionId) {
        LOG.info("[PermissionHandler] onQuestionRequested: " + jsonContent + ", targetSessionId=" + targetSessionId);
        try {
            Gson gson = new Gson();
            JsonObject payload = gson.fromJson(jsonContent, JsonObject.class);
            if (payload == null) {
                return;
            }
            String requestId = stringOrNull(payload, "requestId");
            if (requestId == null || requestId.isBlank()) {
                requestId = stringOrNull(payload, "id");
            }
            if (requestId == null || requestId.isBlank()) {
                requestId = "question-1";
            }
            // Same rule as permissions: identity travels with the request. The
            // daemon's [QUESTION_REQUEST] marker always carries sessionID, so a
            // missing one is a real defect upstream, not something to paper over
            // by borrowing the active session's id.
            String sessionId = stringOrNull(payload, "sessionId");
            if (sessionId == null && targetSessionId != null && !targetSessionId.isBlank()) {
                sessionId = targetSessionId;
            }
            if (sessionId == null) {
                LOG.warn("[PermissionHandler] question request carries no session identity"
                        + " (requestId=" + requestId + "); refusing to guess the active session");
            }
            String toolName = stringOrNull(payload, "tool");
            JsonArray questions = payload.has("questions") && payload.get("questions").isJsonArray()
                    ? payload.getAsJsonArray("questions")
                    : new JsonArray();
            String directory = context.resolveEffectiveWorkingDirectory();

            prompts.questions.put(requestId, new PromptBroker.PendingQuestion(sessionId, questions, toolName, directory));
            prompts.rememberQuestionTab(requestId, context.getTabIdForSession(sessionId));

            // Trigger visual toast and sound reminders
            try {
                askUserQuestionVisualNotifier.remind();
                askUserQuestionSoundNotifier.play();
            } catch (Exception e) {
                LOG.warn("[PermissionHandler] Failed to trigger question notification: " + e.getMessage());
            }

            JsonObject requestData = new JsonObject();
            requestData.addProperty("requestId", requestId);
            if (sessionId != null) {
                requestData.addProperty("sessionId", sessionId);
                String tabId = context.getTabIdForSession(sessionId);
                if (tabId != null) {
                    requestData.addProperty("tabId", tabId);
                }
            }
            if (toolName != null) {
                requestData.addProperty("toolName", toolName);
            }
            requestData.add("questions", questions);

            String requestJson = gson.toJson(requestData);
            String escapedJson = escapeJs(requestJson);

            callJavaScript("window.showAskUserQuestionDialog", escapedJson);
        } catch (Exception e) {
            LOG.error("[PermissionHandler] Failed to process onQuestionRequested: " + e.getMessage(), e);
        }
    }

    /**
     * Server resolved/aborted a pending prompt (turn aborted, timeout, etc.).
     */
    public void onPromptClosed(String kind, String jsonContent) {
        LOG.info("[PermissionHandler] onPromptClosed: kind=" + kind + ", content=" + jsonContent);
        try {
            Gson gson = new Gson();
            JsonObject payload = gson.fromJson(jsonContent, JsonObject.class);
            if (payload == null) {
                return;
            }
            if ("question".equals(kind)) {
                String requestId = stringOrNull(payload, "requestId");
                if (requestId == null) {
                    requestId = stringOrNull(payload, "requestID");
                }
                if (requestId == null) {
                    requestId = stringOrNull(payload, "id");
                }
                if (requestId != null && !requestId.isBlank()) {
                    prompts.questions.remove(requestId);
                    prompts.askSessions.remove(requestId);
                    CompletableFuture<JsonObject> future = prompts.askFutures.remove(requestId);
                    if (future != null) {
                        future.complete(null);
                    }
                    forceCloseFrontendDialog("forceCloseAskUserQuestionDialog", requestId);
                }
            } else if ("plan".equals(kind)) {
                String requestId = stringOrNull(payload, "requestId");
                if (requestId == null) requestId = stringOrNull(payload, "requestID");
                if (requestId == null) requestId = stringOrNull(payload, "id");
                if (requestId != null && !requestId.isBlank()) {
                    prompts.planSessions.remove(requestId);
                    CompletableFuture<JsonObject> future = prompts.planFutures.remove(requestId);
                    if (future != null) {
                        JsonObject rejected = new JsonObject();
                        rejected.addProperty("approved", false);
                        rejected.addProperty("message", "Prompt closed");
                        future.complete(rejected);
                    }
                    forceCloseFrontendDialog("forceClosePlanApprovalDialog", requestId);
                }
            } else {
                String permissionId = stringOrNull(payload, "permissionId");
                if (permissionId == null) {
                    permissionId = stringOrNull(payload, "permissionID");
                }
                if (permissionId == null) {
                    permissionId = stringOrNull(payload, "requestID");
                }
                if (permissionId == null) {
                    permissionId = stringOrNull(payload, "id");
                }
                if (permissionId != null && !permissionId.isBlank()) {
                    prompts.permissionSessions.remove(permissionId);
                    CompletableFuture<Integer> future = prompts.permissions.remove(permissionId);
                    if (future != null) {
                        future.complete(PermissionResponse.DENY.getValue());
                    }
                    forceCloseFrontendDialog("forceClosePermissionDialog", permissionId);
                }
            }
        } catch (Exception e) {
            LOG.warn("[PermissionHandler] onPromptClosed parse failed: " + e.getMessage());
        }
    }

    /**
     * Show the AskUserQuestion dialog.
     */
    public CompletableFuture<JsonObject> showAskUserQuestionDialog(String requestId, JsonObject questionsData) {
        CompletableFuture<JsonObject> future = new CompletableFuture<>();

        LOG.debug("[ASK_USER_QUESTION][SHOW_DIALOG] Starting showAskUserQuestionDialog");
        LOG.debug("[ASK_USER_QUESTION][SHOW_DIALOG] requestId=" + requestId);
        LOG.debug("[ASK_USER_QUESTION][SHOW_DIALOG] questionCount="
                + (questionsData.has("questions") && questionsData.get("questions").isJsonArray()
                    ? questionsData.getAsJsonArray("questions").size()
                    : 0));

        prompts.askFutures.put(requestId, future);
        String dialogSessionId = stringOrNull(questionsData, "sessionId");
        if (dialogSessionId != null && !dialogSessionId.isBlank()) {
            prompts.askSessions.put(requestId, dialogSessionId);
        }
        // The tab owns the prompt: closing the tab rejects it.
        prompts.rememberQuestionTab(requestId, context.getTabIdForSession(dialogSessionId));

        // Remind the user (via the opt-in system toast and sound) that OpenCode is waiting for an
        // answer. Triggered here — before the JS dialog render — so the toast fires
        // for every AskUserQuestion regardless of whether the webview is reachable.
        try {
            askUserQuestionVisualNotifier.remind();
            askUserQuestionSoundNotifier.play();
        } catch (Exception e) {
            LOG.warn("[ASK_USER_QUESTION][SHOW_DIALOG] Failed to show reminder notification: " + e.getMessage());
        }

        try {
            Gson gson = new Gson();
            String requestJson = gson.toJson(questionsData);
            String escapedJson = escapeJs(requestJson);

            callJavaScript("window.showAskUserQuestionDialog", escapedJson);

            scheduleSafetyNet(future, () -> {
                if (future.complete(new JsonObject())) {
                    LOG.warn("[ASK_USER_QUESTION][SHOW_DIALOG] Safety-net timeout fired (webview unreachable) for requestId=" + requestId);
                    prompts.askFutures.remove(requestId);
                    String ownerSessionId = prompts.askSessions.remove(requestId);
                    forceCloseFrontendDialog("forceCloseAskUserQuestionDialog", requestId);
                    OpencodeSession target = ownerSessionId != null
                            ? context.getSession(ownerSessionId) : null;
                    if (target != null) {
                        target.interrupt();
                    }
                }
            });

        } catch (Exception e) {
            LOG.error("[ASK_USER_QUESTION][SHOW_DIALOG] ERROR: errorClass=" + errorClass(e), e);
            prompts.askFutures.remove(requestId);
            prompts.askSessions.remove(requestId);
            future.complete(new JsonObject());
        }

        return future;
    }

    /**
     * Handle AskUserQuestion response messages from JavaScript.
     */
    private void handleAskUserQuestionResponse(String jsonContent) {
        LOG.debug("[ASK_USER_QUESTION][HANDLE_RESPONSE] payloadLength=" + payloadLength(jsonContent));
        try {
            Gson gson = new Gson();
            JsonObject response = gson.fromJson(jsonContent, JsonObject.class);
            if (response == null) {
                return;
            }

            String requestId = stringOrNull(response, "requestId");
            JsonObject answers = response.has("answers") && !response.get("answers").isJsonNull()
                ? response.get("answers").getAsJsonObject()
                : new JsonObject();

            CompletableFuture<JsonObject> pendingFuture = prompts.askFutures.remove(requestId);
            prompts.askSessions.remove(requestId);

            if (pendingFuture != null) {
                LOG.debug("[ASK_USER_QUESTION][HANDLE_RESPONSE] Completing future with answerCount=" + answers.size());
                pendingFuture.complete(answers);
                return;
            }

            PromptBroker.PendingQuestion pending = prompts.questions.remove(requestId);
            if (pending == null) {
                LOG.warn("[ASK_USER_QUESTION][HANDLE_RESPONSE] No pending request found for requestId: " + requestId);
                return;
            }

            JsonArray orderedAnswers = buildOrderedAnswers(pending.questions, answers);

            OpenCodeSDKBridge bridge = context.getOpenCodeSDKBridge();
            if (bridge != null) {
                bridge.replyQuestion(pending.sessionId, requestId, orderedAnswers, pending.directory)
                        .whenComplete((ok, error) -> {
                            if (error != null || !Boolean.TRUE.equals(ok)) {
                                String msg = error != null ? error.getMessage() : "daemon rejected";
                                LOG.warn("[OpenCode] replyQuestion failed: id=" + requestId + " error=" + msg);
                                prompts.questions.put(requestId, pending);
                                callJavaScript("window.showToast", escapeJs("回答提交失败: " + msg));
                                forceCloseFrontendDialog("invalidateQuestionCard", requestId);
                            } else {
                                // Emit complete Q&A data to webview only after successful reply
                                JsonObject onQAData = new JsonObject();
                                onQAData.addProperty("callId", pending.toolName != null ? pending.toolName : "");
                                onQAData.addProperty("requestId", requestId);
                                onQAData.add("questions", pending.questions);
                                onQAData.add("answers", answers);
                                callJavaScript("window.onQuestionAnswered", escapeJs(gson.toJson(onQAData)));
                            }
                        });
            } else {
                LOG.warn("[OpenCode] replyQuestion failed: bridge is null for requestId: " + requestId);
                prompts.questions.put(requestId, pending);
                callJavaScript("window.showToast", escapeJs("回答提交失败: bridge is not available"));
                forceCloseFrontendDialog("invalidateQuestionCard", requestId);
            }
        } catch (Exception e) {
            LOG.error("[ASK_USER_QUESTION][HANDLE_RESPONSE] ERROR: errorClass=" + errorClass(e), e);
        }
    }

    /**
     * Handle AskUserQuestion reject/skip messages from JavaScript.
     */
    private void handleAskUserQuestionReject(String jsonContent) {
        LOG.debug("[ASK_USER_QUESTION][HANDLE_REJECT] " + jsonContent);
        try {
            Gson gson = new Gson();
            JsonObject response = gson.fromJson(jsonContent, JsonObject.class);
            if (response == null) {
                return;
            }
            String requestId = stringOrNull(response, "requestId");
            PromptBroker.PendingQuestion pending = prompts.questions.remove(requestId);
            if (pending == null) {
                return;
            }
            OpenCodeSDKBridge bridge = context.getOpenCodeSDKBridge();
            if (bridge != null) {
                bridge.rejectQuestion(pending.sessionId, requestId, pending.directory)
                        .whenComplete((ok, error) -> {
                            if (error != null || !Boolean.TRUE.equals(ok)) {
                                String msg = error != null ? error.getMessage() : "daemon rejected";
                                LOG.warn("[OpenCode] rejectQuestion failed: id=" + requestId + " error=" + msg);
                            }
                        });
            }
        } catch (Exception e) {
            LOG.error("[ASK_USER_QUESTION][HANDLE_REJECT] ERROR: errorClass=" + errorClass(e), e);
        }
    }

    private static JsonArray buildOrderedAnswers(JsonArray questions, JsonObject answers) {
        JsonArray ordered = new JsonArray();
        if (questions == null) {
            return ordered;
        }
        for (int i = 0; i < questions.size(); i++) {
            JsonArray itemAnswers = new JsonArray();
            if (questions.get(i).isJsonObject()) {
                JsonObject qObj = questions.get(i).getAsJsonObject();
                String qText = stringOrNull(qObj, "question");
                if (qText == null) {
                    qText = stringOrNull(qObj, "text");
                }
                String qKey = stringOrNull(qObj, "key");
                String qId = stringOrNull(qObj, "id");
                String qTitle = stringOrNull(qObj, "title");

                com.google.gson.JsonElement val = null;
                if (answers != null) {
                    if (qText != null && answers.has(qText) && !answers.get(qText).isJsonNull()) {
                        val = answers.get(qText);
                    } else if (qKey != null && answers.has(qKey) && !answers.get(qKey).isJsonNull()) {
                        val = answers.get(qKey);
                    } else if (qId != null && answers.has(qId) && !answers.get(qId).isJsonNull()) {
                        val = answers.get(qId);
                    } else if (qTitle != null && answers.has(qTitle) && !answers.get(qTitle).isJsonNull()) {
                        val = answers.get(qTitle);
                    }
                }

                if (val != null) {
                    if (val.isJsonArray()) {
                        for (com.google.gson.JsonElement v : val.getAsJsonArray()) {
                            if (v.isJsonPrimitive()) {
                                itemAnswers.add(v.getAsString());
                            }
                        }
                    } else if (val.isJsonPrimitive()) {
                        itemAnswers.add(val.getAsString());
                    }
                }
            }
            ordered.add(itemAnswers);
        }
        return ordered;
    }

    private static String stringOrNull(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        return obj.get(key).getAsString();
    }

    /**
     * Show the PlanApproval dialog.
     */
    public CompletableFuture<JsonObject> showPlanApprovalDialog(String requestId, JsonObject planData) {
        CompletableFuture<JsonObject> future = new CompletableFuture<>();

        LOG.debug("[PLAN_APPROVAL][SHOW_DIALOG] Starting showPlanApprovalDialog");
        LOG.debug("[PLAN_APPROVAL][SHOW_DIALOG] requestId=" + requestId);
        LOG.debug("[PLAN_APPROVAL][SHOW_DIALOG] fieldCount=" + planData.size());

        prompts.planFutures.put(requestId, future);
        String planSessionId = stringOrNull(planData, "sessionId");
        if (planSessionId != null && !planSessionId.isBlank()) {
            prompts.planSessions.put(requestId, planSessionId);
            prompts.rememberPlanTab(requestId, context.getTabIdForSession(planSessionId));
        }

        try {
            Gson gson = new Gson();
            if (planSessionId != null && !planSessionId.isBlank()) {
                String tabId = context.getTabIdForSession(planSessionId);
                if (tabId != null && !planData.has("tabId")) {
                    planData.addProperty("tabId", tabId);
                }
            }
            String requestJson = gson.toJson(planData);
            String escapedJson = escapeJs(requestJson);

            callJavaScript("window.showPlanApprovalDialog", escapedJson);

            scheduleSafetyNet(future, () -> {
                JsonObject timeoutResponse = new JsonObject();
                timeoutResponse.addProperty("approved", false);
                timeoutResponse.addProperty("targetMode", "default");
                timeoutResponse.addProperty("message", "Plan approval timed out");
                if (future.complete(timeoutResponse)) {
                    LOG.warn("[PLAN_APPROVAL][SHOW_DIALOG] Safety-net timeout fired (webview unreachable) for requestId=" + requestId);
                    prompts.planFutures.remove(requestId);
                    prompts.planSessions.remove(requestId);
                    forceCloseFrontendDialog("forceClosePlanApprovalDialog", requestId);
                }
            });

        } catch (Exception e) {
            LOG.error("[PLAN_APPROVAL][SHOW_DIALOG] ERROR: errorClass=" + errorClass(e), e);
            prompts.planFutures.remove(requestId);
            prompts.planSessions.remove(requestId);
            JsonObject errorResponse = new JsonObject();
            errorResponse.addProperty("approved", false);
            errorResponse.addProperty("targetMode", "default");
            errorResponse.addProperty("message", "Error showing plan approval dialog");
            future.complete(errorResponse);
        }

        return future;
    }

    /**
     * Handle PlanApproval response messages from JavaScript.
     */
    private void handlePlanApprovalResponse(String jsonContent) {
        LOG.debug("[PLAN_APPROVAL][HANDLE_RESPONSE] payloadLength=" + payloadLength(jsonContent));
        try {
            Gson gson = new Gson();
            JsonObject response = gson.fromJson(jsonContent, JsonObject.class);

            String requestId = response.get("requestId").getAsString();
            boolean approved = response.has("approved") && response.get("approved").getAsBoolean();
            String targetMode = response.has("targetMode") ? response.get("targetMode").getAsString() : "default";

            CompletableFuture<JsonObject> pendingFuture = prompts.planFutures.remove(requestId);
            prompts.planSessions.remove(requestId);

            if (pendingFuture != null) {
                JsonObject result = new JsonObject();
                result.addProperty("approved", approved);
                result.addProperty("targetMode", targetMode);
                LOG.debug("[PLAN_APPROVAL][HANDLE_RESPONSE] Completing future: approved=" + approved + ", targetMode=" + targetMode);
                pendingFuture.complete(result);
            } else {
                LOG.warn("[PLAN_APPROVAL][HANDLE_RESPONSE] No pending request found for requestId: " + requestId);
            }
        } catch (Exception e) {
            LOG.error("[PLAN_APPROVAL][HANDLE_RESPONSE] ERROR: errorClass=" + errorClass(e), e);
        }
    }
}
