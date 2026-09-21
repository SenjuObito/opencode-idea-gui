package com.opencodebuddy.settings;

import com.opencodebuddy.util.PlatformUtils;
import com.google.gson.JsonObject;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OpenCodeBuddySettingsServiceUiPreferencesTest {
    private String originalHomeDir;

    @After
    public void tearDown() throws Exception {
        if (originalHomeDir != null) {
            setCachedHomeDirectory(originalHomeDir);
            originalHomeDir = null;
        }
    }

    @Test
    public void shouldReturnEmptyJsonObjectWhenNoUiPreferencesExist() throws Exception {
        Path tempHome = Files.createTempDirectory("ui-prefs-default-home");
        useTemporaryHomeDirectory(tempHome);

        OpenCodeBuddySettingsService service = new OpenCodeBuddySettingsService();
        JsonObject prefs = service.getUiPreferences();

        assertEquals(0, prefs.entrySet().size());
    }

    @Test
    public void shouldPersistAndMergeUiPreferences() throws Exception {
        Path tempHome = Files.createTempDirectory("ui-prefs-roundtrip-home");
        useTemporaryHomeDirectory(tempHome);

        OpenCodeBuddySettingsService service = new OpenCodeBuddySettingsService();

        // 1. Set skipCompactConfirm = true
        JsonObject patch1 = new JsonObject();
        patch1.addProperty("skipCompactConfirm", true);
        patch1.addProperty("theme", "dark");
        service.setUiPreferences(patch1);

        JsonObject saved1 = service.getUiPreferences();
        assertTrue(saved1.get("skipCompactConfirm").getAsBoolean());
        assertEquals("dark", saved1.get("theme").getAsString());

        // 2. Partial patch: skipCompactConfirm = false, keep theme = dark
        JsonObject patch2 = new JsonObject();
        patch2.addProperty("skipCompactConfirm", false);
        patch2.addProperty("diffTheme", "follow");
        service.setUiPreferences(patch2);

        JsonObject saved2 = service.getUiPreferences();
        assertFalse(saved2.get("skipCompactConfirm").getAsBoolean());
        assertEquals("dark", saved2.get("theme").getAsString());
        assertEquals("follow", saved2.get("diffTheme").getAsString());
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
