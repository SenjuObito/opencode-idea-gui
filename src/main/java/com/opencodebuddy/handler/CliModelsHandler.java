package com.opencodebuddy.handler;

import com.opencodebuddy.bridge.BridgeDirectoryResolver;
import com.opencodebuddy.bridge.EnvironmentConfigurator;
import com.opencodebuddy.bridge.NodeDetector;
import com.opencodebuddy.handler.core.BaseMessageHandler;
import com.opencodebuddy.handler.core.HandlerContext;
import com.opencodebuddy.handler.provider.ModelProviderHandler;
import com.opencodebuddy.provider.ModelContextWindowCatalog;
import com.opencodebuddy.session.OpencodeSession;
import com.opencodebuddy.startup.BridgePreloader;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Lists models for headless CLI providers (Kimi / OpenCode).
 *
 * <p>Preferred path: the persistent daemon ({@code opencode.getModels} →
 * models-service.js listModels via the running {@code opencode serve}), so no
 * cold Node/SDK start per refresh. Fallback: spawn a one-shot Node process
 * running {@code channel-manager.js <provider> listModels} (kept for the case
 * where the daemon cannot serve the request).</p>
 *
 * <p>Cache-first: the last successful payload is persisted per project
 * (PropertiesComponent) and pushed immediately on request, then a fresh list
 * is fetched asynchronously. This mirrors the vscode-plugin's
 * {@code opencode.cliModelsCache} warmup behavior and keeps the model dropdown
 * populated across webview reloads.</p>
 *
 * <p>Frontend: {@code sendToJava('get_cli_models:opencode')} →
 * {@code window.setCliModels({ provider, models, defaultModel?, ... })}.</p>
 */
public class CliModelsHandler extends BaseMessageHandler {

    private static final Logger LOG = Logger.getInstance(CliModelsHandler.class);
    private static final String CHANNEL_SCRIPT = "channel-manager.js";
    private static final long TIMEOUT_SECONDS = 50L;
    /** Upper bound for one daemon round-trip (may include a daemon + serve start). */
    private static final long DAEMON_REQUEST_TIMEOUT_SECONDS = 90L;
    /** Cap on captured stdout — a model list is small; this stops memory exhaustion. */
    private static final int MAX_OUTPUT_CHARS = 64_000;

    /** Project-level cache of the last successful listModels payload (JSON). */
    static final String CACHE_KEY = "opencode.cliModelsCache.";

    private static final String[] SUPPORTED_TYPES = {
            "get_cli_models",
    };

    private static final Set<String> SUPPORTED_PROVIDERS = Set.of(
            "opencode"
    );

    private final Gson gson = new Gson();
    private final NodeDetector nodeDetector = NodeDetector.getInstance();
    private final EnvironmentConfigurator envConfigurator = new EnvironmentConfigurator();

    public CliModelsHandler(HandlerContext context) {
        super(context);
    }

    @Override
    public String[] getSupportedTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public boolean handle(String type, String content) {
        if (!"get_cli_models".equals(type)) {
            return false;
        }
        String provider = content != null ? content.trim().toLowerCase(Locale.ROOT) : "";
        if (!SUPPORTED_PROVIDERS.contains(provider)) {
            pushError(provider, "Unsupported CLI provider for model list: " + provider);
            return true;
        }
        CompletableFuture.runAsync(() -> listModels(provider), AppExecutorUtil.getAppExecutorService());
        return true;
    }

    private void listModels(String provider) {
        JsonObject refreshed = listModelsViaDaemon(provider);
        if (refreshed == null) {
            // Daemon unavailable or errored — fall back to the one-shot
            // channel-manager process (it starts its own serve if needed).
            refreshed = listModelsViaChannelManager(provider);
        }
        if (refreshed == null) {
            pushError(provider, "No model list available for " + provider);
            return;
        }
        ensureProviderField(refreshed, provider);
        writeCachedPayload(provider, refreshed);
        pushPayload(refreshed);
    }

    // =========================================================================
    // Daemon path (preferred — reuses the persistent Node daemon + serve)
    // =========================================================================

