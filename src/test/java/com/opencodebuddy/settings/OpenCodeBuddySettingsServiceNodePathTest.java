package com.opencodebuddy.settings;

import com.opencodebuddy.util.PlatformUtils;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class OpenCodeBuddySettingsServiceNodePathTest {
    private String originalHomeDir;

    @After
    public void tearDown() throws Exception {
        if (originalHomeDir != null) {
            setCachedHomeDirectory(originalHomeDir);
            originalHomeDir = null;
        }
    }

    @Test
    public void shouldReturnNullWhenNodePathNotConfigured() throws Exception {
        Path tempHome = Files.createTempDirectory("node-path-default-home");
        useTemporaryHomeDirectory(tempHome);

        OpenCodeBuddySettingsService service = new OpenCodeBuddySettingsService();
        assertNull(service.getNodePath());
    }

    @Test
    public void shouldPersistAndReadNodePath() throws Exception {
        Path tempHome = Files.createTempDirectory("node-path-roundtrip-home");
        useTemporaryHomeDirectory(tempHome);

        OpenCodeBuddySettingsService service = new OpenCodeBuddySettingsService();
        String samplePath = "/custom/nodejs/bin/node";
        service.setNodePath(samplePath);

        assertEquals(samplePath, service.getNodePath());

        // Create new instance to ensure it reads back from config.json on disk
        OpenCodeBuddySettingsService newService = new OpenCodeBuddySettingsService();
        assertEquals(samplePath, newService.getNodePath());

        // Clear it
        newService.setNodePath(null);
        assertNull(newService.getNodePath());
    }

    private void useTemporaryHomeDirectory(Path tempHome) throws Exception {
        originalHomeDir = PlatformUtils.getHomeDirectory();
        setCachedHomeDirectory(tempHome.toString());
    }

    private void setCachedHomeDirectory(String path) throws Exception {
        Field homeDirField = PlatformUtils.class.getDeclaredField("cachedRealHomeDir");
        homeDirField.setAccessible(true);
        homeDirField.set(null, path);
    }
}
