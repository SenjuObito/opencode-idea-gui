package com.opencodebuddy.handler.provider;

import com.opencodebuddy.handler.UsagePushService;
import com.opencodebuddy.handler.core.HandlerContext;

import com.opencodebuddy.provider.CustomModelContextWindowProvider;
import com.opencodebuddy.util.TokenUsageUtils;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

import java.util.HashMap;
import java.util.Map;

/**
 * Handles model and provider selection and reasoning effort for the
 * OpenCode-only build. The provider is fixed to {@code opencode}; model ids
 * are {@code provider/model} strings resolved by opencode itself.
 */
public class ModelProviderHandler {

    private static final Logger LOG = Logger.getInstance(ModelProviderHandler.class);

    static final Map<String, Integer> MODEL_CONTEXT_LIMITS = new HashMap<>();
    static {
        // Known opencode zen models; unknown models fall back to the default
        // window parsed from "[Nk]/[Nm]" suffixes or 200k.
        MODEL_CONTEXT_LIMITS.put("qwen3-coder", 256_000);
        MODEL_CONTEXT_LIMITS.put("grok-code", 256_000);
        MODEL_CONTEXT_LIMITS.put("claude-sonnet-4-5", 200_000);
    }

    private static final String FIXED_PROVIDER = "opencode";

    private final HandlerContext context;
    private final UsagePushService usagePushService;
    private final Gson gson = new Gson();

    public ModelProviderHandler(HandlerContext context, UsagePushService usagePushService) {
        this.context = context;
        this.usagePushService = usagePushService;
    }

    public void handleSetModel(String content) {
        try {
            String model = content;
            if (content != null && !content.isEmpty()) {
                try {
                    JsonObject json = gson.fromJson(content, JsonObject.class);
                    if (json.has("model")) {
                        model = json.get("model").getAsString();
                    }
                } catch (Exception e) {
                    // content itself is the model
                }
            }

            String previousModel = context.getCurrentModel();
            boolean modelChanged = previousModel == null || !previousModel.equals(model);
            LOG.info("[ModelProviderHandler] Setting model to: " + model
                    + " (was: " + previousModel + ")");
            context.setCurrentModel(model);

            if (context.getSession() != null) {
                context.getSession().setModel(model);
                if (modelChanged) {
                    TokenUsageUtils.clearContextUsageFromSessionMessages(
                            context.getSession().getMessages());
                }
            }

            if (modelChanged) {
                usagePushService.clearUsageDisplay();
            }

            if (context.getProject() != null) {
                com.opencodebuddy.notifications.ClaudeNotifier.setModel(context.getProject(), model);
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
                    usagePushService.pushUsageUpdateAfterModelChange(newMaxTokens);
                }
            });
        } catch (Exception e) {
            LOG.error("[ModelProviderHandler] Failed to set model: " + e.getMessage(), e);
        }
    }

    public void handleSetProvider(String content) {
        // OpenCode-only build: the provider cannot be changed from the UI.
        // Echo the fixed provider so the frontend state stays consistent.
        ApplicationManager.getApplication().invokeLater(() ->
                context.callJavaScript("window.onProviderConfirmed",
                        context.escapeJs(FIXED_PROVIDER)));
    }

    public void handleSetReasoningEffort(String content) {
        try {
            String effort = content;
            if (content != null && !content.isEmpty()) {
                try {
                    JsonObject json = gson.fromJson(content, JsonObject.class);
                    if (json.has("reasoningEffort")) {
                        effort = json.get("reasoningEffort").getAsString();
                    }
                } catch (Exception e) {
                    // content itself is the effort
                }
            }
            if (context.getSession() != null) {
                context.getSession().setReasoningEffort(effort);
            }
            LOG.info("[ModelProviderHandler] Set reasoning effort (variant): " + effort);
        } catch (Exception e) {
            LOG.error("[ModelProviderHandler] Failed to set reasoning effort: " + e.getMessage(), e);
        }
    }

    public void handleSetCodexFastMode(String content) {
        // Legacy multi-engine toggle: no-op in the OpenCode-only build.
    }

    public static int getModelContextLimit(String model) {
        if (model == null || model.isEmpty()) {
            return 200_000;
        }

        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\s*\\[([0-9.]+)([kKmM])\\]\\s*$");
        java.util.regex.Matcher matcher = pattern.matcher(model);

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

        Integer known = MODEL_CONTEXT_LIMITS.get(model);
        return known != null ? known : 200_000;
    }

    public static int getModelContextLimit(String provider, String model) {
        return CustomModelContextWindowProvider.getInstance()
                .getContextWindow(provider, model)
                .orElseGet(() -> getModelContextLimit(model));
    }
}
