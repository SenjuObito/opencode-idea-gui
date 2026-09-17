package com.opencodebuddy.session;

import com.opencodebuddy.handler.SettingsHandler;
import com.opencodebuddy.notifications.OpencodeNotifier;
import com.opencodebuddy.util.TokenUsageUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Owns session-history loading and post-send message reconciliation.
 */
public class SessionMessageOrchestrator {

    private static final Logger LOG = Logger.getInstance(SessionMessageOrchestrator.class);
    private static final int MAX_UUID_SYNC_RETRIES = 3;

    public interface SessionHistoryAccess {
        List<JsonObject> getProviderSessionMessages(String provider, String sessionId, String cwd);

        JsonObject getLatestClaudeUserMessage(String sessionId, String cwd);
    }

    @FunctionalInterface
    public interface UsageDisplay {
        void show(int usedTokens, int maxTokens);
    }

    private final SessionState state;
    private final MessageParser messageParser;
    private final SessionCallbackFacade callbackFacade;
    private final SessionHistoryAccess historyAccess;
    private final UsageDisplay usageDisplay;
    private final long initialUuidSyncDelayMs;
    private final long uuidRetryDelayMs;

    public SessionMessageOrchestrator(
            Project project,
            SessionState state,
            MessageParser messageParser,
            SessionCallbackFacade callbackFacade,
            SessionHistoryAccess historyAccess
    ) {
        this(
                state,
                messageParser,
                callbackFacade,
                historyAccess,
                (usedTokens, maxTokens) -> {
                    if (project != null) {
                        OpencodeNotifier.setTokenUsage(project, usedTokens, maxTokens);
                    }
                    callbackFacade.notifyUsageUpdate(usedTokens, maxTokens);
                },
                100,
                50
        );
    }

    SessionMessageOrchestrator(
            SessionState state,
            MessageParser messageParser,
            SessionCallbackFacade callbackFacade,
            SessionHistoryAccess historyAccess,
            UsageDisplay usageDisplay,
            long initialUuidSyncDelayMs,
            long uuidRetryDelayMs
    ) {
        this.state = state;
        this.messageParser = messageParser;
        this.callbackFacade = callbackFacade;
        this.historyAccess = historyAccess;
        this.usageDisplay = usageDisplay;
        this.initialUuidSyncDelayMs = initialUuidSyncDelayMs;
        this.uuidRetryDelayMs = uuidRetryDelayMs;
    }

