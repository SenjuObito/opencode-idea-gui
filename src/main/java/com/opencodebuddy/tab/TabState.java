package com.opencodebuddy.tab;

/**
 * Lifecycle of one webview tab.
 *
 * <pre>
 * NEW -> CREATING -> BOUND -> STREAMING -> BOUND
 *  |        |          |
 *  |        +-> NEW    +-> LOADING_HISTORY -> BOUND
 *  +-> LOADING_HISTORY
 * any -> CLOSED
 * </pre>
 */
public enum TabState {
    /** No opencode session exists yet. This is a valid, non-error state. */
    NEW,
    /** First send is in flight; the canonical session id has not arrived yet. */
    CREATING,
    /** A canonical session is bound. */
    BOUND,
    /** History is being loaded into a tab that had no active turn. */
    LOADING_HISTORY,
    /** A turn is streaming for this tab. */
    STREAMING,
    /** Closed; all events must be rejected. */
    CLOSED
}
