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

public class OpenCodeBuddySettingsServiceSendShortcutAndSanitizeTest {
    private String originalHomeDir;

    @After
    public void tearDown() throws Exception {
        if (originalHomeDir != null) {
            setCachedHomeDirectory(originalHomeDir);
            originalHomeDir = null;
        }
    }

    @Test
    public void shouldDefaultSendShortcutToEnterAndPersist() throws Exception {
        Path tempHome = Files.createTempDirectory("send-shortcut-test-home");
        useTemporaryHomeDirectory(tempHome);

        OpenCodeBuddySettingsService service = new OpenCodeBuddySettingsService();
        assertEquals("enter", service.getSendShortcut());

        service.setSendShortcut("cmdEnter");
        assertEquals("cmdEnter", service.getSendShortcut());

        // Reload from disk
        OpenCodeBuddySettingsService reloaded = new OpenCodeBuddySettingsService();
        assertEquals("cmdEnter", reloaded.getSendShortcut());
    }

    @Test
    public void shouldSanitizeDeprecatedConfigKeysOnReadAndWrite() throws Exception {
        Path tempHome = Files.createTempDirectory("sanitize-test-home");
        useTemporaryHomeDirectory(tempHome);

        OpenCodeBuddySettingsService service = new OpenCodeBuddySettingsService();

        JsonObject dirtyConfig = new JsonObject();
        dirtyConfig.addProperty("version", 2);
        dirtyConfig.add("claude", new JsonObject());
        dirtyConfig.add("codex", new JsonObject());
        dirtyConfig.add("commitAi", new JsonObject());
        dirtyConfig.addProperty("commitPrompt", "old prompt");
        dirtyConfig.add("projectCommitPrompt", new JsonObject());
        dirtyConfig.addProperty("commitGenerationEnabled", true);
        dirtyConfig.add("promptEnhancer", new JsonObject());
        dirtyConfig.add("prompts", new JsonObject());
        dirtyConfig.add("agents", new JsonObject());
        dirtyConfig.addProperty("selectedAgentId", "agent-1");
        dirtyConfig.add("mcpServers", new JsonObject());
        dirtyConfig.addProperty("opencodeCliPath", "/bin/opencode");

        service.writeConfig(dirtyConfig);

        JsonObject cleanConfig = service.readConfig();
        assertFalse(cleanConfig.has("claude"));
        assertFalse(cleanConfig.has("codex"));
        assertFalse(cleanConfig.has("commitAi"));
        assertFalse(cleanConfig.has("commitPrompt"));
        assertFalse(cleanConfig.has("projectCommitPrompt"));
        assertFalse(cleanConfig.has("commitGenerationEnabled"));
        assertFalse(cleanConfig.has("promptEnhancer"));
        assertFalse(cleanConfig.has("prompts"));
        assertFalse(cleanConfig.has("agents"));
        assertFalse(cleanConfig.has("selectedAgentId"));
        assertFalse(cleanConfig.has("mcpServers"));
        assertEquals("/bin/opencode", cleanConfig.get("opencodeCliPath").getAsString());
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
