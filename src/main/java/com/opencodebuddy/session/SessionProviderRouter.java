package com.opencodebuddy.session;

import com.opencodebuddy.provider.opencode.OpenCodeSDKBridge;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Centralizes provider bridge routing. OpenCode-only build: the single
 * provider is opencode, accessed through the persistent daemon bridge.
 */
public class SessionProviderRouter {

    /** Canonical id of the only provider. */
    public static final String PROVIDER_ID = OpenCodeSDKBridge.PROVIDER_ID;

    private final OpenCodeSDKBridge openCodeSDKBridge;

    public SessionProviderRouter(OpenCodeSDKBridge openCodeSDKBridge) {
        this.openCodeSDKBridge = openCodeSDKBridge;
    }

    public OpenCodeSDKBridge getOpenCodeSDKBridge() {
        return openCodeSDKBridge;
    }

    /**
     * Whether {@code provider} is a headless CLI provider. Kept for call sites
     * that branch on provider ids; always true for the canonical id.
     */
    public static boolean isCliProvider(String provider) {
        return PROVIDER_ID.equals(provider);
    }

    /**
     * Start a channel. Session resolution happens daemon-side on the first
     * turn (the stream emits [SESSION_ID]); preconnect only warms the serve
     * process and SSE subscription.
     */
    public JsonObject launchChannel(String provider, String channelId, String sessionId, String cwd) {
        JsonObject result = new JsonObject();
        if (openCodeSDKBridge != null && sessionId == null) {
            openCodeSDKBridge.preconnect(sessionId, cwd);
        }
        return result;
    }

    /** Abort the active turn. */
    public void interruptChannel(String provider, String channelId) {
        if (openCodeSDKBridge != null) {
            openCodeSDKBridge.abort();
        }
    }

    /**
     * Load session messages for history restore, converted into the
     * Claude-compatible message shape the Java session layer understands.
     * Blocking (bounded) — callers run on a background executor.
     */
    public List<JsonObject> getSessionMessages(String provider, String sessionId, String cwd) {
        if (openCodeSDKBridge == null || sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        try {
            JsonElement element = openCodeSDKBridge
                    .listMessages(sessionId, cwd)
                    .get(60, TimeUnit.SECONDS);
            return OpenCodeMessageConverter.convert(element);
        } catch (Exception e) {
            return List.of();
        }
    }
}
