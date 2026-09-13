package com.opencodebuddy.handler;

import com.opencodebuddy.handler.core.BaseMessageHandler;
import com.opencodebuddy.handler.core.HandlerContext;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.Path;
import java.util.*;

/**
 * Manages opencode command markdown files ("prompts") in the official
 * opencode layout: global {@code ~/.config/opencode/command/*.md} and
 * project {@code <projectRoot>/.opencode/command/*.md}. Files carry YAML-ish
 * frontmatter (description / agent / model / subtask) followed by the prompt
 * template body; {@code $ARGUMENTS} is substituted by opencode at run time.
 */
public class CommandsHandler extends BaseMessageHandler {

    private static final Logger LOG = Logger.getInstance(CommandsHandler.class);
    private static final String[] SUPPORTED_TYPES = {
        "commands_list", "commands_read", "commands_save", "commands_delete"
    };
    private static final String GLOBAL_COMMAND_DIR = ".config/opencode/command";
    private static final String PROJECT_COMMAND_DIR = ".opencode/command";

    private final Gson gson = new Gson();

    public CommandsHandler(HandlerContext context) {
        super(context);
    }

    @Override
    public String[] getSupportedTypes() {
        return SUPPORTED_TYPES.clone();
    }

    @Override
    public boolean handle(String type, String content) {
        try {
            JsonObject payload = parse(content);
            switch (type) {
                case "commands_list" -> handleList(payload);
                case "commands_read" -> handleRead(payload);
                case "commands_save" -> handleSave(payload);
                case "commands_delete" -> handleDelete(payload);
                default -> { return false; }
            }
            return true;
        } catch (Exception e) {
            LOG.error("[CommandsHandler] Failed to handle " + type + ": " + e.getMessage(), e);
            sendError(e.getMessage());
            return true;
        }
    }

