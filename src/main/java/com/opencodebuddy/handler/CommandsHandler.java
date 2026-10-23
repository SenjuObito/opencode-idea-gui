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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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
        "commands.list", "commands.read", "commands.save", "commands.delete"
    };
    private static final String GLOBAL_COMMAND_DIR = ".config/opencode/command";
    private static final String PROJECT_COMMAND_DIR = ".opencode/command";

    private final Gson gson = new Gson();
    /** Lists are cheap to re-read but not free; two seconds keeps a tab switch instant. */
    private static final long LIST_CACHE_TTL_MS = 2_000;
    private final Map<String, CachedList> listCache = new ConcurrentHashMap<>();

    private static final class CachedList {
        final String json;
        final long at;

        CachedList(String json, long at) {
            this.json = json;
            this.at = at;
        }
    }

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
                case "commands.list" -> handleList(payload);
                case "commands.read" -> handleRead(payload);
                case "commands.save" -> handleSave(payload);
                case "commands.delete" -> handleDelete(payload);
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
        File root = projectRoot(payload);
        String key = scope + "|" + (root != null ? root.getAbsolutePath() : "");
        long now = System.currentTimeMillis();
        CachedList cached = listCache.get(key);
        String json;
        if (cached != null && now - cached.at <= LIST_CACHE_TTL_MS) {
            json = cached.json;
        } else {
            json = gson.toJson(buildList(scope, root));
            listCache.put(key, new CachedList(json, now));
        }
        context.publishEvent(com.opencodebuddy.tab.TabEvent.of("commands.listed").field("data", json).build());
    }

    /**
     * Read the commands of one scope from one project root.
     *
     * <p>Package-private and root-parameterised so the listing can be tested
     * without a project, and so a tab's own cwd (not always the project root)
     * decides where project commands live.</p>
     */
    static JsonObject buildList(String scope, File root) throws IOException {
        JsonObject result = baseResult(scope);
        JsonArray items = new JsonArray();
        List<File> dirs = commandDirs(scope, root);
        Set<String> seenNames = new HashSet<>();
        File primaryDir = dirs.isEmpty() ? null : dirs.get(0);

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
        result.addProperty("dir", primaryDir != null ? primaryDir.getAbsolutePath() : "");
        result.addProperty("exists", primaryDir != null && primaryDir.exists());
        return result;
    }

    private void handleRead(JsonObject payload) throws IOException {
        String scope = scopeOf(payload);
        String name = safeName(payload);
        File root = projectRoot(payload);
        File file = commandFile(scope, name, root);
        JsonObject result = baseResult(scope);
        result.addProperty("name", name);
        if (file != null && file.exists()) {
            result.addProperty("content", Files.readString(file.toPath(), StandardCharsets.UTF_8));
            result.addProperty("exists", true);
        } else {
            result.addProperty("content", defaultTemplate(name));
            result.addProperty("exists", false);
        }
        context.publishEvent(com.opencodebuddy.tab.TabEvent.of("commands.detail").field("data", gson.toJson(result)).build());
    }

    private void handleSave(JsonObject payload) throws IOException {
        String scope = scopeOf(payload);
        String name = safeName(payload);
        String content = payload.has("content") && !payload.get("content").isJsonNull()
                ? payload.get("content").getAsString() : "";
        File root = projectRoot(payload);
        File dir = commandDir(scope, root);
        Files.createDirectories(dir.toPath());
        String original = payload.has("originalName") && !payload.get("originalName").isJsonNull()
                ? safeName(payload.get("originalName").getAsString()) : null;
        File target = new File(dir, name + ".md");
        Files.writeString(target.toPath(), content, StandardCharsets.UTF_8);
        if (original != null && !original.equals(name)) {
            for (File d : commandDirs(scope, root)) {
                File oldFile = new File(d, original + ".md");
                if (oldFile.exists()) {
                    Files.deleteIfExists(oldFile.toPath());
                }
            }
        }
        listCache.clear();
        JsonObject result = baseResult(scope);
        result.addProperty("name", name);
        result.addProperty("path", target.getAbsolutePath());
        context.publishEvent(com.opencodebuddy.tab.TabEvent.of("commands.saved").field("data", gson.toJson(result)).build());
    }

    private void handleDelete(JsonObject payload) throws IOException {
        String scope = scopeOf(payload);
        String name = safeName(payload);
        File root = projectRoot(payload);
        boolean deleted = false;
        for (File dir : commandDirs(scope, root)) {
            File file = new File(dir, name + ".md");
            if (file.exists() && Files.deleteIfExists(file.toPath())) {
                deleted = true;
            }
        }
        listCache.clear();
        JsonObject result = baseResult(scope);
        result.addProperty("name", name);
        result.addProperty("deleted", deleted);
        context.publishEvent(com.opencodebuddy.tab.TabEvent.of("commands.deleted").field("data", gson.toJson(result)).build());
    }

    // ===== helpers =====

    /**
     * The cwd of the tab that issued the command, falling back to the project.
     * A tab may be pointed at a subdirectory of the project, and its project
     * commands live next to that subdirectory, not next to the project root.
     */
    private File projectRoot(JsonObject payload) {
        String tabId = payload.has("tabId") && !payload.get("tabId").isJsonNull()
                ? payload.get("tabId").getAsString() : null;
        if (tabId != null && !tabId.isBlank()) {
            com.opencodebuddy.session.OpencodeSession session = context.getSessionForTab(tabId);
            if (session != null && session.getCwd() != null && !session.getCwd().isBlank()) {
                return new File(session.getCwd());
            }
        }
        String base = context.getProject() != null ? context.getProject().getBasePath() : null;
        return base != null && !base.isBlank() ? new File(base) : null;
    }

    private static List<File> commandDirs(String scope, File root) {
        List<File> list = new ArrayList<>();
        if ("project".equals(scope)) {
            if (root != null) {
                list.add(root.toPath().resolve(".opencode/command").toFile());
                list.add(root.toPath().resolve(".opencode/commands").toFile());
                list.add(root.toPath().resolve(".claude/commands").toFile());
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

    private static File commandDir(String scope, File root) {
        List<File> dirs = commandDirs(scope, root);
        if (!dirs.isEmpty()) {
            return dirs.get(0);
        }
        return Paths.get(com.opencodebuddy.util.PlatformUtils.getHomeDirectory())
                .resolve(GLOBAL_COMMAND_DIR).toFile();
    }

    private static File commandFile(String scope, String name, File root) {
        for (File dir : commandDirs(scope, root)) {
            File f = new File(dir, name + ".md");
            if (f.exists()) {
                try {
                    if (f.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator)) {
                        return f;
                    }
                } catch (IOException ignored) {}
            }
        }
        File defaultDir = commandDir(scope, root);
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
