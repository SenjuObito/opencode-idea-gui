package com.opencodebuddy.handler.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * One webview → host command in the v2 envelope
 * ({@code {v, type, requestId, tabId, sessionId?, payload}}).
 *
 * <p>Parsing and validation live here so every entry point applies the same
 * rules: the version must match, the tab id is mandatory, and the payload is
 * handed on with the envelope's identity merged in. That is what removes the
 * "which session is this?" guess from the handlers.</p>
 */
public final class HostCommand {

    public static final int VERSION = 2;

    /** Rejection reason carried back to the webview as {@code commandRejected}. */
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

    private HostCommand(String type, String tabId, String sessionId, String requestId, String payloadJson) {
        this.type = type;
        this.tabId = tabId;
        this.sessionId = sessionId;
        this.requestId = requestId;
        this.payloadJson = payloadJson;
    }

    /**
     * @param content   the raw bridge payload
     * @param expectedType the command type this entry point accepts
     * @throws Invalid when the envelope is malformed or names another command
     */
    public static HostCommand parse(String content, String expectedType) throws Invalid {
        if (content == null || content.isBlank()) {
            throw new Invalid("BAD_ENVELOPE", "empty command");
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(content).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new Invalid("BAD_ENVELOPE", "command is not a JSON object");
        }

        if (!root.has("v") || root.get("v").isJsonNull()
                || root.get("v").getAsInt() != VERSION) {
            throw new Invalid("UNSUPPORTED_VERSION", "command envelope must be v" + VERSION);
        }

        String type = stringField(root, "type");
        if (type == null) {
            throw new Invalid("MISSING_TYPE", "command type is required");
        }
        if (expectedType != null && !expectedType.equals(type)) {
            throw new Invalid("UNKNOWN_TYPE", "expected " + expectedType + " but got " + type);
        }

        String tabId = stringField(root, "tabId");
        if (tabId == null) {
            throw new Invalid("MISSING_TAB_ID", "tabId is required");
        }

        String requestId = stringField(root, "requestId");
        if (requestId == null) {
            throw new Invalid("MISSING_REQUEST_ID", "requestId is required");
        }

        String sessionId = stringField(root, "sessionId");
        JsonObject payload = root.has("payload") && root.get("payload").isJsonObject()
                ? root.getAsJsonObject("payload").deepCopy()
                : new JsonObject();
        // The envelope owns identity; a payload copy can never disagree with it.
        payload.addProperty("tabId", tabId);
        if (sessionId != null) {
            payload.addProperty("sessionId", sessionId);
        }

        return new HostCommand(type, tabId, sessionId, requestId, payload.toString());
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

    /** @return the canonical session named by the command, or {@code null} for a tab-only command */
    public String getSessionId() {
        return sessionId;
    }

    public String getRequestId() {
        return requestId;
    }

    /** Payload with {@code tabId}/{@code sessionId} merged in from the envelope. */
    public String getPayloadJson() {
        return payloadJson;
    }
}
