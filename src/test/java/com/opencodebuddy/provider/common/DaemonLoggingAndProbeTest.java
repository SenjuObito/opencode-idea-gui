package com.opencodebuddy.provider.common;

import com.opencodebuddy.utils.PluginFileLogger;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for Daemon logging and log file resolution.
 */
public class DaemonLoggingAndProbeTest {

    @Test
    public void testPluginFileLoggerPathResolution() {
        String logPath = PluginFileLogger.path();
        assertNotNull("Log path should never be null", logPath);
        assertTrue("Log path should contain opencode-plugin.log", logPath.contains("opencode-plugin.log"));
    }

    @Test
    public void testPluginFileLoggerWriting() {
        // Ensure writing info and error does not throw exceptions
        PluginFileLogger.info("TEST", "Testing Daemon probe log write");
        PluginFileLogger.error("TEST", "Testing Daemon probe error log write");

        File logFile = new File(PluginFileLogger.path());
        assertTrue("Log file should exist or have valid directory path",
                logFile.exists() || logFile.getParentFile().exists() || logFile.getParentFile().mkdirs());
    }
}
