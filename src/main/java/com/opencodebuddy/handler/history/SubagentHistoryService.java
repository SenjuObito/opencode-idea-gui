package com.opencodebuddy.handler.history;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.opencodebuddy.handler.core.HandlerContext;
import com.opencodebuddy.session.OpencodeSession;
import com.opencodebuddy.tab.TabEvent;

/**
 * Forwards a subagent transcript request to core and publishes the result.
 *
 * <p>opencode spawns a child session for the {@code subagent}/{@code task} tool.
 * Resolving that child, reading its transcript, and deciding whether it is still
 * running is engine knowledge: core owns it and answers with one canonical
 * {@code opencode.getSubagentHistory} response. This class only adds the
 * request-scoped identity the webview routes on ({@code agentId},
 * {@code agentPath}) and publishes {@code subagent.history}.</p>
 */
final class SubagentHistoryService {

    private static final Logger LOG = Logger.getInstance(SubagentHistoryService.class);
    private static final String[] ROUTING_FIELDS = {"agentId", "agentPath", "toolUseId"};

    private final HandlerContext context;
    private final Gson gson = new Gson();

    SubagentHistoryService(HandlerContext context) {
        this.context = context;
    }

    void handle(String content) {
        JsonObject request = parsePayload(content);
        String parentSessionId = string(request, "sessionId");
        String toolUseId = string(request, "toolUseId");
        if (parentSessionId.isEmpty() || toolUseId.isEmpty()) {
            return;
        }
        boolean poll = request.has("poll") && request.get("poll").isJsonPrimitive()
                && request.get("poll").getAsJsonPrimitive().isBoolean()
                && request.get("poll").getAsBoolean();
        OpencodeSession session = context.getSession(parentSessionId);
        String cwd = session != null && session.getCwd() != null
                ? session.getCwd() : context.resolveEffectiveWorkingDirectory();

        context.getOpenCodeSDKBridge()
                .getSubagentHistory(
                        parentSessionId,
                        toolUseId,
                        string(request, "description"),
                        cwd,
                        poll)
                .whenComplete((payload, error) ->
                        publish(request, parentSessionId, payload, error));
    }

    private void publish(
            JsonObject request,
            String parentSessionId,
            JsonElement payload,
            Throwable error
    ) {
        JsonObject response = canonicalResponse(request, parentSessionId, payload, error);
        context.publishEvent(TabEvent.of("subagent.history")
                .tab(context.getTabIdForSession(parentSessionId))
                .session(parentSessionId)
                .json("history", gson.toJson(response))
                .build());
    }

    /**
     * Turn the core envelope into the {@code subagent.history} payload.
     *
     * <p>Package-private so the forwarding contract can be tested without a
     * live IntelliJ project: this method must never infer a terminal state, only
     * carry core's answer (or a recoverable failure).</p>
     */
    static JsonObject canonicalResponse(
            JsonObject request,
            String parentSessionId,
            JsonElement payload,
            Throwable error
    ) {
        JsonObject history = error == null ? unwrapHistory(payload) : null;
        JsonObject response = history != null
                ? history
                : failureResponse(request, parentSessionId, error);

        // Request-scoped identity is not engine state: core does not know which
        // UI row asked, but the webview needs it to merge the transcript.
        for (String field : ROUTING_FIELDS) {
            String value = string(request, field);
            if (!value.isEmpty() && !response.has(field)) {
                response.addProperty(field, value);
            }
        }
        response.addProperty("sessionId", parentSessionId);
        if (string(response, "provider").isEmpty()) {
            String provider = string(request, "provider");
            response.addProperty("provider", provider.isEmpty()
                    ? HandlerContext.DEFAULT_PROVIDER : provider);
        }
        return response;
    }

    /**
     * core answers {@code { success, provider, sessionId, history }}. Anything
     * else is a broken route, which must not be mistaken for a finished child.
     */
    private static JsonObject unwrapHistory(JsonElement payload) {
        if (payload == null || !payload.isJsonObject()) {
            return null;
        }
        JsonElement history = payload.getAsJsonObject().get("history");
        return history != null && history.isJsonObject() ? history.getAsJsonObject() : null;
    }

    private static JsonObject failureResponse(
            JsonObject request,
            String parentSessionId,
            Throwable error
    ) {
        JsonObject response = new JsonObject();
        // A transcript request failure is not evidence that the child finished.
        // Keep the card running so the next poll can recover; otherwise a
        // transient bridge error permanently marks a still running subagent as
        // completed.
        response.addProperty("success", false);
        response.addProperty("status", "running");
        response.addProperty("completed", false);
        response.addProperty("sessionId", parentSessionId);
        response.addProperty("error", error != null && error.getMessage() != null
                ? error.getMessage() : "Failed to load subagent transcript");
        response.add("messages", new JsonArray());
        LOG.debug("[SubagentHistory] request failed for " + string(request, "toolUseId"), error);
        return response;
    }

    private static JsonObject parsePayload(String content) {
        if (content == null || content.isBlank()) {
            return new JsonObject();
        }
        try {
            JsonElement parsed = JsonParser.parseString(content);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException ignored) {
            return new JsonObject();
        }
    }

    private static String string(JsonObject object, String field) {
        if (object == null || !object.has(field) || object.get(field).isJsonNull()) {
            return "";
        }
        JsonElement value = object.get(field);
        return value.isJsonPrimitive() ? value.getAsString() : "";
    }
}
