package com.opencodebuddy.handler.provider;

import com.opencodebuddy.handler.UsagePushService;
import com.opencodebuddy.handler.core.HandlerContext;
import com.opencodebuddy.session.OpencodeSession;

import com.opencodebuddy.provider.CustomModelContextWindowProvider;
import com.opencodebuddy.provider.ModelContextWindowCatalog;
import com.opencodebuddy.util.TokenUsageUtils;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

/**
 * Handles model selection and reasoning effort for the OpenCode-only build.
 * The provider is fixed to {@code opencode}; model ids are
 * {@code provider/model} strings resolved by opencode itself.
 */
public class ModelProviderHandler {

    private static final Logger LOG = Logger.getInstance(ModelProviderHandler.class);

    private static final String FIXED_PROVIDER = "opencode";

    private final HandlerContext context;
    private final UsagePushService usagePushService;
    private final Gson gson = new Gson();

    public ModelProviderHandler(HandlerContext context, UsagePushService usagePushService) {
        this.context = context;
        this.usagePushService = usagePushService;
    }

    /**
     * Resolve the session a model/effort change belongs to.
     *
     * <p>Payloads may carry a {@code sessionId}: the webview hosts several sessions
     * and each tab owns its own selection, so "the window's current session" is not
     * a safe default — writing there changes the wrong conversation while the UI
     * reports success. An explicit target that cannot be resolved is a no-op (the
     * tab was closed or has not bound a session yet); it must never fall back.
     * Only the legacy bare-string format keeps the window-scoped behaviour.</p>
     */
    private OpencodeSession resolveTargetSession(String sessionId, boolean hasExplicitScope, String channel) {
        if (sessionId != null && !sessionId.trim().isEmpty()) {
            OpencodeSession scoped = context.getSession(sessionId.trim());
            if (scoped == null) {
                LOG.warn("[ModelProviderHandler] " + channel + ": unknown sessionId " + sessionId
                        + " — ignoring instead of falling back to the window's current session");
            }
            return scoped;
        }
        if (hasExplicitScope) {
            LOG.warn("[ModelProviderHandler] " + channel
                    + ": payload has no sessionId — ignoring (an unbound tab sends its selection with the message)");
            return null;
        }
        return context.getActiveTabSession();
    }

    public void handleSetModel(String content) {
        try {
            String model = content;
            // `silent` marks a value a tab switch is restoring rather than a user
            // choice. Such a replay must not clear the session's context usage or
            // push an empty usage row, otherwise alternating between two tabs
            // with different models zeroes the usage ring every time.
            boolean silent = false;
            boolean scopedPayload = false;
            String targetSessionId = null;
            if (content != null && !content.isEmpty()) {
                try {
                    JsonObject json = gson.fromJson(content, JsonObject.class);
                    scopedPayload = true;
                    if (json.has("model")) {
                        model = json.get("model").getAsString();
                    }
                    if (json.has("silent") && !json.get("silent").isJsonNull()) {
                        silent = json.get("silent").getAsBoolean();
                    }
                    if (json.has("sessionId") && !json.get("sessionId").isJsonNull()) {
                        targetSessionId = json.get("sessionId").getAsString();
                    }
                } catch (Exception e) {
                    // content itself is the model
                }
            }

            OpencodeSession targetSession = resolveTargetSession(targetSessionId, scopedPayload, "set_model");
            // An explicitly scoped payload with no resolvable target is a no-op.
            // This covers both an unbound tab (no sessionId yet) and a closed tab.
            if (targetSession == null && scopedPayload) {
                return;
            }

            String previousModel = context.getCurrentModel();
            boolean modelChanged = previousModel == null || !previousModel.equals(model);
            LOG.info("[ModelProviderHandler] Setting model to: " + model
                    + " (was: " + previousModel + ")"
                    + (silent ? " [silent restore]" : "")
                    + (targetSessionId != null ? " [session=" + targetSessionId + "]" : ""));
            context.setCurrentModel(model);

            if (targetSession != null) {
                targetSession.setModel(model);
                if (modelChanged && !silent) {
                    TokenUsageUtils.clearContextUsageFromSessionMessages(
                            targetSession.getMessages());
                }
            }

            if (modelChanged && !silent) {
                usagePushService.clearUsageDisplay(targetSession);
            }

            if (!silent && context.getProject() != null) {
                com.opencodebuddy.notifications.OpencodeNotifier.setModel(context.getProject(), model);
            }

            int newMaxTokens = getModelContextLimit(FIXED_PROVIDER, model);
            LOG.info("[ModelProviderHandler] Model context limit: " + newMaxTokens
                    + " tokens for selected model: " + model);

            final String confirmedModel = model;
            final String confirmedProvider = context.getCurrentProvider();
            ApplicationManager.getApplication().invokeLater(() -> {
                context.callJavaScript("window.onModelConfirmed",
                        context.escapeJs(confirmedModel), context.escapeJs(confirmedProvider));
                if (modelChanged) {
                    usagePushService.pushUsageUpdateAfterModelChange(targetSession, newMaxTokens);
                }
            });
        } catch (Exception e) {
            LOG.error("[ModelProviderHandler] Failed to set model: " + e.getMessage(), e);
        }
    }

