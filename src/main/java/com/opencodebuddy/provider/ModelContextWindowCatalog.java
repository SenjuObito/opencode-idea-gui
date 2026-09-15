package com.opencodebuddy.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Runtime catalog of the context windows reported by the opencode model list.
 *
 * <p>The opencode daemon sources each model's total context window from
 * models.dev ({@code limit.context}) — the authoritative number for models such
 * as {@code deepseek/deepseek-v4-pro} (1M) that the hardcoded
 * {@code MODEL_CONTEXT_LIMITS} table does not know about. Historically that field
 * was dropped while building the list payload, so every unlisted model fell back
 * to a 200k guess and the usage ring showed the wrong denominator.</p>
 *
 * <p>A context window is an intrinsic property of a model id rather than of a
 * project or tab, so the catalog is application-scoped and keyed by model id.
 * It is populated from every model-list payload
 * {@link com.opencodebuddy.handler.CliModelsHandler} sees — cached or freshly
 * fetched — and consulted by
 * {@link com.opencodebuddy.handler.provider.ModelProviderHandler} <em>before</em>
 * any hardcoded fallback.</p>
 *
 * <p>Updates only ever merge: a payload without limit metadata (the legacy
 * {@code opencode models} stdout fallback, or a cache entry written by an older
 * build) must not erase what a richer payload already taught us.</p>
 */
public final class ModelContextWindowCatalog {

    private static final Logger LOG = Logger.getInstance(ModelContextWindowCatalog.class);
    private static final String MODELS_KEY = "models";
    private static final String ID_KEY = "id";
    private static final String CONTEXT_WINDOW_KEY = "contextWindow";

    private static volatile ModelContextWindowCatalog instance;

    private final Object lock = new Object();
    /** Model id to context window. Guarded by {@link #lock}. */
    private final Map<String, Integer> contextWindows = new HashMap<>();

    private ModelContextWindowCatalog() {
    }

    public static ModelContextWindowCatalog getInstance() {
        ModelContextWindowCatalog local = instance;
        if (local == null) {
            synchronized (ModelContextWindowCatalog.class) {
                local = instance;
                if (local == null) {
                    local = new ModelContextWindowCatalog();
                    instance = local;
                }
            }
        }
        return local;
    }

    @org.jetbrains.annotations.TestOnly
    public static void setInstanceForTests(ModelContextWindowCatalog testInstance) {
        instance = testInstance;
    }

    @org.jetbrains.annotations.TestOnly
    public static ModelContextWindowCatalog createForTests() {
        return new ModelContextWindowCatalog();
    }

    /**
     * Merge every context window carried by a model-list payload.
     *
     * @param payload the {@code listModels} payload ({@code { models: [...] }})
     * @return true when at least one new or changed window was learned, so callers
     *         can republish a usage snapshot that was computed before the catalog
     *         was warm
     */
    public boolean updateFromModelsPayload(JsonObject payload) {
        if (payload == null || !payload.has(MODELS_KEY) || !payload.get(MODELS_KEY).isJsonArray()) {
            return false;
        }

        JsonArray models = payload.getAsJsonArray(MODELS_KEY);
        boolean changed = false;
        synchronized (lock) {
            for (JsonElement element : models) {
                if (element == null || !element.isJsonObject()) {
                    continue;
                }
                JsonObject model = element.getAsJsonObject();
                Integer contextWindow = readContextWindow(model);
                if (contextWindow == null || !model.has(ID_KEY) || model.get(ID_KEY).isJsonNull()) {
                    continue;
                }
                changed |= register(model.get(ID_KEY).getAsString(), contextWindow);
            }
        }
        if (changed) {
            LOG.info("[ModelContextWindowCatalog] Context windows known: " + size());
        }
        return changed;
    }

    /**
     * Resolve a context window by model id.
     *
     * <p>Both the fully qualified {@code provider/model} form and the bare model
     * id are honoured, because opencode hands the plugin {@code provider/model}
     * while other call sites may hold either form.</p>
     *
     * <p>Ids carrying an explicit capacity suffix (for example
     * {@code claude-sonnet-5[1m]}) deliberately miss here and fall through to the
     * suffix parser, so an explicit user-chosen capacity always wins.</p>
     *
     * @param modelId raw model id, may be null or blank
     * @return the known context window, or empty when the catalog cannot answer
     */
    public OptionalInt getContextWindow(String modelId) {
        if (modelId == null) {
            return OptionalInt.empty();
        }
        String trimmed = modelId.trim();
        if (trimmed.isEmpty()) {
            return OptionalInt.empty();
        }

        synchronized (lock) {
            Integer exact = contextWindows.get(trimmed);
            if (exact != null) {
                return OptionalInt.of(exact);
            }
            int slash = trimmed.indexOf('/');
            if (slash >= 0 && slash < trimmed.length() - 1) {
                Integer bare = contextWindows.get(trimmed.substring(slash + 1));
                if (bare != null) {
                    return OptionalInt.of(bare);
                }
            }
        }
        return OptionalInt.empty();
    }

    public void clear() {
        synchronized (lock) {
            contextWindows.clear();
        }
    }

    int size() {
        synchronized (lock) {
            return contextWindows.size();
        }
    }

    /**
     * Record one window under its qualified id, plus under its bare model id when
     * no other provider already claimed that bare name.
     *
     * @return true when a previously unknown value was stored
     */
    private boolean register(String rawModelId, int contextWindow) {
        String modelId = rawModelId.trim();
        if (modelId.isEmpty()) {
            return false;
        }
        boolean changed = putIfChanged(modelId, contextWindow);

        int slash = modelId.indexOf('/');
        if (slash >= 0 && slash < modelId.length() - 1) {
            // Bare ids are ambiguous across providers, so first writer wins.
            String bare = modelId.substring(slash + 1);
            if (!contextWindows.containsKey(bare)) {
                changed |= putIfChanged(bare, contextWindow);
            }
        }
        return changed;
    }

    /**
     * @return true when the stored value is new or different from the previous one
     */
    private boolean putIfChanged(String key, int contextWindow) {
        Integer previous = contextWindows.put(key, contextWindow);
        return previous == null || previous != contextWindow;
    }

    /**
     * Read the window a payload entry advertises.
     *
     * @return the token count, or null when the entry carries no usable value
     */
    private static Integer readContextWindow(JsonObject model) {
        JsonElement element = model.get(CONTEXT_WINDOW_KEY);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        try {
            int value = element.getAsBigDecimal().intValueExact();
            return value > 0 ? value : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
