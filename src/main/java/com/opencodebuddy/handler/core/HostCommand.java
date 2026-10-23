package com.opencodebuddy.handler.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The uniform webview → host command.
 *
 * <p>Every command travels in the same envelope:
 * {@code {v, type, requestId, tabId, sessionId?, payload}}. The host validates
 * it, remembers the request id (so a replayed command runs once) and hands the
 * payload to the handler that owns the command type.</p>
 *
 * <p>The envelope owns the identity: the payload may repeat it, but the
 * envelope's value wins, so a handler can never be talked into acting on a
 * different tab than the one the command names.</p>
 */
public final class HostCommand {

    public static final int VERSION = 2;

    /** The bridge event every command travels on. */
    public static final String TRANSPORT = "host_command";

    /** Rejection reason carried back to the webview as {@code command.rejected}. */
    public static final class Invalid extends Exception {
        private final String code;

        public Invalid(String code, String message) {
            super(message);
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }

    private final String type;
    private final String tabId;
    private final String sessionId;
    private final String requestId;
    private final String payloadJson;

    private HostCommand(
            String type,
            String tabId,
            String sessionId,
            String requestId,
            String payloadJson
    ) {
        this.type = type;
        this.tabId = tabId;
        this.sessionId = sessionId;
        this.requestId = requestId;
        this.payloadJson = payloadJson;
    }

    /**
     * Parse a command envelope.
     *
     * @param content       the raw bridge payload
     * @param expectedType  the command type this entry point accepts, or null
     * @param tabIdRequired when false, a command may name only the session
     */
    public static HostCommand parse(String content, String expectedType, boolean tabIdRequired)
            throws Invalid {
        if (content == null || content.isBlank()) {
            throw new Invalid("BAD_ENVELOPE", "empty command");
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(content).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new Invalid("BAD_ENVELOPE", "the command is not a JSON object");
        }
        if (!root.has("v") || root.get("v").isJsonNull() || root.get("v").getAsInt() != VERSION) {
            throw new Invalid("UNSUPPORTED_VERSION", "a command envelope must be v" + VERSION);
        }
        String type = stringField(root, "type");
        if (type == null) {
            throw new Invalid("MISSING_TYPE", "the command type is required");
        }
        if (expectedType != null && !expectedType.equals(type)) {
            throw new Invalid("UNKNOWN_TYPE", "expected " + expectedType + " but got " + type);
        }
        String requestId = stringField(root, "requestId");
        if (requestId == null) {
            throw new Invalid("MISSING_REQUEST_ID", "the command request id is required");
        }
        String tabId = stringField(root, "tabId");
        String sessionId = stringField(root, "sessionId");
        if (tabId == null && tabIdRequired) {
            if (sessionId == null) {
                throw new Invalid("MISSING_TAB_ID",
                        "the command must name the tab it belongs to");
            }
        }

        String payloadJson;
        if (root.has("payload") && !root.get("payload").isJsonNull()) {
            JsonElement payload = root.get("payload");
            if (payload.isJsonObject()) {
                JsonObject object = payload.getAsJsonObject().deepCopy();
                // The envelope owns the identity.
                if (tabId != null) {
                    object.addProperty("tabId", tabId);
                }
                if (sessionId != null) {
                    object.addProperty("sessionId", sessionId);
                }
                object.addProperty("requestId", requestId);
                payloadJson = object.toString();
            } else if (payload.isJsonPrimitive() && payload.getAsJsonPrimitive().isString()) {
                // A window-level command whose whole content is one string
                // (`delete_session:ses-x`, `open_file:/a/b`). Handlers expect the
                // bare value, not a JSON string literal.
                payloadJson = payload.getAsString();
            } else {
                payloadJson = payload.toString();
            }
        } else {
            JsonObject empty = new JsonObject();
            if (tabId != null) {
                empty.addProperty("tabId", tabId);
            }
            if (sessionId != null) {
                empty.addProperty("sessionId", sessionId);
            }
            empty.addProperty("requestId", requestId);
            payloadJson = empty.toString();
        }

        return new HostCommand(type, tabId, sessionId, requestId, payloadJson);
    }

    /** Parse a command that must name its tab. */
    public static HostCommand parse(String content, String expectedType) throws Invalid {
        return parse(content, expectedType, true);
    }

    private static String stringField(JsonObject root, String name) {
        if (!root.has(name) || root.get(name).isJsonNull()) {
            return null;
        }
        String value = root.get(name).getAsString();
        return value.isBlank() ? null : value;
    }

    public String getType() {
        return type;
    }

    public String getTabId() {
        return tabId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getRequestId() {
        return requestId;
    }

    /** The payload with the envelope's identity merged in. */
    public String getPayloadJson() {
        return payloadJson;
    }
}