    public void handleSetReasoningEffort(String content) {
        try {
            String effort = content;
            boolean scopedPayload = false;
            String targetSessionId = null;
            if (content != null && !content.isEmpty()) {
                try {
                    JsonObject json = gson.fromJson(content, JsonObject.class);
                    scopedPayload = true;
                    if (json.has("reasoningEffort")) {
                        effort = json.get("reasoningEffort").getAsString();
                    }
                    if (json.has("sessionId") && !json.get("sessionId").isJsonNull()) {
                        targetSessionId = json.get("sessionId").getAsString();
                    }
                } catch (Exception e) {
                    // content itself is the effort
                }
            }
            OpencodeSession targetSession = resolveTargetSession(targetSessionId, scopedPayload, "set_reasoning_effort");
            if (targetSession == null && scopedPayload) {
                return;
            }
            if (targetSession != null) {
                targetSession.setReasoningEffort(effort);
            }
            LOG.info("[ModelProviderHandler] Set reasoning effort (variant): " + effort
                    + (targetSessionId != null ? " [session=" + targetSessionId + "]" : ""));
        } catch (Exception e) {
            LOG.error("[ModelProviderHandler] Failed to set reasoning effort: " + e.getMessage(), e);
        }
    }

    public static int getModelContextLimit(String model) {
        if (model == null || model.isEmpty()) {
            return 0;
        }

        String trimmed = model.trim();

        // Explicit bracketed capacity suffix, e.g. "claude-sonnet-4-7 [1m]" or "model[200k]"
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\s*\\[([0-9.]+)([kKmM])\\]\\s*$");
        java.util.regex.Matcher matcher = pattern.matcher(trimmed);

        if (matcher.find()) {
            try {
                double value = Double.parseDouble(matcher.group(1));
                String unit = matcher.group(2).toLowerCase();
                if ("k".equals(unit)) {
                    return (int) (value * 1000);
                } else if ("m".equals(unit)) {
                    return (int) (value * 1_000_000);
                }
            } catch (NumberFormatException ignored) {
            }
        }

        return 0;
    }

    /**
     * Resolve a model's context window, preferring real provider metadata over
     * any bracketed fallback.
     *
     * <p>Resolution order:</p>
     * <ol>
     *   <li>{@code customModelContextWindows} — explicit user configuration</li>
     *   <li>{@link ModelContextWindowCatalog} — the live opencode model list
     *       (models.dev {@code limit.context})</li>
     *   <li>{@link #getModelContextLimit(String)} — bracketed capacity suffix,
     *       otherwise 0 (unknown)</li>
     * </ol>
     *
     * <p>Ids carrying an explicit capacity suffix miss the catalog by design and
     * fall through to the suffix parser, so {@code model[1m]} keeps winning over
     * whatever the catalog reports for the bare id.</p>
     */
    public static int getModelContextLimit(String provider, String model) {
        return CustomModelContextWindowProvider.getInstance()
                .getContextWindow(provider, model)
                .orElseGet(() -> ModelContextWindowCatalog.getInstance()
                        .getContextWindow(model)
                        .orElseGet(() -> getModelContextLimit(model)));
    }
}
