package com.opencodebuddy.tab;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One event on its way from the host to the webview.
 *
 * <p>An event carries its identity in named fields and its content in a named
 * payload. Nothing travels positionally: a handler reads
 * {@code payload.delta} or {@code payload.reason}, and a new field can be
 * added without touching every other handler's signature.</p>
 *
 * <p>The identity fields are the ones the plan makes mandatory: the tab that
 * owns the event, the session it belongs to (when it has one), the turn it
 * belongs to (when it has one) and the sequence number inside that turn.</p>
 */
public final class TabEvent {

    private final String type;
    private final String tabId;
    private final String sessionId;
    private final Long turnId;
    private final Long seq;
    private final Map<String, Object> payload;

    private TabEvent(
            String type,
            String tabId,
            String sessionId,
            Long turnId,
            Long seq,
            Map<String, Object> payload
    ) {
        this.type = type;
        this.tabId = tabId;
        this.sessionId = sessionId;
        this.turnId = turnId;
        this.seq = seq;
        this.payload = payload;
    }

    public static Builder of(String type) {
        return new Builder(type);
    }

    public String getType() {
        return type;
    }

    /** The tab that owns the event, or {@code null} when it is window-level. */
    public String getTabId() {
        return tabId;
    }

    /** The session the event belongs to, or {@code null}. */
    public String getSessionId() {
        return sessionId;
    }

    public Long getTurnId() {
        return turnId;
    }

    public Long getSeq() {
        return seq;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    /** The event as the JSON body of the bridge envelope. */
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("type", type);
        if (tabId != null) {
            json.addProperty("tabId", tabId);
        }
        if (sessionId != null) {
            json.addProperty("sessionId", sessionId);
        }
        if (turnId != null) {
            json.addProperty("turnId", turnId);
        }
        if (seq != null) {
            json.addProperty("seq", seq);
        }
        JsonObject body = new JsonObject();
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            body.add(entry.getKey(), toJsonValue(entry.getValue()));
        }
        json.add("payload", body);
        return json;
    }

    private static JsonElement toJsonValue(Object value) {
        if (value == null) {
            return com.google.gson.JsonNull.INSTANCE;
        }
        if (value instanceof JsonElement element) {
            return element;
        }
        if (value instanceof Boolean bool) {
            return new JsonPrimitive(bool);
        }
        if (value instanceof Number number) {
            return new JsonPrimitive(number);
        }
        if (value instanceof String text) {
            return new JsonPrimitive(text);
        }
        return new com.google.gson.Gson().toJsonTree(value);
    }

    @Override
    public String toString() {
        return "TabEvent{" + type + (tabId != null ? " tab=" + tabId : "")
                + (sessionId != null ? " session=" + sessionId : "") + "}";
    }

    /**
     * The same event addressed to {@code tabId}.
     *
     * <p>Every tab-scoped event must name its tab: the webview routes by that
     * id and cannot fall back to "the session on screen" without risking a
     * cross-session write. Events produced by a path that only knows the
     * session are re-addressed at the single publish choke point.</p>
     */
    public TabEvent withTabId(String tabId) {
        if (tabId == null || tabId.equals(this.tabId)) {
            return this;
        }
        return new TabEvent(type, tabId, sessionId, turnId, seq, new LinkedHashMap<>(payload));
    }

    /** Builds an event: identity first, content second. */
    public static final class Builder {
        private final String type;
        private String tabId;
        private String sessionId;
        private Long turnId;
        private Long seq;
        private final Map<String, Object> payload = new LinkedHashMap<>();

        private Builder(String type) {
            this.type = type;
        }

        public Builder tab(String tabId) {
            this.tabId = tabId;
            return this;
        }

        public Builder session(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder turn(Long turnId) {
            this.turnId = turnId;
            return this;
        }

        public Builder seq(Long seq) {
            this.seq = seq;
            return this;
        }

        /** A named content field. */
        public Builder field(String name, Object value) {
            payload.put(name, value);
            return this;
        }

        /** A named content field holding a JSON document. */
        public Builder json(String name, String json) {
            if (json == null || json.isBlank()) {
                payload.put(name, com.google.gson.JsonNull.INSTANCE);
            } else {
                try {
                    payload.put(name, com.google.gson.JsonParser.parseString(json));
                } catch (RuntimeException e) {
                    payload.put(name, json);
                }
            }
            return this;
        }

        public TabEvent build() {
            return new TabEvent(type, tabId, sessionId, turnId, seq, new LinkedHashMap<>(payload));
        }
    }
}
