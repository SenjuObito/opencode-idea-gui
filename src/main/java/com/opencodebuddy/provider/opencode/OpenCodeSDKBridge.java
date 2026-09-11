package com.opencodebuddy.provider.opencode;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.opencodebuddy.provider.common.DaemonBridge;
import com.opencodebuddy.provider.common.MessageCallback;
import com.opencodebuddy.provider.common.SDKResult;
import com.opencodebuddy.session.ClaudeSession;
import com.opencodebuddy.utils.PluginFileLogger;
import com.intellij.openapi.diagnostic.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * OpenCode bridge backed by the persistent ai-bridge daemon.
 *
 * <p>Chain: Java → daemon (NDJSON over stdin/stdout) → {@code opencode serve}
 * (HTTP via @opencode-ai/sdk) + SSE event subscription. The daemon normalizes
 * SDK/SSE events into marker lines; {@link OpenCodeMarkerParser} adapts those
 * into {@link MessageCallback} events.</p>
 *
 * <p>Daemon methods (see ai-bridge daemon.js): {@code opencode.send},
 * {@code opencode.shell}, {@code opencode.preconnect},
 * {@code opencode.getContextUsage}, {@code opencode.listModels} and any
 * {@code opencode.<command>} dispatched to channels/opencode-channel.js
 * (listMessages, getSessionInfo, findFiles, listAgents, listCommands,
 * listMcpServers, getMcpStatus, replyPermission, replyQuestion,
 * rejectQuestion, summarize, shareSession, unshareSession, revert, unrevert,
 * fork).</p>
 */
public class OpenCodeSDKBridge {

    public static final String PROVIDER_ID = "opencode";

    private static final Logger LOG = Logger.getInstance(OpenCodeSDKBridge.class);

    private final DaemonBridge daemon;
    private final Gson gson;
    private final OpenCodeMarkerParser markerParser;

    public OpenCodeSDKBridge(DaemonBridge daemon, Gson gson) {
        this.daemon = daemon;
        this.gson = gson;
        this.markerParser = new OpenCodeMarkerParser(gson);
    }

    // =========================================================================
    // Daemon lifecycle
    // =========================================================================

    public boolean ensureDaemonRunning() {
        return daemon.ensureRunning();
    }

    public boolean isDaemonAlive() {
        return daemon.isAlive();
    }

    public void stopDaemon() {
        daemon.stop();
    }

    /**
     * Abort the current turn. Bypasses the daemon's command queue and completes
     * all in-flight request futures so Java-side callers unblock.
     */
    public void abort() {
        daemon.sendAbort();
    }

    public void addDaemonEventListener(DaemonBridge.DaemonEventListener listener) {
        daemon.addEventListener(listener);
    }

    public void removeDaemonEventListener(DaemonBridge.DaemonEventListener listener) {
        daemon.removeEventListener(listener);
    }

    /**
     * Install the daemon lifecycle listener (ready/died). The bridge hosts a
     * single slot; the chat window's status pusher owns it.
     */
    public void setDaemonLifecycleListener(DaemonBridge.DaemonLifecycleListener listener) {
        daemon.setLifecycleListener(listener);
    }

    // =========================================================================
    // Generic request helpers
    // =========================================================================

    /**
     * Send a daemon request, streaming each output line to the callback.
     * The returned future completes when the daemon signals done for the request.
     */
    public CompletableFuture<Boolean> request(
            String method, JsonObject params, DaemonBridge.DaemonOutputCallback callback) {
        return daemon.sendCommand(method, params, callback);
    }

    /**
     * Convenience for request/response style commands: collects every output
     * line and parses the last JSON line as the result payload.
     */
    public CompletableFuture<JsonElement> requestJson(String method, JsonObject params) {
        List<String> lines = new ArrayList<>();
        return request(method, params, new DaemonBridge.DaemonOutputCallback() {
            @Override
            public void onLine(String line) {
                lines.add(line);
            }

            @Override
            public void onStderr(String text) {
                LOG.debug("[OpenCode] stderr: " + text);
            }

            @Override
            public void onError(String error) {
                // Completion with success=false carries the error; keep collecting.
            }

            @Override
            public void onComplete(boolean success) {
            }
        }).thenApply(success -> parseLastJson(lines, success, method));
    }

