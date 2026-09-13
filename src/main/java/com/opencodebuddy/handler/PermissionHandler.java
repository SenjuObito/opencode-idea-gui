package com.opencodebuddy.handler;

import com.opencodebuddy.handler.core.BaseMessageHandler;
import com.opencodebuddy.handler.core.HandlerContext;

import com.opencodebuddy.permission.PermissionRequest;
import com.opencodebuddy.permission.PermissionService;
import com.opencodebuddy.provider.opencode.OpenCodeSDKBridge;
import com.opencodebuddy.settings.CodemossSettingsService;
import com.opencodebuddy.util.SoundNotificationService;
import com.opencodebuddy.util.SystemNotificationService;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Permission handler.
 * Handles permission dialog display and decision processing.
 */
public class PermissionHandler extends BaseMessageHandler {

    private static final Logger LOG = Logger.getInstance(PermissionHandler.class);

    private static final String[] SUPPORTED_TYPES = {
        "permission_decision",
        "ask_user_question_response",
        "ask_user_question_reject",
        "plan_approval_response"
    };

    private static class PendingQuestion {
        final String sessionId;
        final JsonArray questions;
        final String toolName;
        final String directory;

        PendingQuestion(String sessionId, JsonArray questions, String toolName, String directory) {
            this.sessionId = sessionId;
            this.questions = questions;
            this.toolName = toolName;
            this.directory = directory;
        }
    }

    private final Map<String, PendingQuestion> pendingQuestions = new ConcurrentHashMap<>();

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

    // Permission request map
    private final Map<String, CompletableFuture<Integer>> pendingPermissionRequests = new ConcurrentHashMap<>();

    // AskUserQuestion request map (requestId -> CompletableFuture<JsonObject>)
    private final Map<String, CompletableFuture<JsonObject>> pendingAskUserQuestionRequests = new ConcurrentHashMap<>();

