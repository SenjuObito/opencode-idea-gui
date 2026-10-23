package com.opencodebuddy.handler;

import com.opencodebuddy.cli.CliStatusDetector;
import com.opencodebuddy.cli.CliToolStatus;
import com.opencodebuddy.handler.core.BaseMessageHandler;
import com.opencodebuddy.handler.core.HandlerContext;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Frontend bridge for CLI install/version detection (Settings → CLI tab).
 *
 * <p>Messages:
 * <ul>
 *   <li>{@code get_cli_status:} — probe all known CLIs, push {@code window.updateCliStatus}</li>
 * </ul>
 *
 * <p>Does <strong>not</strong> install CLIs for the user; the UI only shows
 * install instructions when a tool is missing.
 */
public class CliStatusHandler extends BaseMessageHandler {

    private static final Logger LOG = Logger.getInstance(CliStatusHandler.class);

    private static final String[] SUPPORTED_TYPES = {
            "get_cli_status",
    };

    private final Gson gson = new Gson();

    public CliStatusHandler(HandlerContext context) {
        super(context);
    }

    @Override
    public String[] getSupportedTypes() {
        return SUPPORTED_TYPES.clone();
    }

    @Override
    public boolean handle(String type, String content) {
        if (!"get_cli_status".equals(type)) {
            return false;
        }
        handleGetStatus();
        return true;
    }

    private void handleGetStatus() {
        CompletableFuture.runAsync(() -> {
            try {
                Map<String, CliToolStatus> statuses = overlayEngineStatus(CliStatusDetector.detectAll());
                JsonObject payload = new JsonObject();
                for (Map.Entry<String, CliToolStatus> entry : statuses.entrySet()) {
                    payload.add(entry.getKey(), toJson(entry.getValue()));
                }
                String json = gson.toJson(payload);
                ApplicationManager.getApplication().invokeLater(() ->
                        context.publishEvent(com.opencodebuddy.tab.TabEvent.of("cli.status").field("data", json).build())
                );
            } catch (Exception e) {
                LOG.error("[CliStatusHandler] Failed to detect CLI status: " + e.getMessage(), e);
                JsonObject error = new JsonObject();
                error.addProperty("error", e.getMessage() != null ? e.getMessage() : "unknown error");
                String json = gson.toJson(error);
                ApplicationManager.getApplication().invokeLater(() ->
                        context.publishEvent(com.opencodebuddy.tab.TabEvent.of("cli.status").field("data", json).build())
                );
            }
        }, AppExecutorUtil.getAppExecutorService()).exceptionally(ex -> {
            LOG.error("[CliStatusHandler] Unexpected error: " + ex.getMessage(), ex);
            return null;
        });
    }

    /**
     * Show the path/version the daemon actually resolved for opencode, so the
     * settings page agrees with what is really being run (the local detector uses
     * a separate candidate list and does not read the user's configured path).
     * A no-op when the daemon has no resolution yet.
     */
    private Map<String, CliToolStatus> overlayEngineStatus(Map<String, CliToolStatus> statuses) {
        try {
            com.opencodebuddy.provider.opencode.OpenCodeSDKBridge bridge = context.getOpenCodeSDKBridge();
            if (bridge == null || statuses == null) {
                return statuses;
            }
            JsonObject engine = bridge.getEngineStatus();
            if (engine == null || !engine.has("cli") || !engine.get("cli").isJsonObject()) {
                return statuses;
            }
            JsonObject cli = engine.getAsJsonObject("cli");
            if (!cli.has("path") || cli.get("path").isJsonNull()) {
                return statuses;
            }
            String path = cli.get("path").getAsString();
            if (path == null || path.trim().isEmpty()) {
                return statuses;
            }
            String version = (cli.has("version") && !cli.get("version").isJsonNull())
                    ? cli.get("version").getAsString() : null;
            Map<String, CliToolStatus> merged = new java.util.LinkedHashMap<>(statuses);
            merged.put(
                    com.opencodebuddy.cli.CliToolId.OPENCODE.getId(),
                    CliToolStatus.installed(com.opencodebuddy.cli.CliToolId.OPENCODE, version, path));
            return merged;
        } catch (Exception e) {
            LOG.debug("[CliStatusHandler] Could not overlay daemon engine status: " + e.getMessage());
            return statuses;
        }
    }

    private JsonObject toJson(CliToolStatus status) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", status.getId());
        obj.addProperty("name", status.getName());
        obj.addProperty("binaryName", status.getBinaryName());
        obj.addProperty("installed", status.isInstalled());
        if (status.getVersion() != null) {
            obj.addProperty("version", status.getVersion());
        }
        if (status.getPath() != null) {
            obj.addProperty("path", status.getPath());
        }
        if (status.getError() != null) {
            obj.addProperty("error", status.getError());
        }
        return obj;
    }
}
