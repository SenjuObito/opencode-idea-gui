package com.opencodebuddy.settings;

import com.opencodebuddy.handler.OpenCodeCliPathHandler;
import com.opencodebuddy.util.PlatformUtils;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class OpenCodeBuddySettingsServiceOpencodeCliPathTest {
    private String originalHomeDir;

    @After
    public void tearDown() throws Exception {
        if (originalHomeDir != null) {
            setCachedHomeDirectory(originalHomeDir);
            originalHomeDir = null;
        }
    }

    @Test
    public void shouldReturnNullWhenOpencodeCliPathNotConfigured() throws Exception {
        Path tempHome = Files.createTempDirectory("opencode-cli-path-default-home");
        useTemporaryHomeDirectory(tempHome);

        OpenCodeBuddySettingsService service = new OpenCodeBuddySettingsService();
        assertNull(service.getOpencodeCliPath());
    }

    @Test
    public void shouldPersistAndReadOpencodeCliPath() throws Exception {
        Path tempHome = Files.createTempDirectory("opencode-cli-path-roundtrip-home");
        useTemporaryHomeDirectory(tempHome);

        OpenCodeBuddySettingsService service = new OpenCodeBuddySettingsService();
        String samplePath = "/custom/path/to/opencode";
        service.setOpencodeCliPath(samplePath);

        assertEquals(samplePath, service.getOpencodeCliPath());

        // Create new instance to ensure it reads back from config.json on disk
        OpenCodeBuddySettingsService newService = new OpenCodeBuddySettingsService();
        assertEquals(samplePath, newService.getOpencodeCliPath());

        // Clear it
        newService.setOpencodeCliPath(null);
        assertNull(newService.getOpencodeCliPath());
    }

    @Test
    public void shouldResolveValidCliPath() throws Exception {
        Path tempHome = Files.createTempDirectory("opencode-cli-home-expand");
        File fakeBin = new File(tempHome.toFile(), "fake-opencode.exe");
        assertTrue(fakeBin.createNewFile());

        try {
            Method resolveMethod = OpenCodeCliPathHandler.class.getDeclaredMethod("resolveValidCliPath", String.class);
            resolveMethod.setAccessible(true);

            String resolved = (String) resolveMethod.invoke(null, fakeBin.getAbsolutePath());
            assertNotNull(resolved);
            assertEquals(fakeBin.getCanonicalPath(), new File(resolved).getCanonicalPath());

            String notFound = (String) resolveMethod.invoke(null, new File(tempHome.toFile(), "non-existent-binary").getAbsolutePath());
            assertNull(notFound);
        } finally {
            fakeBin.delete();
        }
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
