package com.opencodebuddy.prompt;

import com.google.gson.JsonArray;

/**
 * A prompt that is waiting for an answer.
 *
 * <p>The identity of a prompt is {@code (tabId, requestId)}: the tab owns the
 * dialog, the request id addresses the request inside it. The session id is
 * carried along because the answer has to go back to the session that asked
 * — but the tab is what decides where the prompt lives and when it dies.</p>
 */
public final class PendingPrompt {

    private final String requestId;
    private final PromptKind kind;
    private final String sessionId;
    private final String tabId;
    private final String toolName;
    private final String directory;
    private final JsonArray questions;

    public PendingPrompt(
            String requestId,
            PromptKind kind,
            String sessionId,
            String tabId,
            String toolName,
            String directory,
            JsonArray questions
    ) {
        this.requestId = requestId;
        this.kind = kind;
        this.sessionId = sessionId;
        this.tabId = tabId;
        this.toolName = toolName;
        this.directory = directory;
        this.questions = questions;
    }

    public String getRequestId() {
        return requestId;
    }

    public PromptKind getKind() {
        return kind;
    }

    public String getSessionId() {
        return sessionId;
    }

    /** The tab that owns the prompt; {@code null} when the session has no tab. */
    public String getTabId() {
        return tabId;
    }

    public String getToolName() {
        return toolName;
    }

    public String getDirectory() {
        return directory;
    }

    public JsonArray getQuestions() {
        return questions;
    }
}