    private JsonElement parseLastJson(List<String> lines, boolean success, String method) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            String trimmed = lines.get(i).trim();
            if (trimmed.isEmpty() || trimmed.charAt(0) != '{' && trimmed.charAt(0) != '[') {
                continue;
            }
            try {
                return JsonParser.parseString(trimmed);
            } catch (Exception ignored) {
            }
        }
        JsonObject fallback = new JsonObject();
        fallback.addProperty("success", success);
        return fallback;
    }

    // =========================================================================
    // Streaming send
    // =========================================================================

    /**
     * Send a user message (or slash command with {@code command}) through the
     * daemon's persistent opencode turn. Streams markers to the callback.
     *
     * @param model "provider/model" id or "" for the opencode-configured default
     * @param agent opencode agent id (build/plan) or null
     */
    public CompletableFuture<SDKResult> sendMessage(
            String message,
            String sessionId,
            String cwd,
            String model,
            String reasoningEffort,
            List<ClaudeSession.Attachment> attachments,
            String permissionMode,
            String agent,
            String command,
            String commandArguments,
            MessageCallback callback
    ) {
        JsonObject params = new JsonObject();
        params.addProperty("message", message != null ? message : "");
        params.addProperty("sessionId", sessionId != null ? sessionId : "");
        params.addProperty("cwd", cwd != null ? cwd : "");
        params.addProperty("model", model != null ? model : "");
        params.addProperty("reasoningEffort", reasoningEffort != null ? reasoningEffort : "");
        String mode = permissionMode != null && !permissionMode.isBlank() ? permissionMode.trim() : "default";
        params.addProperty("permissionMode", mode);
        params.addProperty("mode", mode);
        if (agent != null && !agent.isBlank()) {
            params.addProperty("agent", agent);
        }
        if (command != null && !command.isBlank()) {
            // Slash commands go through /session/{id}/command; the message text
            // is ignored by the daemon in that case.
            params.addProperty("command", command);
            params.addProperty("commandArguments", commandArguments != null ? commandArguments : "");
        }
        if (attachments != null && !attachments.isEmpty()) {
            params.add("attachments", buildAttachmentArray(attachments));
        }

        LOG.info("[OpenCode] send sessionId="
                + (sessionId != null && !sessionId.isEmpty() ? sessionId : "(new)")
                + " model=" + (model != null && !model.isEmpty() ? model : "(default)")
                + " agent=" + (agent != null ? agent : "(default)")
                + " command=" + (command != null ? command : "-")
                + " permissionMode=" + mode
                + " attachments=" + (attachments != null ? attachments.size() : 0));

        SDKResult result = new SDKResult();
        OpenCodeMarkerParser.StreamContext context = new OpenCodeMarkerParser.StreamContext();
        return daemon.sendCommand("opencode.send", params, new DaemonBridge.DaemonOutputCallback() {
            @Override
            public void onLine(String line) {
                markerParser.processOutputLine(line, callback, result, context);
            }

            @Override
            public void onStderr(String text) {
                callback.onMessage("node_log", text);
            }

            @Override
            public void onError(String error) {
                if (!context.hadSendError.get()) {
                    result.success = false;
                    result.error = error;
                    callback.onError(error);
                }
            }

            @Override
            public void onComplete(boolean success) {
                result.success = success && !context.hadSendError.get();
                result.messageCount = result.messages.size();
                callback.onComplete(result);
            }
        }).thenApply(v -> result);
    }

    /**
     * Run a {@code !}-style shell command inside the current opencode session.
     */
    public CompletableFuture<SDKResult> shell(String sessionId, String cwd, String command, MessageCallback callback) {
        return shell(sessionId, cwd, command, null, null, callback);
    }

    /**
     * Run a {@code !}-style shell command inside the current opencode session with explicit model/agent.
     */
    public CompletableFuture<SDKResult> shell(
            String sessionId,
            String cwd,
            String command,
            String model,
            String agent,
            MessageCallback callback
    ) {
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId != null ? sessionId : "");
        params.addProperty("cwd", cwd != null ? cwd : "");
        params.addProperty("command", command != null ? command : "");
        if (model != null && !model.isEmpty()) {
            params.addProperty("model", model);
        }
        if (agent != null && !agent.isEmpty()) {
            params.addProperty("agent", agent);
        }

        SDKResult result = new SDKResult();
        OpenCodeMarkerParser.StreamContext context = new OpenCodeMarkerParser.StreamContext();
        return daemon.sendCommand("opencode.shell", params, new DaemonBridge.DaemonOutputCallback() {
            @Override
            public void onLine(String line) {
                markerParser.processOutputLine(line, callback, result, context);
            }

            @Override
            public void onStderr(String text) {
                callback.onMessage("node_log", text);
            }

            @Override
            public void onError(String error) {
                if (!context.hadSendError.get()) {
                    result.success = false;
                    result.error = error;
                    callback.onError(error);
                }
            }

            @Override
            public void onComplete(boolean success) {
                result.success = success && !context.hadSendError.get();
                result.messageCount = result.messages.size();
                callback.onComplete(result);
            }
        }).thenApply(v -> result);
    }

    public CompletableFuture<Boolean> shell(String sessionId, String cwd, String command) {
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId != null ? sessionId : "");
        params.addProperty("cwd", cwd != null ? cwd : "");
        params.addProperty("command", command != null ? command : "");
        return request("opencode.shell", params, noopCallback());
    }

    /**
     * Warm up the daemon: start/attach {@code opencode serve} plus the
     * directory-scoped SSE subscription, and resolve/create the session.
     */
    public CompletableFuture<Boolean> preconnect(String sessionId, String cwd) {
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId != null ? sessionId : "");
        params.addProperty("cwd", cwd != null ? cwd : "");
        PluginFileLogger.info("DAEMON", "preconnect request: cwd=" + (cwd != null ? cwd : "")
                + ", sessionId=" + (sessionId != null ? sessionId : ""));
        CompletableFuture<Boolean> future = request("opencode.preconnect", params, noopCallback());
        future.whenComplete((ok, err) -> PluginFileLogger.info("DAEMON",
                "preconnect result=" + ok + (err != null ? ", error=" + err.getMessage() : "")));
        return future;
    }

    // =========================================================================
    // Query helpers used by handlers
    // =========================================================================

    public CompletableFuture<JsonElement> listModels() {
        return requestJson("opencode.listModels", new JsonObject());
    }

    public CompletableFuture<JsonElement> listMessages(String sessionId, String cwd) {
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId != null ? sessionId : "");
        params.addProperty("directory", cwd != null ? cwd : "");
        JsonArray entries = new JsonArray();
        return request("opencode.listMessages", params, new DaemonBridge.DaemonOutputCallback() {
            @Override
            public void onLine(String line) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.charAt(0) != '{') {
                    return;
                }
                try {
                    JsonObject obj = JsonParser.parseString(trimmed).getAsJsonObject();
                    if (obj.has("messageEntry") && obj.get("messageEntry").isJsonObject()) {
                        entries.add(obj.getAsJsonObject("messageEntry"));
                    } else if (obj.has("messages") && obj.get("messages").isJsonArray()) {
                        entries.addAll(obj.getAsJsonArray("messages"));
                    }
                } catch (Exception ignored) {
                }
            }

            @Override
            public void onStderr(String text) {
                LOG.debug("[OpenCode] stderr: " + text);
            }

            @Override
            public void onError(String error) {
            }

            @Override
            public void onComplete(boolean success) {
            }
        }).thenApply(success -> entries);
    }

    public CompletableFuture<JsonElement> getSessionInfo(String sessionId, String cwd) {
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId != null ? sessionId : "");
        params.addProperty("directory", cwd != null ? cwd : "");
        return requestJson("opencode.getSessionInfo", params);
    }

    public CompletableFuture<JsonElement> findFiles(String query, String cwd) {
        JsonObject params = new JsonObject();
        params.addProperty("query", query != null ? query : "");
        params.addProperty("directory", cwd != null ? cwd : "");
        return requestJson("opencode.findFiles", params);
    }

    public CompletableFuture<JsonElement> listAgents(String cwd) {
        JsonObject params = new JsonObject();
        params.addProperty("directory", cwd != null ? cwd : "");
        return requestJson("opencode.listAgents", params);
    }

    public CompletableFuture<JsonElement> listCommands(String cwd) {
        JsonObject params = new JsonObject();
        params.addProperty("directory", cwd != null ? cwd : "");
        return requestJson("opencode.listCommands", params);
    }

    public CompletableFuture<JsonElement> listMcpServers(String cwd) {
        JsonObject params = new JsonObject();
        params.addProperty("directory", cwd != null ? cwd : "");
        return requestJson("opencode.listMcpServers", params);
    }

    public CompletableFuture<JsonElement> getMcpStatus(String cwd) {
        JsonObject params = new JsonObject();
        params.addProperty("directory", cwd != null ? cwd : "");
        return requestJson("opencode.getMcpStatus", params);
    }

    public CompletableFuture<JsonElement> listSessions(String cwd, int limit) {
        JsonObject params = new JsonObject();
        params.addProperty("directory", cwd != null ? cwd : "");
        if (limit > 0) {
            params.addProperty("limit", limit);
        }
        return requestJson("opencode.listSessions", params);
    }

    public CompletableFuture<JsonElement> deleteSession(String sessionId, String cwd) {
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId != null ? sessionId : "");
        params.addProperty("directory", cwd != null ? cwd : "");
        return requestJson("opencode.deleteSession", params);
    }

    public CompletableFuture<JsonElement> updateSessionTitle(String sessionId, String title, String cwd) {
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId != null ? sessionId : "");
        params.addProperty("title", title != null ? title : "");
        params.addProperty("directory", cwd != null ? cwd : "");
        return requestJson("opencode.updateSessionTitle", params);
    }

    public CompletableFuture<JsonElement> toggleFavorite(String sessionId, Boolean isFavorited, String cwd) {
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId != null ? sessionId : "");
        if (isFavorited != null) {
            params.addProperty("isFavorited", isFavorited);
        }
        params.addProperty("directory", cwd != null ? cwd : "");
        return requestJson("opencode.toggleFavorite", params);
    }

    public CompletableFuture<Boolean> replyPermission(String sessionId, String requestId, String reply, String message, String directory) {
        JsonObject params = new JsonObject();
        // Daemon channel protocol (opencode-channel.js 'replyPermission'):
        // { sessionId, permissionID, reply: allow|allowAlways|deny, rejectMessage }
        // — the channel maps this vocabulary to the SDK's once/always/reject
        // and looks up the workspace directory from the session registry.
        if (sessionId != null && !sessionId.isBlank()) {
            params.addProperty("sessionId", sessionId);
        }
        if (directory != null && !directory.isBlank()) {
            params.addProperty("directory", directory);
        }
        params.addProperty("permissionID", requestId != null ? requestId : "");
        params.addProperty("reply", reply != null ? reply : "allow");
        if (message != null && !message.isBlank()) {
            params.addProperty("rejectMessage", message);
        }
        return request("opencode.replyPermission", params, noopCallback());
    }

    public CompletableFuture<Boolean> replyQuestion(String requestId, JsonArray answers) {
        JsonObject params = new JsonObject();
        params.addProperty("requestID", requestId != null ? requestId : "");
        if (answers != null) {
            params.add("answers", answers);
        }
        return request("opencode.replyQuestion", params, noopCallback());
    }

    public CompletableFuture<Boolean> rejectQuestion(String requestId) {
        JsonObject params = new JsonObject();
        params.addProperty("requestID", requestId != null ? requestId : "");
        return request("opencode.rejectQuestion", params, noopCallback());
    }

    public CompletableFuture<Boolean> summarize(String sessionId, String cwd, String providerId, String modelId) {
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId != null ? sessionId : "");
        params.addProperty("directory", cwd != null ? cwd : "");
        params.addProperty("providerID", providerId != null ? providerId : "");
        params.addProperty("modelID", modelId != null ? modelId : "");
        return request("opencode.summarize", params, noopCallback());
    }

    public CompletableFuture<Boolean> revert(String sessionId, String cwd, String messageID) {
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId != null ? sessionId : "");
        params.addProperty("directory", cwd != null ? cwd : "");
        if (messageID != null) {
            params.addProperty("messageID", messageID);
        }
        return request("opencode.revert", params, noopCallback());
    }

    public CompletableFuture<Boolean> unrevert(String sessionId, String cwd) {
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId != null ? sessionId : "");
        params.addProperty("directory", cwd != null ? cwd : "");
        return request("opencode.unrevert", params, noopCallback());
    }

    public CompletableFuture<Boolean> fork(String sessionId, String cwd, String messageID) {
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId != null ? sessionId : "");
        params.addProperty("directory", cwd != null ? cwd : "");
        if (messageID != null) {
            params.addProperty("messageID", messageID);
        }
        return request("opencode.fork", params, noopCallback());
    }

    public CompletableFuture<JsonElement> getContextUsage(String sessionId, String cwd) {
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId != null ? sessionId : "");
        params.addProperty("directory", cwd != null ? cwd : "");
        return requestJson("opencode.getContextUsage", params);
    }

    private JsonArray buildAttachmentArray(List<ClaudeSession.Attachment> attachments) {
        JsonArray arr = new JsonArray();
        for (ClaudeSession.Attachment attachment : attachments) {
            if (attachment == null) {
                continue;
            }
            JsonObject o = new JsonObject();
            o.addProperty("fileName", attachment.fileName != null ? attachment.fileName : "");
            o.addProperty("mediaType", attachment.mediaType != null ? attachment.mediaType : "");
            o.addProperty("data", attachment.data != null ? attachment.data : "");
            arr.add(o);
        }
        return arr;
    }

    private static DaemonBridge.DaemonOutputCallback noopCallback() {
        return new DaemonBridge.DaemonOutputCallback() {
            @Override
            public void onLine(String line) {
            }

            @Override
            public void onStderr(String text) {
            }

            @Override
            public void onError(String error) {
            }

            @Override
            public void onComplete(boolean success) {
            }
        };
    }
}
