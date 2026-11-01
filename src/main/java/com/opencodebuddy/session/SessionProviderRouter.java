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

    /**
     * Abort the active turn.
     *
     * <p>{@code channelId} is the local channel identifier (a random UUID) and is
     * deliberately NOT forwarded: the daemon hands the value straight to
     * {@code sdk.abort(...)}, and opencode rejects anything that is not a session
     * id with {@code Expected a string starting with "ses"}. The abort target must
     * therefore be the real opencode session id ({@code ses_*}); when it is not
     * known yet the daemon falls back to aborting every active turn.
     *
     * @param channelId local channel id, kept for routing symmetry with
     *                  {@link #launchChannel}
     * @param sessionId opencode session id ({@code ses_*}), or {@code null} when
     *                  no turn has produced a session id yet
     */
    public void interruptChannel(String provider, String channelId, String sessionId) {
        if (openCodeSDKBridge != null) {
            openCodeSDKBridge.abort(sessionId);
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
        return getSessionMessagePage(provider, sessionId, cwd, FIRST_PAGE_SIZE, null, null, null).messages();
    }

    /**
     * Page size requested when opening a session.
     *
     * <p>opencode 2.x answers with 50 messages unless told otherwise, and
     * refuses {@code limit > 200}. Opening a long session without asking for a
     * full page made it look as if only the newest 50 records existed.</p>
     */
    public static final int FIRST_PAGE_SIZE = 200;

    /** One page of a transcript, converted for the session layer. */
    public record MessagePage(List<JsonObject> messages, String nextCursor, boolean hasEarlier) {
    }

    /**
     * Read one page of the transcript.
     *
     * <p>{@code cursor} is {@code nextCursor} of the previous page; {@code type}
     * filters by message type ({@code user}) and {@code order} must not be
     * combined with a cursor (the server rejects that). A filtered page can
     * report a cursor at the real end, so callers stop on an empty page.</p>
     *
     * @throws SessionMessageLoadException on timeout or daemon failure
     */
    public MessagePage getSessionMessagePage(
            String provider,
            String sessionId,
            String cwd,
            int limit,
            String cursor,
            String type,
            String order) {
        if (openCodeSDKBridge == null || sessionId == null || sessionId.isBlank()) {
            return new MessagePage(List.of(), null, false);
        }
        try {
            OpenCodeSDKBridge.MessagePage page = openCodeSDKBridge
                    .listMessagePage(sessionId, cwd, limit, cursor, type, order)
                    .get(60, TimeUnit.SECONDS);
            JsonElement element = page.messages();
            // opencode persists a slash command as its expanded template. Put
            // the text the user typed back where the server lost it, so a
            // reloaded transcript still shows `/review` instead of the prompt.
            SessionCommandTextStore commandStore = SessionCommandTextStore.getInstanceOrNull();
            java.util.Map<String, String> commandTexts = commandStore != null
                    ? OpenCodeMessageConverter.matchCommandTexts(
                            element, commandStore.entriesFor(sessionId))
                    : java.util.Map.of();
            List<JsonObject> messages = OpenCodeMessageConverter.convert(element, commandTexts);
            return new MessagePage(messages, page.nextCursor(), page.hasEarlier());
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
