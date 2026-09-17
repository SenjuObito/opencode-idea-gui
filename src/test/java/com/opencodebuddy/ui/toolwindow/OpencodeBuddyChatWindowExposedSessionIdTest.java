package com.opencodebuddy.ui.toolwindow;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Regression tests for the stale-session-ID fix (PR #1192).
 *
 * After session callbacks are re-bound (new session, history load), the ID
 * exposed via getSessionId() must never be the previous session's ID, and it
 * must never become null either: DetachTabAction skips DetachedWindowManager
 * registration on a null ID, and dispose-time PermissionService cleanup keys
 * off a stable identifier.
 */
public class OpencodeBuddyChatWindowExposedSessionIdTest {

    private static final String PERMISSION_KEY = "permission-key-uuid";

    @Test
    public void freshSessionFallsBackToPermissionServiceKey() {
        assertEquals(PERMISSION_KEY,
                OpencodeBuddyChatWindow.resolveExposedSessionId(null, PERMISSION_KEY));
        assertEquals(PERMISSION_KEY,
                OpencodeBuddyChatWindow.resolveExposedSessionId("", PERMISSION_KEY));
        assertEquals(PERMISSION_KEY,
                OpencodeBuddyChatWindow.resolveExposedSessionId("   ", PERMISSION_KEY));
    }

    @Test
    public void historyLoadedSessionExposesItsOwnId() {
        assertEquals("history-session-42",
                OpencodeBuddyChatWindow.resolveExposedSessionId("history-session-42", PERMISSION_KEY));
    }
}
