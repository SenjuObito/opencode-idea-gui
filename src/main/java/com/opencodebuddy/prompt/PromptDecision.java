package com.opencodebuddy.prompt;

/**
 * The user's answer to a prompt.
 *
 * <p>A decision always names the request it belongs to; the host verifies the
 * triple {@code (tabId, sessionId, requestId)} before it forwards the answer
 * to the daemon.</p>
 */
public final class PromptDecision {

    /** What the user chose. */
    public enum Choice {
        ALLOW,
        DENY
    }

    private final String requestId;
    private final Choice choice;
    private final boolean remember;

    public PromptDecision(String requestId, Choice choice, boolean remember) {
        this.requestId = requestId;
        this.choice = choice;
        this.remember = remember;
    }

    public String getRequestId() {
        return requestId;
    }

    public Choice getChoice() {
        return choice;
    }

    /** Should the decision stick for the rest of the session? */
    public boolean isRemember() {
        return remember;
    }

    public boolean isAllow() {
        return choice == Choice.ALLOW;
    }
}
