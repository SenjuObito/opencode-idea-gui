package com.opencodebuddy.handler;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CommandsHandlerTest {

    @Test
    public void projectListReadsCommandsFromTheTabsCwd() throws Exception {
        Path root = Files.createTempDirectory("commands-handler");
        try {
            Path dir = root.resolve(".opencode").resolve("command");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("review.md"),
                    "---\ndescription: Review changes\nagent: build\n---\n\n$ARGUMENTS\n",
                    StandardCharsets.UTF_8);

            JsonObject result = CommandsHandler.buildList("project", root.toFile());

            JsonArray commands = result.getAsJsonArray("commands");
            assertEquals(1, commands.size());
            JsonObject command = commands.get(0).getAsJsonObject();
            assertEquals("review", command.get("name").getAsString());
            assertEquals("Review changes", command.get("description").getAsString());
            assertEquals("build", command.get("agent").getAsString());
            assertTrue(result.get("dir").getAsString().endsWith(".opencode" + java.io.File.separator + "command"));
        } finally {
            try (var paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }
}
