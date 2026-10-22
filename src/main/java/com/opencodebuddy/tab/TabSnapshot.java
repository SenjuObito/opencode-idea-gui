package com.opencodebuddy.tab;

import com.google.gson.JsonObject;

/**
 * Immutable view of one tab at a point in time.
 *
 * <p>The reload/resync payload is built from these instead of walking mutable
 * records while another thread binds or closes them. It carries the stable tab
 * id, so the webview recreates the same tab rather than inventing one from a
 * session id.</p>
 */
public record TabSnapshot(
        String tabId,
        String sessionId,
        String title,
        boolean active,
        boolean streaming,
        boolean loading,
        long epoch
) {

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("tabId", tabId);
        if (sessionId != null) {
            json.addProperty("sessionId", sessionId);
        }
        if (title != null) {
            json.addProperty("title", title);
        }
        json.addProperty("streaming", streaming);
        json.addProperty("loading", loading);
        json.addProperty("epoch", epoch);
        return json;
    }
}