    private JsonObject listModelsViaDaemon(String provider) {
        try {
            JsonObject params = new JsonObject();
            JsonElement result = context.getOpenCodeSDKBridge()
                    .requestJson("opencode.getModels", params)
                    .get(DAEMON_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!(result instanceof JsonObject payload) || !payloadHasModels(payload)) {
                LOG.info("[CliModels] Daemon getModels returned no usable payload; falling back");
                return null;
            }
            LOG.info("[CliModels] Listed models for " + provider + " via daemon");
            return payload;
        } catch (Exception e) {
            LOG.info("[CliModels] Daemon getModels unavailable (" + e.getMessage() + "); falling back");
            return null;
        }
    }

    private static boolean payloadHasModels(JsonObject payload) {
        if (!payload.has("models") || !payload.get("models").isJsonArray()) {
            return false;
        }
        return payload.getAsJsonArray("models").size() > 0;
    }

    private void ensureProviderField(JsonObject payload, String provider) {
        if (!payload.has("provider") || payload.get("provider").isJsonNull()) {
            payload.addProperty("provider", provider);
        }
    }

    // =========================================================================
    // Channel-manager fallback (one-shot Node process)
    // =========================================================================

    private JsonObject listModelsViaChannelManager(String provider) {
        try {
            String node = nodeDetector.findNodeExecutable();
            BridgeDirectoryResolver resolver = BridgePreloader.getSharedResolver();
            File bridgeDir = resolver != null ? resolver.findSdkDir() : null;
            if (bridgeDir == null || !bridgeDir.exists()) {
                LOG.warn("[CliModels] Bridge directory not ready");
                return null;
            }

            File script = new File(bridgeDir, CHANNEL_SCRIPT);
            if (!script.exists()) {
                LOG.warn("[CliModels] channel-manager.js not found");
                return null;
            }

            List<String> command = new ArrayList<>(NodeDetector.buildNodeScriptCommand(
                    node, script.getAbsolutePath()));
            command.add(provider);
            command.add("listModels");

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(bridgeDir);
            pb.redirectErrorStream(true);
            Map<String, String> env = pb.environment();
            envConfigurator.updateProcessEnvironment(pb, node);
            LOG.info("[CliModels] Listing models for " + provider + " via channel-manager: "
                    + String.join(" ", command));

            Process process = pb.start();
            // Drain stdout on a daemon thread (bounded) so a verbose child cannot
            // deadlock on a full pipe buffer while this thread enforces the timeout.
            StringBuilder output = new StringBuilder();
            Thread readerThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (output) {
                            if (output.length() < MAX_OUTPUT_CHARS) {
                                output.append(line).append('\n');
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            });
            readerThread.setDaemon(true);
            readerThread.start();

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                LOG.warn("[CliModels] Timed out listing " + provider + " models");
                return null;
            }
            // Process exited; the reader hits EOF promptly — join for the final lines.
            readerThread.join(2000L);

            JsonObject payload = extractJsonObject(output.toString());
            if (payload == null) {
                LOG.warn("[CliModels] No model list JSON in " + provider + " listModels output");
                return null;
            }
            if (payload.has("debug") && payload.get("debug").isJsonObject()) {
                // Bridge-side diagnostics (e.g. empty model parse, fallback source)
                LOG.warn("[CliModels] " + provider + " listModels debug: " + payload.get("debug"));
            }
            return payload;
        } catch (Exception e) {
            LOG.warn("[CliModels] Failed for " + provider + ": " + e.getMessage(), e);
            return null;
        }
    }

    // =========================================================================
    // Warmup (called by DaemonStatusHandler once serve is ready)
    // =========================================================================

    /**
     * Fire-and-forget refresh of the cached model catalog. Runs after
     * preconnect succeeds so the first model dropdown open is instant; the
     * fetched payload is pushed to the webview when one is attached.
     */
    public static void warmupModelCache(HandlerContext context) {
        CompletableFuture.runAsync(() -> {
            try {
                CliModelsHandler handler = new CliModelsHandler(context);
                JsonObject refreshed = handler.listModelsViaDaemon("opencode");
                if (refreshed == null) {
                    refreshed = handler.listModelsViaChannelManager("opencode");
                }
                if (refreshed == null) {
                    LOG.info("[CliModels] Warmup produced no model list yet");
                    return;
                }
                handler.ensureProviderField(refreshed, "opencode");
                handler.writeCachedPayload("opencode", refreshed);
                handler.pushPayload(refreshed);
                LOG.info("[CliModels] Model list warmed via daemon");
            } catch (Exception e) {
                LOG.debug("[CliModels] Warmup failed: " + e.getMessage());
            }
        }, AppExecutorUtil.getAppExecutorService());
    }

    // =========================================================================
    // Cache / push helpers
    // =========================================================================

    private PropertiesComponent properties() {
        return context.getProject() != null
                ? PropertiesComponent.getInstance(context.getProject())
                : PropertiesComponent.getInstance();
    }

    private JsonObject readCachedPayload(String provider) {
        try {
            String json = properties().getValue(CACHE_KEY + provider);
            if (json == null || json.isEmpty()) {
                return null;
            }
            JsonObject payload = gson.fromJson(json, JsonObject.class);
            if (!payloadHasModels(payload)) {
                return null;
            }
            // The persisted list may predate this build and lack limit metadata;
            // merging is a no-op then and a real warm-up otherwise.
            ModelContextWindowCatalog.getInstance().updateFromModelsPayload(payload);
            return payload;
        } catch (Exception e) {
            LOG.debug("[CliModels] Ignoring unreadable model cache: " + e.getMessage());
            return null;
        }
    }

    private void writeCachedPayload(String provider, JsonObject payload) {
        try {
            properties().setValue(CACHE_KEY + provider, gson.toJson(payload));
        } catch (Exception e) {
            LOG.debug("[CliModels] Failed to persist model cache: " + e.getMessage());
        }
    }

    private void pushPayload(JsonObject payload) {
        // Teach the context-window catalog before the webview sees the list, so
        // the very next usage push resolves the real model limit.
        boolean catalogChanged = ModelContextWindowCatalog.getInstance().updateFromModelsPayload(payload);
        callJavaScript("window.setCliModels", escapeJs(gson.toJson(payload)));
        republishUsageIfLimitChanged(catalogChanged);
    }

    /**
     * Re-send the retained usage snapshot once the catalog learned a model's real
     * context window.
     *
     * <p>Session restore and webview recovery can resolve a limit while the model
     * catalog is still cold, which publishes the hardcoded 200k fallback. When the
     * list (fresh or cached) arrives moments later, the already-rendered usage ring
     * would stay wrong until the next turn — so republish when the catalog moved.
     * A session with no usage snapshot yet pushes nothing and stays untouched.</p>
     */
    private void republishUsageIfLimitChanged(boolean catalogChanged) {
        if (!catalogChanged) {
            return;
        }
        try {
            OpencodeSession session = context.getSession();
            if (session == null) {
                return;
            }
            int limit = ModelProviderHandler.getModelContextLimit(session.getProvider(), session.getModel());
            new UsagePushService(context).pushCurrentUsageIfAvailable(limit);
        } catch (Exception e) {
            LOG.debug("[CliModels] Usage republish skipped: " + e.getMessage());
        }
    }

    private JsonObject extractJsonObject(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        // Prefer last JSON object line (channel-manager may print diagnostics to stdout).
        String[] lines = raw.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (!line.startsWith("{") || !line.endsWith("}")) {
                continue;
            }
            try {
                JsonObject obj = gson.fromJson(line, JsonObject.class);
                if (obj != null && (obj.has("models") || obj.has("success"))) {
                    return obj;
                }
            } catch (Exception ignored) {
            }
        }
        // Fallback: whole buffer
        try {
            int start = raw.lastIndexOf('{');
            int end = raw.lastIndexOf('}');
            if (start >= 0 && end > start) {
                return gson.fromJson(raw.substring(start, end + 1), JsonObject.class);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void pushError(String provider, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("success", false);
        error.addProperty("provider", provider != null ? provider : "");
        error.addProperty("error", message != null ? message : "unknown error");
        error.add("models", gson.toJsonTree(new ArrayList<>()));
        callJavaScript("window.setCliModels", escapeJs(gson.toJson(error)));
    }
}
