package com.opencodebuddy.session;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SessionTitlesTest {

    @Test
    public void recognisesEnginePlaceholders() {
        assertTrue(SessionTitles.isEnginePlaceholder("New session - 2026-10-27T09:20:28.850Z"));
        assertTrue(SessionTitles.isEnginePlaceholder("Child session - 2026-10-27T09:20:28.850Z"));
        assertTrue(SessionTitles.isEnginePlaceholder("  New session - 2026-10-27T09:20:28.850Z  "));
    }

    @Test
    public void keepsRealTitles() {
        assertFalse(SessionTitles.isEnginePlaceholder("Greeting"));
        assertFalse(SessionTitles.isEnginePlaceholder("会话标题未更新排查"));
        assertFalse(SessionTitles.isEnginePlaceholder(null));
        assertFalse(SessionTitles.isEnginePlaceholder(""));
        // Only the exact ISO shape is a placeholder; a near miss is a real name.
        assertFalse(SessionTitles.isEnginePlaceholder("New session - 2026-10-27"));
        assertFalse(SessionTitles.isEnginePlaceholder("New Session - 2026-10-27T09:20:28.850Z"));
    }
}