    private void handleList(JsonObject payload) throws IOException {
        String scope = scopeOf(payload);
        JsonObject result = baseResult(scope);
        JsonArray items = new JsonArray();
        List<File> dirs = commandDirs(scope);
        Set<String> seenNames = new HashSet<>();
        File primaryDir = commandDir(scope);

        for (File dir : dirs) {
            if (!dir.exists()) {
                continue;
            }
            File[] files = dir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".md"));
            if (files != null) {
                Arrays.sort(files, Comparator.comparing(File::getName));
                for (File f : files) {
                    String name = f.getName().substring(0, f.getName().length() - 3);
                    if (seenNames.add(name)) {
                        JsonObject item = new JsonObject();
                        item.addProperty("name", name);
                        item.addProperty("fileName", f.getName());
                        item.addProperty("size", f.length());
                        item.addProperty("lastModified", f.lastModified());
                        String content = Files.readString(f.toPath(), StandardCharsets.UTF_8);
                        item.addProperty("description", extractFrontmatterField(content, "description"));
                        item.addProperty("agent", extractFrontmatterField(content, "agent"));
                        item.addProperty("model", extractFrontmatterField(content, "model"));
                        items.add(item);
                    }
                }
            }
        }
        result.add("commands", items);
        result.addProperty("dir", primaryDir.getAbsolutePath());
        result.addProperty("exists", primaryDir.exists());
        callJavaScript("window.onCommandsList", gson.toJson(result));
    }

    private void handleRead(JsonObject payload) throws IOException {
        String scope = scopeOf(payload);
        String name = safeName(payload);
        File file = commandFile(scope, name);
        JsonObject result = baseResult(scope);
        result.addProperty("name", name);
        if (file != null && file.exists()) {
            result.addProperty("content", Files.readString(file.toPath(), StandardCharsets.UTF_8));
            result.addProperty("exists", true);
        } else {
            result.addProperty("content", defaultTemplate(name));
            result.addProperty("exists", false);
        }
        callJavaScript("window.onCommandsRead", gson.toJson(result));
    }

    private void handleSave(JsonObject payload) throws IOException {
        String scope = scopeOf(payload);
        String name = safeName(payload);
        String content = payload.has("content") && !payload.get("content").isJsonNull()
                ? payload.get("content").getAsString() : "";
        File dir = commandDir(scope);
        Files.createDirectories(dir.toPath());
        String original = payload.has("originalName") && !payload.get("originalName").isJsonNull()
                ? safeName(payload.get("originalName").getAsString()) : null;
        File target = new File(dir, name + ".md");
        Files.writeString(target.toPath(), content, StandardCharsets.UTF_8);
        if (original != null && !original.equals(name)) {
            for (File d : commandDirs(scope)) {
                File oldFile = new File(d, original + ".md");
                if (oldFile.exists()) {
                    Files.deleteIfExists(oldFile.toPath());
                }
            }
        }
        JsonObject result = baseResult(scope);
        result.addProperty("name", name);
        result.addProperty("path", target.getAbsolutePath());
        callJavaScript("window.onCommandsSaved", gson.toJson(result));
    }

    private void handleDelete(JsonObject payload) throws IOException {
        String scope = scopeOf(payload);
        String name = safeName(payload);
        boolean deleted = false;
        for (File dir : commandDirs(scope)) {
            File file = new File(dir, name + ".md");
            if (file.exists() && Files.deleteIfExists(file.toPath())) {
                deleted = true;
            }
        }
        JsonObject result = baseResult(scope);
        result.addProperty("name", name);
        result.addProperty("deleted", deleted);
        callJavaScript("window.onCommandsDeleted", gson.toJson(result));
    }

    // ===== helpers =====

    private List<File> commandDirs(String scope) {
        List<File> list = new ArrayList<>();
        if ("project".equals(scope)) {
            String projectPath = context.getProject() != null ? context.getProject().getBasePath() : null;
            if (projectPath != null) {
                list.add(Path.of(projectPath).resolve(".opencode/command").toFile());
                list.add(Path.of(projectPath).resolve(".opencode/commands").toFile());
                list.add(Path.of(projectPath).resolve(".claude/commands").toFile());
            }
        } else {
            String home = com.opencodebuddy.util.PlatformUtils.getHomeDirectory();
            list.add(Paths.get(home).resolve(".config/opencode/command").toFile());
            list.add(Paths.get(home).resolve(".config/opencode/commands").toFile());
            list.add(Paths.get(home).resolve(".opencode/command").toFile());
            list.add(Paths.get(home).resolve(".opencode/commands").toFile());
            list.add(Paths.get(home).resolve(".claude/commands").toFile());
        }
        return list;
    }

    private File commandDir(String scope) {
        List<File> dirs = commandDirs(scope);
        return dirs.isEmpty() ? Paths.get(com.opencodebuddy.util.PlatformUtils.getHomeDirectory()).resolve(GLOBAL_COMMAND_DIR).toFile() : dirs.get(0);
    }

    private File commandFile(String scope, String name) {
        for (File dir : commandDirs(scope)) {
            File f = new File(dir, name + ".md");
            if (f.exists()) {
                try {
                    if (f.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator)) {
                        return f;
                    }
                } catch (IOException ignored) {}
            }
        }
        File defaultDir = commandDir(scope);
        File f = new File(defaultDir, name + ".md");
        try {
            if (!f.getCanonicalPath().startsWith(defaultDir.getCanonicalPath() + File.separator)) {
                return null;
            }
        } catch (IOException e) {
            return null;
        }
        return f;
    }

    private static String extractFrontmatterField(String content, String field) {
        if (content == null) {
            return "";
        }
        String[] lines = content.split("\n", -1);
        boolean inFrontmatter = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (i == 0 && "---".equals(line)) {
                inFrontmatter = true;
                continue;
            }
            if (inFrontmatter && "---".equals(line)) {
                break;
            }
            if (inFrontmatter && line.startsWith(field + ":")) {
                return line.substring(field.length() + 1).trim().replaceFirst("^\"|\"$", "");
            }
        }
        return "";
    }

    private static String defaultTemplate(String name) {
        return "---\ndescription: " + name + "\nagent: build\n---\n\n$ARGUMENTS\n";
    }

    private static JsonObject baseResult(String scope) {
        JsonObject result = new JsonObject();
        result.addProperty("scope", scope);
        result.addProperty("success", true);
        return result;
    }

    private static String scopeOf(JsonObject payload) {
        return payload.has("scope") && !payload.get("scope").isJsonNull()
                ? payload.get("scope").getAsString() : "global";
    }

    private static String safeName(JsonObject payload) {
        return safeName(payload.has("name") && !payload.get("name").isJsonNull()
                ? payload.get("name").getAsString() : "");
    }

    private static String safeName(String name) {
        String trimmed = name.trim();
        if (trimmed.isEmpty() || trimmed.contains("/") || trimmed.contains("\\")
                || trimmed.contains("..") || trimmed.startsWith(".")) {
            throw new IllegalArgumentException("Invalid command name: " + name);
        }
        return trimmed;
    }

    private JsonObject parse(String content) {
        if (content == null || content.isBlank()) {
            return new JsonObject();
        }
        try {
            return gson.fromJson(content, JsonObject.class);
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    private void sendError(String message) {
        callJavaScript("window.showError", com.opencodebuddy.util.JsUtils.escapeJs(
                message != null ? message : "commands operation failed"));
    }
}
