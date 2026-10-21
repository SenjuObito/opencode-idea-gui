package com.opencodebuddy.tab;

import com.google.gson.JsonObject;

import java.util.Objects;

/**
 * One tab-scoped command from the webview.
 *
 * <p>{@code requestId} is the sender's idempotency key: a reloaded webview can
 * replay a command whose first delivery was already applied, and the router
 * answers the repeat without running the handler twice.</p>
 */
public record TabCommand(TabId tabId, String type, String requestId, JsonObject payload) {

    public TabCommand {
        Objects.requireNonNull(tabId, "tabId");
        Objects.requireNonNull(type, "type");
        if (type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
        payload = payload != null ? payload : new JsonObject();
    }

    public static TabCommand of(String tabId, String type, String requestId, JsonObject payload) {
        return new TabCommand(TabId.of(tabId), type, requestId, payload);
    }

    public String string(String key) {
        return payload.has(key) && !payload.get(key).isJsonNull()
                ? payload.get(key).getAsString()
                : null;
    }
}
