package com.opencodebuddy.session;

import com.opencodebuddy.permission.PermissionRequest;

import java.util.List;

/**
 * Callback handler.
 * Dispatches various session callback notifications.
 */
public class CallbackHandler {
    private OpencodeSession.SessionCallback callback;

    public void setCallback(OpencodeSession.SessionCallback callback) {
        this.callback = callback;
    }

    /**
     * Notify of a message update.
     */
    public void notifyMessageUpdate(List<OpencodeSession.Message> messages) {
        if (callback != null) {
            callback.onMessageUpdate(messages);
        }
    }

    /**
     * Notify of a state change.
     */
    public void notifyStateChange(boolean busy, boolean loading, String error) {
        if (callback != null) {
            callback.onStateChange(busy, loading, error);
        }
    }

    /**
     * Notify status message (e.g., reconnecting notices).
     */
    public void notifyStatusMessage(String message) {
        if (callback != null) {
            callback.onStatusMessage(message);
        }
    }

    /**
     * Notify that a session ID was received.
     */
    public void notifySessionIdReceived(String sessionId) {
        if (callback != null) {
            callback.onSessionIdReceived(sessionId);
        }
    }

    /**
     * Notify that a session title update was received.
     */
    public void notifySessionTitleReceived(String sessionId, String title) {
        if (callback != null) {
            callback.onSessionTitleReceived(sessionId, title);
        }
    }

    /**
     * Notify of a permission request.
     */
    public void notifyPermissionRequested(PermissionRequest request) {
        if (callback != null) {
            callback.onPermissionRequested(request);
        }
    }

    /**
     * Notify of a thinking status change.
     */
    public void notifyThinkingStatusChanged(boolean isThinking) {
        if (callback != null) {
            callback.onThinkingStatusChanged(isThinking);
        }
    }

    /**
     * Notify that slash commands were received.
     */
    public void notifySlashCommandsReceived(List<String> slashCommands) {
        if (callback != null) {
            callback.onSlashCommandsReceived(slashCommands);
        }
    }

    /**
     * Notify of a Node.js log (forwarded to frontend console).
     */
    public void notifyNodeLog(String log) {
        if (callback != null) {
            callback.onNodeLog(log);
        }
    }

    public void notifySummaryReceived(String summary) {
        if (callback != null) {
            callback.onSummaryReceived(summary);
        }
    }
    // ===== Streaming notification methods =====

    /**
     * Notify that streaming has started.
     */
    public void notifyStreamStart() {
        if (callback != null) {
            callback.onStreamStart();
        }
    }

    /**
     * Notify that streaming has ended.
     */
    public void notifyStreamEnd() {
        if (callback != null) {
            callback.onStreamEnd();
        }
    }

    /**
     * Notify of a content delta (handled by the existing onContentDelta callback).
     */
    public void notifyContentDelta(String delta) {
        if (callback != null) {
            callback.onContentDelta(delta);
        }
    }

    /**
     * Notify of a thinking delta.
     */
    public void notifyThinkingDelta(String delta) {
        if (callback != null) {
            callback.onThinkingDelta(delta);
        }
    }

    /**
     * Notify that a block reset signal was received.
     * Frontend should clear streaming content refs to prevent cross-turn merging.
     */
    public void notifyBlockReset() {
        if (callback != null) {
            callback.onBlockReset();
        }
    }

    /**
     * Notify of a usage update.
     */
    public void notifyUsageUpdate(int usedTokens, int maxTokens) {
        if (callback != null) {
            callback.onUsageUpdate(usedTokens, maxTokens);
        }
    }

    /**
     * Notify that a specific message received its provider UUID.
     */
    public void notifyUserMessageUuidPatched(String content, String uuid) {
        if (callback != null) {
            callback.onUserMessageUuidPatched(content, uuid);
        }
    }

    /**
     * Notify of a Claude Code task_* SDK system event (task_started /
     * task_progress / task_notification).
     */
    public void notifyTaskEvent(String eventJson) {
        if (callback != null) {
            callback.onTaskEvent(eventJson);
        }
    }

    /**
     * Notify of a question request (AskUserQuestion).
     */
    public void notifyQuestionRequested(String jsonContent) {
        if (callback != null) {
            callback.onQuestionRequested(jsonContent);
        }
    }

    /**
     * Notify that a question or permission prompt was closed/resolved by the server.
     */
    public void notifyPromptClosed(String kind, String jsonContent) {
        if (callback != null) {
            callback.onPromptClosed(kind, jsonContent);
        }
    }

    /**
     * Notify that session revert state was updated.
     */
    public void notifyRevertStateUpdate(boolean hasRevert, String messageId) {
        if (callback != null) {
            callback.onRevertStateUpdate(hasRevert, messageId);
        }
    }

    public void notifyRevertStateUpdate(boolean hasRevert) {
        notifyRevertStateUpdate(hasRevert, null);
    }

    /**
     * Notify that the server removed these messages from the session.
     *
     * <p>Driven by opencode's {@code message.removed} events, emitted when a
     * pending revert is finally applied (cleanup runs at the start of the next
     * prompt). Carries opencode message ids — the same ids the frontend matches
     * against {@code message.id} / {@code raw.id} / {@code raw.uuid}.</p>
     */
    public void notifyMessagesRemoved(List<String> messageIds) {
        if (callback != null && messageIds != null && !messageIds.isEmpty()) {
            callback.onMessagesRemoved(messageIds);
        }
    }
}