    public CompletableFuture<Void> syncUserMessageUuidsAfterSend() {
        String provider = state.getProvider();
        if ("codex".equals(provider)
                || SessionProviderRouter.isCliProvider(provider)
                || findLatestUnresolvedUserMessage() == null) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            sleep(initialUuidSyncDelayMs);
            updateUserMessageUuids();
        });
    }

    void updateUserMessageUuids() {
        String sessionId = state.getSessionId();
        String cwd = state.getCwd();

        if (sessionId == null || sessionId.isEmpty()) {
            return;
        }

        if (findLatestUnresolvedUserMessage() == null) {
            return;
        }

        for (int attempt = 1; attempt <= MAX_UUID_SYNC_RETRIES; attempt++) {
            try {
                JsonObject latestClaudeUserMessage = historyAccess.getLatestClaudeUserMessage(sessionId, cwd);
                if (latestClaudeUserMessage == null) {
                    if (attempt < MAX_UUID_SYNC_RETRIES) {
                        sleep(uuidRetryDelayMs);
                        continue;
                    }
                    return;
                }

                OpencodeSession.Message matchedMessage = patchMatchingUserMessage(latestClaudeUserMessage);
                if (matchedMessage != null && matchedMessage.raw != null && matchedMessage.raw.has("uuid")) {
                    callbackFacade.notifyUserMessageUuidPatched(
                            matchedMessage.content != null ? matchedMessage.content : "",
                            matchedMessage.raw.get("uuid").getAsString()
                    );
                    return;
                }

                if (attempt < MAX_UUID_SYNC_RETRIES) {
                    sleep(uuidRetryDelayMs);
                }
            } catch (Exception e) {
                LOG.warn("[Rewind] Failed to update user message UUIDs (attempt " + attempt + "): " + e.getMessage());
                if (attempt < MAX_UUID_SYNC_RETRIES) {
                    sleep(uuidRetryDelayMs);
                }
            }
        }
    }

    public CompletableFuture<Void> loadFromServer() {
        if (state.getSessionId() == null) {
            return CompletableFuture.completedFuture(null);
        }

        state.setLoading(true);
        callbackFacade.notifyStateChange(state.isBusy(), state.isLoading(), state.getError());

        return CompletableFuture.runAsync(() -> {
            try {
                String currentSessionId = state.getSessionId();
                String currentCwd = state.getCwd();
                String currentProvider = state.getProvider();

                LOG.info("Loading session from server: sessionId=" + currentSessionId + ", cwd=" + currentCwd);
                List<JsonObject> serverMessages =
                        historyAccess.getProviderSessionMessages(currentProvider, currentSessionId, currentCwd);
                if (serverMessages == null) {
                    serverMessages = List.of();
                }

                LOG.debug("Received " + serverMessages.size() + " messages from server");

                state.setError(null);
                state.clearMessages();
                for (JsonObject msg : serverMessages) {
                    OpencodeSession.Message message = messageParser.parseServerMessage(msg);
                    if (message != null) {
                        state.addMessage(message);
                    }
                }

                // 用量必须从「已解析的会话消息」里取，而不是转换器的外层 JSON：
                // OpenCodeMessageConverter 把 opencode 的 tokens 挂在 outer.raw.tokens，
                // 外层对象本身没有 usage/turnUsage/tokens 字段，
                // TokenUsageUtils.findLastUsageFromRawMessages 因此永远拿到 null，
                // 历史会话的上下文用量就停在切会话时被重置的 0%。
                restoreTokenUsage();
                callbackFacade.notifyMessageUpdate(state.getMessages());
            } catch (Exception e) {
                state.setError(e.getMessage());
                LOG.error("Error loading session: " + e.getMessage(), e);
                // 关键：把失败传出去。此前异常在这里被吞掉、future 正常完成，调用方
                // 只能看到「加载成功但消息为空」，于是留下一个「sessionId 已绑定 +
                // 消息为空」的幽灵状态 —— 用户一发送消息就会被追加到旧会话，旧对话
                // 整份冒出来（表现为「打开是空的，发一条旧对话立刻出现」）。
                // 现在失败可被调用方识别，用于重试或解除绑定。
                throw new CompletionException(e);
            } finally {
                state.setLoading(false);
                callbackFacade.notifyStateChange(state.isBusy(), state.isLoading(), state.getError());
            }
        });
    }

    private OpencodeSession.Message patchMatchingUserMessage(JsonObject historyMessage) {
        if (!historyMessage.has("type") || !"user".equals(historyMessage.get("type").getAsString())) {
            return null;
        }
        if (!historyMessage.has("uuid") || historyMessage.get("uuid").isJsonNull()) {
            return null;
        }

        String historyContent = extractMessageContentForMatching(historyMessage);
        if (historyContent == null || historyContent.isEmpty()) {
            return null;
        }

        String uuid = historyMessage.get("uuid").getAsString();
        List<OpencodeSession.Message> localMessages = state.getMessagesReference();
        for (int i = localMessages.size() - 1; i >= 0; i--) {
            OpencodeSession.Message localMsg = localMessages.get(i);
            if (localMsg.type != OpencodeSession.Message.Type.USER) {
                continue;
            }
            synchronized (localMsg) {
                if (localMsg.raw != null && localMsg.raw.has("uuid") && !localMsg.raw.get("uuid").isJsonNull()) {
                    continue;
                }
                if (!historyContent.equals(localMsg.content)) {
                    continue;
                }

                if (localMsg.raw == null) {
                    localMsg.raw = createDefaultUserRaw(localMsg.content);
                }
                localMsg.raw.addProperty("uuid", uuid);
            }
            return localMsg;
        }

        return null;
    }

    static JsonObject createDefaultUserRaw(String content) {
        JsonObject raw = new JsonObject();
        JsonObject message = new JsonObject();
        JsonArray contentArray = new JsonArray();
        JsonObject textBlock = new JsonObject();
        textBlock.addProperty("type", "text");
        textBlock.addProperty("text", content != null ? content : "");
        contentArray.add(textBlock);
        message.add("content", contentArray);
        raw.add("message", message);
        return raw;
    }

    private OpencodeSession.Message findLatestUnresolvedUserMessage() {
        List<OpencodeSession.Message> messages = state.getMessagesReference();
        for (int i = messages.size() - 1; i >= 0; i--) {
            OpencodeSession.Message message = messages.get(i);
            if (message.type != OpencodeSession.Message.Type.USER) {
                continue;
            }
            if (message.content == null || message.content.isEmpty() || "[tool_result]".equals(message.content)) {
                continue;
            }
            if (message.raw == null) {
                return message;
            }
            if (!message.raw.has("uuid") || message.raw.get("uuid").isJsonNull()) {
                return message;
            }
        }
        return null;
    }

    String extractMessageContentForMatching(JsonObject msg) {
        if (!msg.has("message") || !msg.get("message").isJsonObject()) {
            return null;
        }
        JsonObject message = msg.getAsJsonObject("message");
        if (!message.has("content")) {
            return null;
        }

        JsonElement contentElement = message.get("content");
        if (contentElement.isJsonPrimitive()) {
            return contentElement.getAsString();
        }

        if (contentElement.isJsonArray()) {
            JsonArray contentArray = contentElement.getAsJsonArray();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < contentArray.size(); i++) {
                JsonElement element = contentArray.get(i);
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject block = element.getAsJsonObject();
                if (block.has("type") && "text".equals(block.get("type").getAsString()) && block.has("text")) {
                    if (sb.length() > 0) {
                        sb.append("\n");
                    }
                    sb.append(block.get("text").getAsString());
                }
            }
            return sb.toString();
        }

        return null;
    }

    /**
     * 恢复历史会话的上下文用量环。
     *
     * <p>对应 vscode 插件 {@code OpenCodeSession.republishUsageFromHistory()}：
     * 在 state 里已经装好解析后的消息之后，从最后一条带用量的助手消息重推一次
     * used/max。没有历史用量则什么都不推，让前端保持原样，而不是把 0 当成真值。</p>
     *
     * <p>取值走 {@code state.getMessages()}（{@link OpencodeSession.Message#raw} 层），
     * 因为 opencode 的 token 快照位于 {@code raw.tokens}；早期版本误传
     * {@link OpenCodeMessageConverter} 的外层对象（用量埋在 {@code raw} 里），
     * 导致 {@code findLastUsageFromRawMessages} 恒为 null、用量环永远显示 0%。</p>
     */
    private void restoreTokenUsage() {
        try {
            JsonObject lastUsage = TokenUsageUtils.findLastUsageFromSessionMessages(
                    state.getMessages(), state.getProvider());
            if (lastUsage == null) {
                return;
            }

            int usedTokens = TokenUsageUtils.extractContextTokens(lastUsage, state.getProvider());
            int fallbackMaxTokens = SettingsHandler.getModelContextLimit(
                    state.getProvider(), state.getModel());
            int maxTokens = TokenUsageUtils.extractMaxTokens(lastUsage, fallbackMaxTokens);
            usageDisplay.show(usedTokens, maxTokens);
            LOG.debug("Restored token usage from history: " + usedTokens + " / " + maxTokens);
        } catch (Exception e) {
            LOG.warn("Failed to extract token usage from history: " + e.getMessage());
        }
    }

    private void sleep(long delayMs) {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
