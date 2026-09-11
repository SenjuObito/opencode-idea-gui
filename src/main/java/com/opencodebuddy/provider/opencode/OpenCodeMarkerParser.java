package com.opencodebuddy.provider.opencode;

import com.opencodebuddy.provider.common.MessageCallback;
import com.opencodebuddy.provider.common.SDKResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Adapts tagged daemon output lines into bridge callbacks and SDKResult updates.
 *
 * <p>The ai-bridge daemon normalizes opencode SDK events into these markers
 * (see ai-bridge utils/marker-protocol.js) and wraps each line as
 * {@code {"id":<requestId>,"line":"..."}} NDJSON output.</p>
 */
public final class OpenCodeMarkerParser {

    private final Gson gson;

    public OpenCodeMarkerParser(Gson gson) {
        this.gson = gson;
    }

    /** Per-stream parsing context (spans the whole lifecycle of one send). */
    public static final class StreamContext {
        public final StringBuilder assistantContent = new StringBuilder();
        public final AtomicBoolean hadSendError = new AtomicBoolean(false);
        public final AtomicReference<String> lastNodeError = new AtomicReference<>(null);
        public final AtomicBoolean wasAborted = new AtomicBoolean(false);
    }

    public void processOutputLine(String line, MessageCallback callback, SDKResult result, StreamContext context) {
        if (line.startsWith("[STDIN_ERROR]")
                || line.startsWith("[STDIN_PARSE_ERROR]")
                || line.startsWith("[GET_SESSION_ERROR]")
                || line.startsWith("[PERSIST_ERROR]")) {
            context.lastNodeError.set(line);
        }

        if (line.startsWith("[MESSAGE]")) {
            String jsonStr = line.substring("[MESSAGE]".length()).trim();
            try {
                JsonObject msg = gson.fromJson(jsonStr, JsonObject.class);
                result.messages.add(msg);
                String type = msg.has("type") && !msg.get("type").isJsonNull()
                        ? msg.get("type").getAsString() : "unknown";
                callback.onMessage(type, jsonStr);
            } catch (Exception ignored) {
            }
            return;
        }

        if (line.startsWith("[SEND_ERROR]")) {
            // Suppress SEND_ERROR for user-initiated aborts so the UI does not
            // show an error toast; the abort path completes gracefully.
            if (context.wasAborted.get()) {
                return;
            }
            String jsonStr = line.substring("[SEND_ERROR]".length()).trim();
            String errorMessage = jsonStr;
            try {
                JsonObject obj = gson.fromJson(jsonStr, JsonObject.class);
                if (obj.has("error") && !obj.get("error").isJsonNull()) {
                    errorMessage = obj.get("error").getAsString();
                }
            } catch (Exception ignored) {
            }
            context.hadSendError.set(true);
            result.success = false;
            result.error = errorMessage;
            callback.onError(errorMessage);
            return;
        }

        if (line.startsWith("[CONTENT]")) {
            String content = stripSeparator(line, "[CONTENT]");
            context.assistantContent.append(content);
            callback.onMessage("content", content);
            return;
        }

        if (line.startsWith("[CONTENT_DELTA]")) {
            String delta = decodeJsonStringPayload(stripSeparator(line, "[CONTENT_DELTA]"));
            context.assistantContent.append(delta);
            callback.onMessage("content_delta", delta);
            return;
        }

        if (line.startsWith("[THINKING]")) {
            callback.onMessage("thinking", stripSeparator(line, "[THINKING]").trim());
            return;
        }

        if (line.startsWith("[THINKING_DELTA]")) {
            callback.onMessage("thinking_delta", decodeJsonStringPayload(stripSeparator(line, "[THINKING_DELTA]")));
            return;
        }

        if (line.startsWith("[STREAM_START]")) {
            callback.onMessage("stream_start", "");
            return;
        }

        if (line.startsWith("[STREAM_END]")) {
            callback.onMessage("stream_end", "");
            return;
        }

        if (line.startsWith("[SESSION_ID]")) {
            callback.onMessage("session_id", stripSeparator(line, "[SESSION_ID]").trim());
            return;
        }

        if (line.startsWith("[TOOL_RESULT]")) {
            callback.onMessage("tool_result", stripSeparator(line, "[TOOL_RESULT]").trim());
            return;
        }

        if (line.startsWith("[USAGE]")) {
            callback.onMessage("usage", stripSeparator(line, "[USAGE]").trim());
            return;
        }

        if (line.startsWith("[REVERT_STATE]")) {
            callback.onMessage("revert_state", stripSeparator(line, "[REVERT_STATE]").trim());
            return;
        }

        if (line.startsWith("[SESSION_TITLE]")) {
            String payload = stripSeparator(line, "[SESSION_TITLE]").trim();
            String decoded = decodeJsonStringPayload(payload);
            callback.onMessage("session_title", decoded);
            return;
        }

        if (line.startsWith("[PERMISSION_REQUEST]")) {
            callback.onMessage("permission_request", stripSeparator(line, "[PERMISSION_REQUEST]").trim());
            return;
        }

        if (line.startsWith("[QUESTION_REQUEST]")) {
            callback.onMessage("question_request", stripSeparator(line, "[QUESTION_REQUEST]").trim());
            return;
        }

        if (line.startsWith("[PERMISSION_CLOSED]")) {
            callback.onMessage("permission_closed", stripSeparator(line, "[PERMISSION_CLOSED]").trim());
            return;
        }

        if (line.startsWith("[QUESTION_CLOSED]")) {
            callback.onMessage("question_closed", stripSeparator(line, "[QUESTION_CLOSED]").trim());
            return;
        }

        if (line.startsWith("[TODO_UPDATED]")) {
            // The daemon emits [TODO_UPDATED] via emitJsonStringMarker, which
            // JSON-stringifies its payload — decode idempotently (a single decode
            // yields the raw object string when doubly encoded).
            String payload = stripSeparator(line, "[TODO_UPDATED]").trim();
            String decoded = decodeJsonStringPayload(payload);
            callback.onMessage("todo_updated", decoded.equals(payload) ? payload : gson.toJson(decoded));
            return;
        }

        if (line.startsWith("[MESSAGE_START]")) {
            callback.onMessage("message_start", "");
            return;
        }

        if (line.startsWith("[BLOCK_RESET]")) {
            callback.onMessage("block_reset", "");
            return;
        }

        if (line.startsWith("[MESSAGE_END]")) {
            callback.onMessage("message_end", "");
        }
    }

    private static String stripSeparator(String line, String marker) {
        String payload = line.substring(marker.length());
        return payload.startsWith(" ") ? payload.substring(1) : payload;
    }

    private String decodeJsonStringPayload(String jsonStr) {
        try {
            String decoded = gson.fromJson(jsonStr, String.class);
            return decoded != null ? decoded : jsonStr;
        } catch (Exception e) {
            return jsonStr;
        }
    }
}