    // PlanApproval request map (requestId -> CompletableFuture<JsonObject>)
    private final Map<String, CompletableFuture<JsonObject>> pendingPlanApprovalRequests = new ConcurrentHashMap<>();

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
        CodemossSettingsService settingsService = context.getSettingsService();
        if (settingsService == null) {
            // Fall back to DEFAULT (not MAX) so a missing settings service doesn't turn the
            // safety net into a one-hour hang for an error that's almost always transient.
            return CodemossSettingsService.DEFAULT_PERMISSION_DIALOG_TIMEOUT_SECONDS
                    + CodemossSettingsService.PERMISSION_SAFETY_NET_BUFFER_SECONDS;
        }
        try {
            return settingsService.getPermissionDialogTimeoutSeconds()
                    + CodemossSettingsService.PERMISSION_SAFETY_NET_BUFFER_SECONDS;
        } catch (Exception e) {
            LOG.warn("[PERM_SHOW] Failed to read permission dialog timeout for safety net; errorClass="
                    + e.getClass().getSimpleName(), e);
            return CodemossSettingsService.DEFAULT_PERMISSION_DIALOG_TIMEOUT_SECONDS
                    + CodemossSettingsService.PERMISSION_SAFETY_NET_BUFFER_SECONDS;
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
        String jsCode = "if (typeof window." + fnName + " === 'function') { "
                + "window." + fnName + "('" + escapedId + "'); }";
        // executeJavaScriptOnEDT already marshals to the EDT and no-ops when the
        // browser is absent, so call it directly. Wrapping it in another
        // invokeLater would both double-post and NPE in unit tests, where
        // ApplicationManager.getApplication() is null.
        context.executeJavaScriptOnEDT(jsCode);
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

    /**
     * Show the frontend permission dialog.
     */
    public CompletableFuture<Integer> showFrontendPermissionDialog(String toolName, JsonObject inputs) {
        String channelId = UUID.randomUUID().toString();
        CompletableFuture<Integer> future = new CompletableFuture<>();

        LOG.info("[PERM_SHOW] showFrontendPermissionDialog called: channelId=" + channelId + ", toolName=" + toolName);

        pendingPermissionRequests.put(channelId, future);
        LOG.info("[PERM_SHOW] Stored pending request, total pending: " + pendingPermissionRequests.size());

        try {
            Gson gson = new Gson();
            JsonObject requestData = new JsonObject();
            requestData.addProperty("channelId", channelId);
            requestData.addProperty("toolName", toolName);
            requestData.add("inputs", inputs);

            String requestJson = gson.toJson(requestData);
            String escapedJson = escapeJs(requestJson);

            ApplicationManager.getApplication().invokeLater(() -> {
                LOG.info("[PERM_SHOW] Executing JS to show dialog for channelId=" + channelId);
                String jsCode = "(function retryShowDialog(retries) { " +
                    "  if (window.showPermissionDialog) { " +
                    "    window.showPermissionDialog('" + escapedJson + "'); " +
                    "  } else if (retries > 0) { " +
                    "    setTimeout(function() { retryShowDialog(retries - 1); }, 200); " +
                    "  } else { " +
                    "    console.error('[PERM_DEBUG][JS] FAILED: showPermissionDialog not available!'); " +
                    "  } " +
                    "})(30);";

                context.executeJavaScriptOnEDT(jsCode);
            });

            scheduleSafetyNet(future, () -> {
                if (future.complete(PermissionService.PermissionResponse.DENY.getValue())) {
                    LOG.warn("[PERM_SHOW] Safety-net timeout fired (webview unreachable) for channelId=" + channelId);
                    pendingPermissionRequests.remove(channelId);
                    // The webview may still have the dialog open (with its own
                    // longer countdown finishing later, or stuck in an invisible
                    // state from a JCEF render issue). Tell it to drop the
                    // current dialog so the queue can drain for the next
                    // request — see issue #1360.
                    forceCloseFrontendDialog("forceClosePermissionDialog", channelId);
                }
            });

        } catch (Exception e) {
            LOG.error("[PERM_SHOW] ERROR: errorClass=" + errorClass(e), e);
            pendingPermissionRequests.remove(channelId);
            future.complete(PermissionService.PermissionResponse.DENY.getValue());
        }

        return future;
    }

    /**
     * Show permission request dialog (from PermissionRequest).
     */
    public void showPermissionDialog(PermissionRequest request) {
        LOG.info("[PermissionHandler] 显示权限请求对话框: " + request.getToolName());

        try {
            Gson gson = new Gson();
            JsonObject requestData = new JsonObject();
            requestData.addProperty("channelId", request.getChannelId());
            requestData.addProperty("toolName", request.getToolName());

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

            // Execute directly via context browser on EDT with retry loop
            String jsCode = "(function retryShowPermission(retries) { " +
                "  if (window.showPermissionDialog) { " +
                "    window.showPermissionDialog('" + escapedJson + "'); " +
                "  } else if (retries > 0) { " +
                "    setTimeout(function() { retryShowPermission(retries - 1); }, 200); " +
                "  } else { " +
                "    console.error('[PermissionHandler][JS] FAILED: showPermissionDialog not available!'); " +
                "  } " +
                "})(30);";

            context.executeJavaScriptOnEDT(jsCode);

        } catch (Exception e) {
            LOG.error("[PermissionHandler] 显示权限弹窗失败: errorClass=" + errorClass(e), e);
            if (this.context.getSession() != null) {
                this.context.getSession().handlePermissionDecision(
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
            boolean allow = decision.get("allow").getAsBoolean();
            boolean remember = decision.get("remember").getAsBoolean();
            String rejectMessage = "";
            if (decision.has("rejectMessage") && !decision.get("rejectMessage").isJsonNull()) {
                rejectMessage = decision.get("rejectMessage").getAsString();
            }

            LOG.info("[PERM_DECISION] channelId=" + channelId + ", allow=" + allow + ", remember=" + remember);
            LOG.info("[PERM_DECISION] pendingPermissionRequests size before remove: " + pendingPermissionRequests.size());

            CompletableFuture<Integer> pendingFuture = pendingPermissionRequests.remove(channelId);

            if (pendingFuture != null) {
                LOG.info("[PERM_DECISION] Found pending future, completing with allow=" + allow);
                int responseValue;
                if (allow) {
                    responseValue = remember ?
                        PermissionService.PermissionResponse.ALLOW_ALWAYS.getValue() :
                        PermissionService.PermissionResponse.ALLOW.getValue();
                } else {
                    responseValue = PermissionService.PermissionResponse.DENY.getValue();
                }
                pendingFuture.complete(responseValue);
                LOG.info("[PERM_DECISION] Future completed with value=" + responseValue);

                if (!allow) {
                    notifyPermissionDenied();
                }
            } else {
                LOG.warn("[PERM_DECISION] No pending future found for channelId=" + channelId + ", falling back to session handler");
                LOG.warn("[PERM_DECISION] Current pendingPermissionRequests keys: " + pendingPermissionRequests.keySet());
                // Handle permission request from Session
                if (remember) {
                    context.getSession().handlePermissionDecisionAlways(channelId, allow);
                } else {
                    context.getSession().handlePermissionDecision(channelId, allow, false, rejectMessage);
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
     * Clear all pending permission requests.
     * Called during session switching or history restoration to prevent old requests from interfering with the new session.
     */
    public void clearPendingRequests() {
        LOG.info("[PERM_CLEAR] Clearing all pending permission requests");

        int permissionCount = pendingPermissionRequests.size();
        int askUserCount = pendingAskUserQuestionRequests.size();
        int opencodeQuestionCount = pendingQuestions.size();
        int planCount = pendingPlanApprovalRequests.size();

        // Cancel all pending permission requests
        for (Map.Entry<String, CompletableFuture<Integer>> entry : pendingPermissionRequests.entrySet()) {
            entry.getValue().complete(PermissionService.PermissionResponse.DENY.getValue());
        }
        pendingPermissionRequests.clear();

        // Cancel all pending AskUserQuestion requests
        for (Map.Entry<String, CompletableFuture<JsonObject>> entry : pendingAskUserQuestionRequests.entrySet()) {
            entry.getValue().complete(null);
        }
        pendingAskUserQuestionRequests.clear();
        pendingQuestions.clear();

        // Cancel all pending PlanApproval requests
        for (Map.Entry<String, CompletableFuture<JsonObject>> entry : pendingPlanApprovalRequests.entrySet()) {
            JsonObject rejected = new com.google.gson.JsonObject();
            rejected.addProperty("approved", false);
            rejected.addProperty("message", "Session changed");
            entry.getValue().complete(rejected);
        }
        pendingPlanApprovalRequests.clear();

        // Match the Java-side teardown by closing any dialogs still open in the
        // webview. Pass null/empty to close every dialog of each kind — same
        // semantics as forceClose*Dialog called from the safety net.
        if (permissionCount > 0) {
            forceCloseFrontendDialog("forceClosePermissionDialog", null);
        }
        if (askUserCount > 0 || opencodeQuestionCount > 0) {
            forceCloseFrontendDialog("forceCloseAskUserQuestionDialog", null);
        }
        if (planCount > 0) {
            forceCloseFrontendDialog("forceClosePlanApprovalDialog", null);
        }

        LOG.info("[PERM_CLEAR] Cleared: " + permissionCount + " permission, " +
                 (askUserCount + opencodeQuestionCount) + " askUser, " + planCount + " plan requests");
    }

    /**
     * Show AskUserQuestion dialog (from OpenCode daemon/SSE).
     */
    public void onQuestionRequested(String jsonContent) {
        LOG.info("[PermissionHandler] onQuestionRequested: " + jsonContent);
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
            String sessionId = stringOrNull(payload, "sessionId");
            if (sessionId == null && context.getSession() != null) {
                sessionId = context.getSession().getSessionId();
            }
            String toolName = stringOrNull(payload, "tool");
            JsonArray questions = payload.has("questions") && payload.get("questions").isJsonArray()
                    ? payload.getAsJsonArray("questions")
                    : new JsonArray();
            String directory = context.resolveEffectiveWorkingDirectory();

            pendingQuestions.put(requestId, new PendingQuestion(sessionId, questions, toolName, directory));

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
            }
            if (toolName != null) {
                requestData.addProperty("toolName", toolName);
            }
            requestData.add("questions", questions);

            String requestJson = gson.toJson(requestData);
            String escapedJson = escapeJs(requestJson);

            String jsCode = "(function retryShowAskUserQuestion(retries) { " +
                "  if (window.showAskUserQuestionDialog) { " +
                "    window.showAskUserQuestionDialog('" + escapedJson + "'); " +
                "  } else if (retries > 0) { " +
                "    setTimeout(function() { retryShowAskUserQuestion(retries - 1); }, 200); " +
                "  } else { " +
                "    console.error('[PermissionHandler][JS] FAILED: showAskUserQuestionDialog not available!'); " +
                "  } " +
                "})(30);";

            context.executeJavaScriptOnEDT(jsCode);
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
                    pendingQuestions.remove(requestId);
                    forceCloseFrontendDialog("forceCloseAskUserQuestionDialog", requestId);
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
                    forceCloseFrontendDialog("forceClosePermissionDialog", permissionId);
                }
            }
        } catch (Exception e) {
            LOG.warn("[PermissionHandler] onPromptClosed parse failed: " + e.getMessage());
        }
    }

    /**
     * Show AskUserQuestion dialog (implements PermissionService.AskUserQuestionDialogShower interface).
     */
    public CompletableFuture<JsonObject> showAskUserQuestionDialog(String requestId, JsonObject questionsData) {
        CompletableFuture<JsonObject> future = new CompletableFuture<>();

        LOG.debug("[ASK_USER_QUESTION][SHOW_DIALOG] Starting showAskUserQuestionDialog");
        LOG.debug("[ASK_USER_QUESTION][SHOW_DIALOG] requestId=" + requestId);
        LOG.debug("[ASK_USER_QUESTION][SHOW_DIALOG] questionCount="
                + (questionsData.has("questions") && questionsData.get("questions").isJsonArray()
                    ? questionsData.getAsJsonArray("questions").size()
                    : 0));

        pendingAskUserQuestionRequests.put(requestId, future);

        // Remind the user (via the opt-in system toast and sound) that Claude is waiting for an
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

            Application application = ApplicationManager.getApplication();
            if (application != null) {
                application.invokeLater(() -> {
                    String jsCode = "(function retryShowAskUserQuestion(retries) { " +
                        "  if (window.showAskUserQuestionDialog) { " +
                        "    window.showAskUserQuestionDialog('" + escapedJson + "'); " +
                        "  } else if (retries > 0) { " +
                        "    setTimeout(function() { retryShowAskUserQuestion(retries - 1); }, 200); " +
                        "  } else { " +
                        "    console.error('[ASK_USER_QUESTION][JS] FAILED: showAskUserQuestionDialog not available!'); " +
                        "  } " +
                        "})(30);";

                    context.executeJavaScriptOnEDT(jsCode);
                });
            } else {
                LOG.debug("[ASK_USER_QUESTION][SHOW_DIALOG] Application unavailable, skipping JS dialog dispatch");
            }

            scheduleSafetyNet(future, () -> {
                if (future.complete(new JsonObject())) {
                    LOG.warn("[ASK_USER_QUESTION][SHOW_DIALOG] Safety-net timeout fired (webview unreachable) for requestId=" + requestId);
                    pendingAskUserQuestionRequests.remove(requestId);
                    forceCloseFrontendDialog("forceCloseAskUserQuestionDialog", requestId);
                }
            });

        } catch (Exception e) {
            LOG.error("[ASK_USER_QUESTION][SHOW_DIALOG] ERROR: errorClass=" + errorClass(e), e);
            pendingAskUserQuestionRequests.remove(requestId);
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

            CompletableFuture<JsonObject> pendingFuture = pendingAskUserQuestionRequests.remove(requestId);

            if (pendingFuture != null) {
                LOG.debug("[ASK_USER_QUESTION][HANDLE_RESPONSE] Completing future with answerCount=" + answers.size());
                pendingFuture.complete(answers);
                return;
            }

            PendingQuestion pending = pendingQuestions.remove(requestId);
            if (pending == null) {
                LOG.warn("[ASK_USER_QUESTION][HANDLE_RESPONSE] No pending request found for requestId: " + requestId);
                return;
            }

            // Emit complete Q&A data to webview
            JsonObject onQAData = new JsonObject();
            onQAData.addProperty("callId", pending.toolName != null ? pending.toolName : "");
            onQAData.addProperty("requestId", requestId);
            onQAData.add("questions", pending.questions);
            onQAData.add("answers", answers);
            context.executeJavaScriptOnEDT("if (window.onQuestionAnswered) { window.onQuestionAnswered('"
                    + escapeJs(gson.toJson(onQAData)) + "'); }");

            JsonArray orderedAnswers = buildOrderedAnswers(pending.questions, answers);

            OpenCodeSDKBridge bridge = context.getOpenCodeSDKBridge();
            if (bridge != null) {
                bridge.replyQuestion(pending.sessionId, requestId, orderedAnswers, pending.directory)
                        .whenComplete((ok, error) -> {
                            if (error != null || !Boolean.TRUE.equals(ok)) {
                                String msg = error != null ? error.getMessage() : "daemon rejected";
                                LOG.warn("[OpenCode] replyQuestion failed: id=" + requestId + " error=" + msg);
                                context.executeJavaScriptOnEDT("if (window.showToast) { window.showToast('"
                                        + escapeJs("回答提交失败: " + msg) + "'); }");
                                forceCloseFrontendDialog("invalidateQuestionCard", requestId);
                            }
                        });
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
            PendingQuestion pending = pendingQuestions.remove(requestId);
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
                String qText = qObj.has("question") && !qObj.get("question").isJsonNull()
                        ? qObj.get("question").getAsString()
                        : (qObj.has("text") && !qObj.get("text").isJsonNull() ? qObj.get("text").getAsString() : "");
                if (answers != null && qText != null && answers.has(qText) && !answers.get(qText).isJsonNull()) {
                    com.google.gson.JsonElement val = answers.get(qText);
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
     * Show PlanApproval dialog (implements PermissionService.PlanApprovalDialogShower interface).
     */
    public CompletableFuture<JsonObject> showPlanApprovalDialog(String requestId, JsonObject planData) {
        CompletableFuture<JsonObject> future = new CompletableFuture<>();

        LOG.debug("[PLAN_APPROVAL][SHOW_DIALOG] Starting showPlanApprovalDialog");
        LOG.debug("[PLAN_APPROVAL][SHOW_DIALOG] requestId=" + requestId);
        LOG.debug("[PLAN_APPROVAL][SHOW_DIALOG] fieldCount=" + planData.size());

        pendingPlanApprovalRequests.put(requestId, future);

        try {
            Gson gson = new Gson();
            String requestJson = gson.toJson(planData);
            String escapedJson = escapeJs(requestJson);

            ApplicationManager.getApplication().invokeLater(() -> {
                String jsCode = "(function retryShowPlanApproval(retries) { " +
                    "  if (window.showPlanApprovalDialog) { " +
                    "    window.showPlanApprovalDialog('" + escapedJson + "'); " +
                    "  } else if (retries > 0) { " +
                    "    setTimeout(function() { retryShowPlanApproval(retries - 1); }, 200); " +
                    "  } else { " +
                    "    console.error('[PLAN_APPROVAL][JS] FAILED: showPlanApprovalDialog not available!'); " +
                    "  } " +
                    "})(30);";

                context.executeJavaScriptOnEDT(jsCode);
            });

            scheduleSafetyNet(future, () -> {
                JsonObject timeoutResponse = new JsonObject();
                timeoutResponse.addProperty("approved", false);
                timeoutResponse.addProperty("targetMode", "default");
                timeoutResponse.addProperty("message", "Plan approval timed out");
                if (future.complete(timeoutResponse)) {
                    LOG.warn("[PLAN_APPROVAL][SHOW_DIALOG] Safety-net timeout fired (webview unreachable) for requestId=" + requestId);
                    pendingPlanApprovalRequests.remove(requestId);
                    forceCloseFrontendDialog("forceClosePlanApprovalDialog", requestId);
                }
            });

        } catch (Exception e) {
            LOG.error("[PLAN_APPROVAL][SHOW_DIALOG] ERROR: errorClass=" + errorClass(e), e);
            pendingPlanApprovalRequests.remove(requestId);
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

            CompletableFuture<JsonObject> pendingFuture = pendingPlanApprovalRequests.remove(requestId);

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
