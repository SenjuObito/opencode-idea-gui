package com.opencodebuddy.settings;

import com.opencodebuddy.util.PlatformUtils;
import com.google.gson.JsonObject;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class CodemossSettingsServicePromptEnhancerConfigTest {
    private String originalHomeDir;

    @After
    public void tearDown() throws Exception {
        if (originalHomeDir != null) {
            setCachedHomeDirectory(originalHomeDir);
            originalHomeDir = null;
        }
    }

    @Test
    public void shouldDefaultPromptEnhancerToOpenCodeModel() throws Exception {
        Path tempHome = Files.createTempDirectory("prompt-enhancer-default-home");
        useTemporaryHomeDirectory(tempHome);

        CodemossSettingsService service = new CodemossSettingsService();
        JsonObject config = service.getPromptEnhancerConfig();

        assertTrue(config.get("provider").isJsonNull());
        assertNotNull(config.getAsJsonObject("models"));
        assertEquals("opencode-default", config.getAsJsonObject("models").get("opencode").getAsString());
        assertTrue(config.getAsJsonObject("availability").has("opencode"));
    }

    @Test
    public void shouldPersistManualPromptEnhancerProviderAndModel() throws Exception {
        Path tempHome = Files.createTempDirectory("prompt-enhancer-manual-home");
        useTemporaryHomeDirectory(tempHome);

        CodemossSettingsService service = new CodemossSettingsService();
        service.setPromptEnhancerConfig("opencode", "claude-3-7-sonnet");

        JsonObject config = service.getPromptEnhancerConfig();
        assertEquals("opencode", config.get("provider").getAsString());
        assertEquals("claude-3-7-sonnet", config.getAsJsonObject("models").get("opencode").getAsString());
    }

    private void useTemporaryHomeDirectory(Path tempHome) throws Exception {
        if (originalHomeDir == null) {
            originalHomeDir = getCachedHomeDirectory();
        }
        setCachedHomeDirectory(tempHome.toString());
        Files.createDirectories(tempHome.resolve(".opencodebuddy"));
    }

    private String getCachedHomeDirectory() throws Exception {
        Field field = PlatformUtils.class.getDeclaredField("cachedRealHomeDir");
        field.setAccessible(true);
        return (String) field.get(null);
    }

    private void setCachedHomeDirectory(String homeDir) throws Exception {
        Field field = PlatformUtils.class.getDeclaredField("cachedRealHomeDir");
        field.setAccessible(true);
        field.set(null, homeDir);
    }
}
