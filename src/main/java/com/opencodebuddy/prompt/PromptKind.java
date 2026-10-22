package com.opencodebuddy.prompt;

/**
 * The kind of a pending prompt.
 *
 * <p>Permission, question and plan are one concept in the model: a request
 * that a tab owns, that waits for an answer, and that has to be rejected when
 * the tab goes away. The kind only decides how the answer is read.</p>
 */
public enum PromptKind {
    /** A tool call that needs a decision (allow / deny). */
    PERMISSION,
    /** A question the agent asked the user. */
    QUESTION,
    /** A plan the agent wants approved. */
    PLAN
}
