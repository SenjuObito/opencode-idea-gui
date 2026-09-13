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

public class CodemossSettingsServiceCommitAiConfigTest {
    private String originalHomeDir;

    @After
    public void tearDown() throws Exception {
        if (originalHomeDir != null) {
            setCachedHomeDirectory(originalHomeDir);
            originalHomeDir = null;
        }
    }

    @Test
    public void shouldDefaultCommitAiToOpenCodeModel() throws Exception {
        Path tempHome = Files.createTempDirectory("commit-ai-default-home");
        useTemporaryHomeDirectory(tempHome);

        CodemossSettingsService service = new CodemossSettingsService();
        JsonObject config = service.getCommitAiConfig();

        assertTrue(config.get("provider").isJsonNull());
        assertNotNull(config.getAsJsonObject("models"));
        assertEquals("opencode-default", config.getAsJsonObject("models").get("opencode").getAsString());
        assertTrue(config.getAsJsonObject("availability").has("opencode"));
    }

    @Test
    public void shouldPersistManualCommitAiProviderAndModel() throws Exception {
        Path tempHome = Files.createTempDirectory("commit-ai-manual-home");
        useTemporaryHomeDirectory(tempHome);

        CodemossSettingsService service = new CodemossSettingsService();
        service.setCommitAiConfig("opencode", "gpt-4o");

        JsonObject config = service.getCommitAiConfig();
        assertEquals("opencode", config.get("provider").getAsString());
        assertEquals("gpt-4o", config.getAsJsonObject("models").get("opencode").getAsString());
    }

    @Test
    public void shouldNotMutatePromptEnhancerConfigWhenSavingCommitAiConfig() throws Exception {
        Path tempHome = Files.createTempDirectory("commit-ai-isolated-home");
        useTemporaryHomeDirectory(tempHome);

        CodemossSettingsService service = new CodemossSettingsService();
        service.setPromptEnhancerConfig("opencode", "claude-3-7-sonnet");
        service.setCommitAiConfig("opencode", "gpt-4o");

        JsonObject promptEnhancerConfig = service.getPromptEnhancerConfig();
        JsonObject commitAiConfig = service.getCommitAiConfig();

        assertEquals("opencode", promptEnhancerConfig.get("provider").getAsString());
        assertEquals("claude-3-7-sonnet", promptEnhancerConfig.getAsJsonObject("models").get("opencode").getAsString());

        assertEquals("opencode", commitAiConfig.get("provider").getAsString());
        assertEquals("gpt-4o", commitAiConfig.getAsJsonObject("models").get("opencode").getAsString());
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
