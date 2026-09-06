package com.opencodebuddy;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class LegacyToolWindowCompatibilityTest {

    @Test
    public void legacyToolWindowClassRemainsAssignableToCurrentImplementation() {
        assertTrue(
            com.opencodebuddy.ui.toolwindow.ClaudeSDKToolWindow.class
                .isAssignableFrom(ClaudeSDKToolWindow.class)
        );
    }
}
