package com.opencodebuddy.session;

import com.opencodebuddy.provider.opencode.OpenCodeSDKBridge;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.concurrent.ExecutionException;
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
     *
     * 失败不再被压成空列表：`return List.of()` 会让「查询失败」和「会话确实没有
     * 消息」变成同一个结果，调用方据此把失败的恢复当成空会话展示（打开标签页
     * 一片空白、无提示），而 Java 侧仍绑着那个 sessionId —— 用户一发送消息就会
     * 被追加到旧会话，旧对话整份冒出来。现在失败抛出，由调用方决定重试或降级。
     *
     * @throws SessionMessageLoadException 查询超时或 daemon 返回失败时
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
            throw new SessionMessageLoadException(
                    "Failed to load messages for session " + sessionId + ": " + describe(e), e);
        }
    }

    private static String describe(Throwable e) {
        Throwable cause = (e instanceof ExecutionException && e.getCause() != null) ? e.getCause() : e;
        String message = cause.getMessage();
        return (message == null || message.isBlank()) ? cause.getClass().getSimpleName() : message;
    }

    /** 会话消息加载失败（区别于「会话没有消息」）。 */
    public static class SessionMessageLoadException extends RuntimeException {
        public SessionMessageLoadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
