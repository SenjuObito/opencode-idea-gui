package com.opencodebuddy.handler.provider;

import com.opencodebuddy.handler.UsagePushService;
import com.opencodebuddy.handler.core.HandlerContext;

import com.opencodebuddy.provider.CustomModelContextWindowProvider;
import com.opencodebuddy.provider.ModelContextWindowCatalog;
import com.opencodebuddy.util.TokenUsageUtils;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

import java.util.HashMap;
import java.util.Map;

/**
 * Handles model selection and reasoning effort for the OpenCode-only build.
 * The provider is fixed to {@code opencode}; model ids are
 * {@code provider/model} strings resolved by opencode itself.
 */
public class ModelProviderHandler {

    private static final Logger LOG = Logger.getInstance(ModelProviderHandler.class);

    static final Map<String, Integer> MODEL_CONTEXT_LIMITS = new HashMap<>();
    static {
        // Known models limits
        MODEL_CONTEXT_LIMITS.put("qwen3-coder", 256_000);
        MODEL_CONTEXT_LIMITS.put("grok-code", 256_000);
        MODEL_CONTEXT_LIMITS.put("claude-sonnet-4-5", 200_000);
        MODEL_CONTEXT_LIMITS.put("claude-sonnet-4-7", 200_000);
        MODEL_CONTEXT_LIMITS.put("claude-opus-4", 200_000);
        MODEL_CONTEXT_LIMITS.put("claude-opus-4-1", 200_000);
        MODEL_CONTEXT_LIMITS.put("claude-3-7-sonnet", 200_000);
        MODEL_CONTEXT_LIMITS.put("gpt-5", 400_000);
        MODEL_CONTEXT_LIMITS.put("gpt-5-codex", 400_000);
        MODEL_CONTEXT_LIMITS.put("gpt-4o", 128_000);
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
                    usagePushService.pushUsageUpdateAfterModelChange(newMaxTokens);
                }
            });
        } catch (Exception e) {
            LOG.error("[ModelProviderHandler] Failed to set model: " + e.getMessage(), e);
        }
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

    public static int getModelContextLimit(String model) {
        if (model == null || model.isEmpty()) {
            return 200_000;
        }

        String trimmed = model.trim();

        // 1. Explicit bracketed capacity suffix, e.g. "claude-sonnet-4-7 [1m]" or "model[200k]"
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

        Integer known = MODEL_CONTEXT_LIMITS.get(trimmed);
        if (known != null) {
            return known;
        }

        // 2. Strip provider prefix if present (e.g. "anthropic/claude-3-7-sonnet" -> "claude-3-7-sonnet")
        int slashIdx = trimmed.indexOf('/');
        String bareModel = slashIdx >= 0 ? trimmed.substring(slashIdx + 1) : trimmed;
        known = MODEL_CONTEXT_LIMITS.get(bareModel);
        if (known != null) {
            return known;
        }

        // 3. Prefix matching
        for (Map.Entry<String, Integer> entry : MODEL_CONTEXT_LIMITS.entrySet()) {
            if (trimmed.startsWith(entry.getKey()) || bareModel.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }

        return 200_000;
    }

    /**
     * Resolve a model's context window, preferring real provider metadata over
     * the hardcoded table.
     *
     * <p>Resolution order:</p>
     * <ol>
     *   <li>{@code customModelContextWindows} — explicit user configuration</li>
     *   <li>{@link ModelContextWindowCatalog} — the live opencode model list
     *       (models.dev {@code limit.context})</li>
     *   <li>{@link #getModelContextLimit(String)} — bracketed capacity suffix,
     *       then the hardcoded table, then the 200k default</li>
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
