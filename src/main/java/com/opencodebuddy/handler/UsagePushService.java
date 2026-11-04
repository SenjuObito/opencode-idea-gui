package com.opencodebuddy.handler;

import com.opencodebuddy.handler.core.HandlerContext;

import com.opencodebuddy.notifications.OpencodeNotifier;
import com.opencodebuddy.session.EditorContextService;
import com.opencodebuddy.session.EditorContextSnapshot;
import com.opencodebuddy.session.OpencodeSession;
import com.opencodebuddy.util.TokenUsageUtils;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

import java.util.List;

/**
 * Handles usage statistics push and context bar refresh operations.
 */
public class UsagePushService {

    private static final Logger LOG = Logger.getInstance(UsagePushService.class);

    private final HandlerContext context;
    private final Gson gson = new Gson();

    public UsagePushService(HandlerContext context) {
        this.context = context;
    }

    /**
     * Push usage update after model switch.
     * Recalculates percentage and maxTokens based on the new model's context limit.
     */
    public void pushUsageUpdateAfterModelChange(int newMaxTokens) {
        pushUsageUpdateAfterModelChange(context.getActiveTabSession(), newMaxTokens);
    }

    public void pushUsageUpdateAfterModelChange(OpencodeSession target, int newMaxTokens) {
        try {
            OpencodeSession session = target != null ? target : context.getActiveTabSession();
            if (session == null) {
                clearUsageDisplay(null);
                return;
            }

            // Extract the latest usage information from the addressed session.
            List<OpencodeSession.Message> messages = session.getMessages();
            JsonObject lastUsage = TokenUsageUtils.findLastUsageFromSessionMessages(
                    messages,
                    session.getProvider()
            );
            if (lastUsage == null) {
                // No provider snapshot is available yet. Keep the context unknown
                // instead of presenting a static capacity as session truth.
                clearUsageDisplay(session);
                return;
            }
            int usedTokens = TokenUsageUtils.extractContextTokens(lastUsage, session.getProvider());

            // Send update
            sendUsageUpdate(session, usedTokens, newMaxTokens);

        } catch (Exception e) {
            LOG.error("[UsagePushService] Failed to push usage update after model change: " + e.getMessage(), e);
        }
    }

    /**
     * Push the context snapshot already retained by the active session during WebView
     * recovery. Empty sessions remain untouched because their history may still be loading.
     *
     * @param fallbackMaxTokens static model limit used only when provider metadata does not
     *                          contain a session-specific context window
     * @return true when a trusted usage snapshot was scheduled for delivery
     */
    public boolean pushCurrentUsageIfAvailable(int fallbackMaxTokens) {
        return pushCurrentUsageIfAvailable(context.getActiveTabSession(), fallbackMaxTokens);
    }

    public boolean pushCurrentUsageIfAvailable(OpencodeSession target, int fallbackMaxTokens) {
        try {
            OpencodeSession session = target != null ? target : context.getActiveTabSession();
            if (session == null) {
                return false;
            }

            String provider = session.getProvider();
            JsonObject lastUsage = TokenUsageUtils.findLastUsageFromSessionMessages(
                    session.getMessages(), provider);
            if (lastUsage == null) {
                return false;
            }

            int usedTokens = TokenUsageUtils.extractContextTokens(lastUsage, provider);
            int maxTokens = TokenUsageUtils.extractMaxTokens(lastUsage, fallbackMaxTokens);
            sendUsageUpdate(session, usedTokens, maxTokens);
            return true;
        } catch (Exception e) {
            LOG.error("[UsagePushService] Failed to restore current usage: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * Send usage update to the frontend.
     */
    public void sendUsageUpdate(int usedTokens, int maxTokens) {
        sendUsageUpdate(context.getActiveTabSession(), usedTokens, maxTokens);
    }

    public void sendUsageUpdate(OpencodeSession session, int usedTokens, int maxTokens) {
        int percentage = Math.min(100, maxTokens > 0 ? (int) ((usedTokens * 100.0) / maxTokens) : 0);

        LOG.info("[UsagePushService] Sending usage update: usedTokens=" + usedTokens + ", maxTokens=" + maxTokens + ", percentage=" + percentage + "%");

        // Build usage update data
        JsonObject usageUpdate = new JsonObject();
        usageUpdate.addProperty("percentage", percentage);
        usageUpdate.addProperty("totalTokens", usedTokens);
        usageUpdate.addProperty("limit", maxTokens);
        usageUpdate.addProperty("usedTokens", usedTokens);
        usageUpdate.addProperty("maxTokens", maxTokens);

        sendUsagePayload(usageUpdate, session);
    }

    /**
     * Clear provider-specific usage while the next provider snapshot is unknown.
     * Omitting numerator and denominator also clears the frontend tooltip values.
     */
    public void clearUsageDisplay() {
        clearUsageDisplay(context.getActiveTabSession());
    }

    public void clearUsageDisplay(OpencodeSession session) {
        JsonObject usageUpdate = new JsonObject();
        usageUpdate.addProperty("percentage", 0);
        if (session == null || session == context.getActiveTabSession()) {
            clearStatusBarUsage();
            sendUsagePayload(usageUpdate);
        } else {
            sendUsagePayload(usageUpdate, session);
        }
    }

    void clearStatusBarUsage() {
        if (context.getProject() != null) {
            OpencodeNotifier.clearTokenUsage(context.getProject());
        }
    }

    void sendUsagePayload(JsonObject usageUpdate) {
        sendUsagePayload(usageUpdate, context.getActiveTabSession());
    }

    void sendUsagePayload(JsonObject usageUpdate, OpencodeSession session) {
        String usageJson = gson.toJson(usageUpdate);
        String sessionId = (session != null) ? session.getSessionId() : null;
        if (sessionId == null || sessionId.isEmpty()) {
            // Never send an un-attributable usage update: the webview used to read
            // a missing id as "the active tab", so a brand-new session (which has
            // no daemon id yet) was overwritten with whatever tokens the previous
            // session had just produced.
            LOG.debug("[UsagePushService] Dropping usage payload without a session id");
            return;
        }
        context.publishEvent(com.opencodebuddy.tab.TabEvent.of("usage.updated")
                .tab(context.getTabIdForSession(sessionId))
                .session(sessionId)
                .json("usage", usageJson)
                .build());
    }

    /**
     * Refresh the context bar with the currently open editor file.
     */
    public void refreshContextBar() {
        Application application = ApplicationManager.getApplication();
        if (application == null) {
            LOG.debug("[UsagePushService] No application; skipping context bar refresh");
            return;
        }
        application.invokeLater(() -> {
            try {
                if (context.getProject() == null) {
                    return;
                }

                EditorContextService contextService =
                        EditorContextService.getInstance(context.getProject());
                EditorContextSnapshot snapshot = contextService != null
                        ? contextService.getSnapshot()
                        : EditorContextSnapshot.EMPTY;

                if (snapshot.hasFile()) {
                    StringBuilder info = new StringBuilder("@").append(snapshot.file());
                    if (snapshot.hasSelection()) {
                        info.append("#L").append(snapshot.startLine())
                                .append("-").append(snapshot.endLine());
                    }
                    context.callJavaScript("addSelectionInfo", context.escapeJs(info.toString()));
                } else {
                    context.callJavaScript("clearSelectionInfo");
                }
            } catch (Exception e) {
                LOG.warn("[UsagePushService] Failed to refresh context bar: " + e.getMessage());
            }
        });
    }
}
