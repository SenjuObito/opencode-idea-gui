package com.opencodebuddy;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class LegacyToolWindowCompatibilityTest {

    @Test
    public void legacyToolWindowClassRemainsAssignableToCurrentImplementation() {
        assertTrue(
            com.opencodebuddy.ui.toolwindow.OpencodeBuddyToolWindow.class
                .isAssignableFrom(ClaudeSDKToolWindow.class)
        );
    }
}
