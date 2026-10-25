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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads a subagent transcript from opencode.
 *
 * <p>opencode spawns a child session for the {@code subagent}/{@code task} tool.
 * The parent transcript points at it in the tool part metadata, so this service
 * reads the parent once, resolves the child, then reads the child's messages.
 * No extra state is kept here: every poll re-derives the child from the
 * authoritative transcript, which keeps a background tab correct after a
 * reload.</p>
 */
final class SubagentHistoryService {

    private static final Logger LOG = Logger.getInstance(SubagentHistoryService.class);
    private static final int POLL_TAIL_MESSAGES = 200;
    private static final Pattern SUBAGENT_SESSION =
            Pattern.compile("<subagent\\b[^>]*\\bsessionID\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    private final HandlerContext context;
    private final Gson gson = new Gson();

    SubagentHistoryService(HandlerContext context) {
        this.context = context;
    }

    void handle(String content) {
        JsonObject request = parsePayload(content);
        String parentSessionId = string(request, "sessionId");
        String toolUseId = string(request, "toolUseId");
        String provider = string(request, "provider");
        if (parentSessionId.isEmpty() || toolUseId.isEmpty()) {
            return;
        }
        boolean poll = request.has("poll") && request.get("poll").isJsonPrimitive()
                && request.get("poll").getAsJsonPrimitive().isBoolean()
                && request.get("poll").getAsBoolean();
        OpencodeSession session = context.getSession(parentSessionId);
        String cwd = session != null && session.getCwd() != null
                ? session.getCwd() : context.resolveEffectiveWorkingDirectory();

        context.getOpenCodeSDKBridge().listMessages(parentSessionId, cwd)
                .thenCompose(parentEntries -> resolveChild(parentSessionId, toolUseId, request, parentEntries, cwd, poll))
                .whenComplete((history, error) -> publish(request, history, error));
    }

    private java.util.concurrent.CompletableFuture<JsonObject> resolveChild(
            String parentSessionId,
            String toolUseId,
            JsonObject request,
            JsonElement parentEntries,
            String cwd,
            boolean poll
    ) {
        FoundTool tool = findTool(asArray(parentEntries), toolUseId);
        JsonObject base = baseResponse(request, parentSessionId);

        if (tool == null) {
            // The parent transcript has not flushed the tool part yet. Report a
            // running, empty transcript so the webview keeps polling instead of
            // showing a permanent error.
            return java.util.concurrent.CompletableFuture.completedFuture(
                    running(base, "Subagent is still starting"));
        }

        String childSessionId = tool.childSessionId;
        if (childSessionId == null || childSessionId.isBlank()) {
            return context.getOpenCodeSDKBridge().listChildSessions(parentSessionId, cwd)
                    .thenCompose(sessions -> {
                        String resolved = childFromList(sessions, string(request, "description"));
                        if (resolved == null) {
                            return java.util.concurrent.CompletableFuture.completedFuture(
                                    running(base, "Subagent session is not available yet"));
                        }
                        return loadChild(base, tool, resolved, cwd, poll);
                    });
        }
        return loadChild(base, tool, childSessionId, cwd, poll);
    }

    private java.util.concurrent.CompletableFuture<JsonObject> loadChild(
            JsonObject base,
            FoundTool tool,
            String childSessionId,
            String cwd,
            boolean poll
    ) {
        return context.getOpenCodeSDKBridge().listMessages(
                        childSessionId, cwd, poll ? POLL_TAIL_MESSAGES : 0)
                .thenApply(childEntries -> {
                    JsonObject response = base.deepCopy();
                    JsonArray messages = convertEntries(childEntries);
                    String status = statusFromEntries(childEntries);
                    response.addProperty("success", true);
                    response.add("messages", messages);
                    response.addProperty("status", status);
                    response.addProperty("completed", !"running".equals(status));
                    if (tool.output != null && !tool.output.isBlank()) {
                        response.addProperty("resultText", tool.output);
                    }
                    return response;
                });
    }

    private JsonObject running(JsonObject base, String reason) {
        JsonObject response = base.deepCopy();
        response.addProperty("success", true);
        response.addProperty("status", "running");
        response.addProperty("completed", false);
        response.add("messages", new JsonArray());
        LOG.debug("[SubagentHistory] " + reason + " for " + string(base, "toolUseId"));
        return response;
    }

    private void publish(JsonObject request, JsonObject history, Throwable error) {
        JsonObject response;
        if (error != null || history == null) {
            response = baseResponse(request, string(request, "sessionId"));
            response.addProperty("success", false);
            // A transcript request failure is not evidence that the child
            // finished. Keep the card running so the next poll can recover;
            // otherwise a transient bridge error permanently marks a still
            // running subagent as completed.
            response.addProperty("status", "running");
            response.addProperty("completed", false);
            response.addProperty("error", error != null && error.getMessage() != null
                    ? error.getMessage() : "Failed to load subagent transcript");
            response.add("messages", new JsonArray());
        } else {
            response = history;
        }

        String parentSessionId = string(request, "sessionId");
        context.publishEvent(TabEvent.of("subagent.history")
                .tab(context.getTabIdForSession(parentSessionId))
                .session(parentSessionId)
                .json("history", gson.toJson(response))
                .build());
    }

    private JsonObject baseResponse(JsonObject request, String parentSessionId) {
        JsonObject base = new JsonObject();
        base.addProperty("toolUseId", string(request, "toolUseId"));
        base.addProperty("agentId", string(request, "agentId"));
        base.addProperty("agentPath", string(request, "agentPath"));
        base.addProperty("sessionId", parentSessionId);
        base.addProperty("provider", string(request, "provider").isEmpty()
                ? HandlerContext.DEFAULT_PROVIDER : string(request, "provider"));
        return base;
    }

    // ── parent transcript ────────────────────────────────────────────────

    static FoundTool findTool(JsonArray entries, String toolUseId) {
        if (entries == null || toolUseId == null || toolUseId.isEmpty()) {
            return null;
        }
        for (JsonElement entryElement : entries) {
            if (!entryElement.isJsonObject()) continue;
            JsonArray parts = asArray(entryElement.getAsJsonObject().get("parts"));
            for (JsonElement partElement : parts) {
                if (!partElement.isJsonObject()) continue;
                JsonObject part = partElement.getAsJsonObject();
                String id = first(string(part, "id"), string(part, "callID"), string(part, "callId"));
                if (!toolUseId.equals(id)) continue;

                JsonObject state = part.has("state") && part.get("state").isJsonObject()
                        ? part.getAsJsonObject("state") : new JsonObject();
                String childSessionId = childFromMetadata(state);
                String output = first(string(state, "output"), flattenContent(state.get("content")));
                if (childSessionId == null && output != null) {
                    childSessionId = childFromOutput(output);
                }
                String status = statusOf(state, part, output);
                return new FoundTool(childSessionId, status, output);
            }
        }
        return null;
    }

    private static String childFromMetadata(JsonObject state) {
        JsonObject metadata = state.has("metadata") && state.get("metadata").isJsonObject()
                ? state.getAsJsonObject("metadata") : null;
        if (metadata == null) return null;
        return first(string(metadata, "sessionID"), string(metadata, "sessionId"));
    }

    private static String childFromOutput(String output) {
        Matcher matcher = SUBAGENT_SESSION.matcher(output);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String statusOf(JsonObject state, JsonObject part, String output) {
        String status = first(string(state, "status"), string(part, "status")).toLowerCase();
        if (state.has("error") && !state.get("error").isJsonNull()) {
            return "error";
        }
        if (status.equals("error") || status.equals("failed")) {
            return "error";
        }
        if (status.equals("completed") || status.equals("complete")) {
            return "completed";
        }
        // Older opencode builds only encode the terminal state in the
        // `<subagent state="completed">` wrapper.
        if (output != null) {
            Matcher matcher = Pattern.compile("<subagent\\b[^>]*\\bstate\\s*=\\s*\"(completed|error|failed)\"",
                    Pattern.CASE_INSENSITIVE).matcher(output);
            if (matcher.find()) {
                String value = matcher.group(1).toLowerCase();
                return value.equals("completed") ? "completed" : "error";
            }
        }
        return "running";
    }

    /**
     * The child transcript is authoritative. A background subagent's parent
     * tool part reaches {@code completed} when the launch is acknowledged, so
     * only the child's last assistant finish/error can prove that it actually
     * ended.
     */
    static String statusFromEntries(JsonElement entries) {
        // The child transcript is authoritative for a background subagent.
        // The parent tool's `state.status` becomes `completed` when the launch
        // is acknowledged, while the child session can keep running for
        // minutes. Never let that launch ack override a running child.
        JsonArray list = asArray(entries);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (!list.get(i).isJsonObject()) continue;
            JsonObject entry = list.get(i).getAsJsonObject();
            JsonObject info = entry.has("info") && entry.get("info").isJsonObject()
                    ? entry.getAsJsonObject("info") : null;
            if (info == null || !"assistant".equals(string(info, "role"))) continue;
            if (info.has("error") && !info.get("error").isJsonNull()) return "error";
            String finish = string(info, "finish");
            if ("stop".equals(finish) || "end_turn".equals(finish) || "completed".equals(finish)) {
                return "completed";
            }
            // tool-calls / length / empty finish means the child is still
            // working or its next step has not started yet.
            return "running";
        }
        // No child assistant message is terminal evidence either. Keep polling
        // even when the parent launch tool already says completed.
        return "running";
    }

    static String childFromList(JsonElement sessions, String description) {
        JsonArray list = asArray(sessions);
        if (list.size() == 0 && sessions != null && sessions.isJsonObject()
                && sessions.getAsJsonObject().has("sessions")) {
            list = asArray(sessions.getAsJsonObject().get("sessions"));
        }
        String bestId = null;
        long bestUpdated = Long.MIN_VALUE;
        String wanted = description == null ? "" : description.trim().toLowerCase();
        for (JsonElement element : list) {
            if (!element.isJsonObject()) continue;
            JsonObject session = element.getAsJsonObject();
            String id = string(session, "id");
            if (id.isEmpty()) continue;
            String title = string(session, "title").trim().toLowerCase();
            boolean matches = wanted.isEmpty() || title.equals(wanted) || title.contains(wanted);
            if (!matches) continue;
            long updated = longValue(session);
            if (updated >= bestUpdated) {
                bestUpdated = updated;
                bestId = id;
            }
        }
        return bestId;
    }

    private static long longValue(JsonObject session) {
        if (session.has("time") && session.get("time").isJsonObject()) {
            JsonObject time = session.getAsJsonObject("time");
            JsonElement updated = time.get("updated");
            if (updated != null && !updated.isJsonNull() && updated.isJsonPrimitive()) {
                try {
                    return updated.getAsLong();
                } catch (RuntimeException ignored) {
                    // Fall through to 0.
                }
            }
        }
        return 0L;
    }

    // ── child transcript → SubagentHistoryResponse.messages ─────────────

    static JsonArray convertEntries(JsonElement entries) {
        JsonArray messages = new JsonArray();
        for (JsonElement entryElement : asArray(entries)) {
            if (!entryElement.isJsonObject()) continue;
            JsonObject entry = entryElement.getAsJsonObject();
            JsonObject info = entry.has("info") && entry.get("info").isJsonObject()
                    ? entry.getAsJsonObject("info") : null;
            if (info == null) continue;
            String role = string(info, "role");
            if (!"assistant".equals(role) && !"user".equals(role)) continue;

            JsonArray blocks = new JsonArray();
            for (JsonElement partElement : asArray(entry.get("parts"))) {
                if (!partElement.isJsonObject()) continue;
                JsonObject part = partElement.getAsJsonObject();
                String type = string(part, "type");
                switch (type) {
                    case "text" -> {
                        String text = string(part, "text");
                        if (!text.isBlank()) {
                            JsonObject block = new JsonObject();
                            block.addProperty("type", "text");
                            block.addProperty("text", text);
                            blocks.add(block);
                        }
                    }
                    case "reasoning", "thinking" -> {
                        String text = first(string(part, "text"), string(part, "thinking"));
                        if (text != null && !text.isBlank()) {
                            JsonObject block = new JsonObject();
                            block.addProperty("type", "thinking");
                            block.addProperty("thinking", text);
                            blocks.add(block);
                        }
                    }
                    case "tool", "tool_use" -> {
                        JsonObject state = part.has("state") && part.get("state").isJsonObject()
                                ? part.getAsJsonObject("state") : new JsonObject();
                        JsonElement input = state.has("input") ? state.get("input") : part.get("input");
                        JsonObject block = new JsonObject();
                        block.addProperty("type", "tool_use");
                        block.addProperty("id", first(string(part, "id"), string(part, "callID")));
                        block.addProperty("name", first(string(part, "tool"), string(part, "name"), "Tool"));
                        if (input != null && !input.isJsonNull()) {
                            block.add("input", input);
                        }
                        blocks.add(block);
                    }
                    default -> {
                        // Tool results and provider bookkeeping are not part of
                        // the process card; the final report is carried separately.
                    }
                }
            }
            if (blocks.isEmpty()) continue;

            JsonObject message = new JsonObject();
            message.addProperty("type", role);
            JsonObject body = new JsonObject();
            body.add("content", blocks);
            message.add("message", body);
            String id = string(info, "id");
            if (!id.isEmpty()) {
                message.addProperty("id", id);
            }
            messages.add(message);
        }
        return messages;
    }

    // ── small JSON helpers ──────────────────────────────────────────────

    private static JsonObject parsePayload(String content) {
        if (content == null || content.isBlank()) return new JsonObject();
        try {
            JsonElement parsed = JsonParser.parseString(content);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException ignored) {
            return new JsonObject();
        }
    }

    private static JsonArray asArray(JsonElement element) {
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }

    private static String string(JsonObject object, String field) {
        if (object == null || !object.has(field) || object.get(field).isJsonNull()) return "";
        JsonElement value = object.get(field);
        return value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private static String flattenContent(JsonElement content) {
        if (content == null || content.isJsonNull()) return "";
        if (content.isJsonPrimitive()) return content.getAsString();
        if (!content.isJsonArray()) return "";
        StringBuilder text = new StringBuilder();
        for (JsonElement item : content.getAsJsonArray()) {
            if (item.isJsonObject()) {
                String value = string(item.getAsJsonObject(), "text");
                if (!value.isBlank()) {
                    if (!text.isEmpty()) text.append('\n');
                    text.append(value);
                }
            }
        }
        return text.toString();
    }

    /** The child id and terminal state carried by one parent tool part. */
    static final class FoundTool {
        final String childSessionId;
        final String status;
        final String output;

        FoundTool(String childSessionId, String status, String output) {
            this.childSessionId = childSessionId;
            this.status = status;
            this.output = output;
        }
    }
}
