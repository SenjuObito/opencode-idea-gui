package com.opencodebuddy.cache;

import com.opencodebuddy.bridge.NodeDetector;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Plugin-side index of opencode sessions created through the daemon.
 *
 * <p>opencode does not expose a session-list command over the daemon, so the
 * plugin records a summary entry (id, title, model, cwd, message count,
 * timestamps) every time a turn completes. The history view is rendered from
 * this index; entries are keyed by sessionId and filtered by project cwd.</p>
 */
public final class OpenCodeSessionIndex {

    private static final Logger LOG = Logger.getInstance(OpenCodeSessionIndex.class);
    private static final String INDEX_FILE_NAME = "opencode-session-index.json";
    private static final int MAX_SESSIONS = 500;

    private static final OpenCodeSessionIndex INSTANCE = new OpenCodeSessionIndex();

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path indexFile;
    private final Object fileLock = new Object();

    private OpenCodeSessionIndex() {
        this(defaultIndexFile());
    }

    OpenCodeSessionIndex(Path indexFile) {
        this.indexFile = indexFile;
    }

    public static OpenCodeSessionIndex getInstance() {
        return INSTANCE;
    }

    private static Path defaultIndexFile() {
        return Paths.get(NodeDetector.resolveHomeForFileOps(), ".codemoss", "cache", INDEX_FILE_NAME);
    }

    /**
     * Insert or update a session summary entry.
     */
    public void upsert(String sessionId, String title, String model, String cwd,
                       long firstTimestamp, long lastTimestamp, int messageCount) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        synchronized (fileLock) {
            JsonArray sessions = readSessions();
            JsonObject entry = findEntry(sessions, sessionId);
            if (entry == null) {
                entry = new JsonObject();
                entry.addProperty("sessionId", sessionId);
                sessions.add(entry);
                trimToSize(sessions);
            }
            if (title != null && !title.isBlank()) {
                entry.addProperty("title", title);
            }
            if (getTitle(entry).isBlank()) {
                entry.addProperty("title", "Untitled session");
            }
            if (model != null && !model.isBlank()) {
                entry.addProperty("model", model);
            }
            if (cwd != null && !cwd.isBlank()) {
                entry.addProperty("cwd", cwd);
            }
            if (firstTimestamp > 0) {
                entry.addProperty("firstTimestamp", firstTimestamp);
            }
            if (lastTimestamp > 0) {
                entry.addProperty("lastTimestamp", lastTimestamp);
            }
            if (messageCount >= 0) {
                entry.addProperty("messageCount", messageCount);
            }
            writeSessions(sessions);
        }
    }

    /**
     * Remove a session entry (e.g. when the session was deleted).
     */
    public void remove(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        synchronized (fileLock) {
            JsonArray sessions = readSessions();
            boolean changed = false;
            Iterator<JsonElement> it = sessions.iterator();
            while (it.hasNext()) {
                JsonElement element = it.next();
                if (element.isJsonObject()
                        && sessionId.equals(element.getAsJsonObject().get("sessionId") == null
                                ? null : element.getAsJsonObject().get("sessionId").getAsString())) {
                    it.remove();
                    changed = true;
                }
            }
            if (changed) {
                writeSessions(sessions);
            }
        }
    }

    /**
     * List session entries for a project working directory (all entries when
     * cwd is null/blank).
     */
    public List<JsonObject> sessionsForProject(String cwd) {
        synchronized (fileLock) {
            JsonArray sessions = readSessions();
            List<JsonObject> result = new ArrayList<>(sessions.size());
            for (JsonElement element : sessions) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject entry = element.getAsJsonObject();
                if (cwd == null || cwd.isBlank()) {
                    result.add(entry.deepCopy());
                    continue;
                }
                String entryCwd = entry.has("cwd") && !entry.get("cwd").isJsonNull()
                        ? entry.get("cwd").getAsString() : null;
                if (normalizeCwd(cwd).equals(normalizeCwd(entryCwd))) {
                    result.add(entry.deepCopy());
                }
            }
            return result;
        }
    }

    private static String normalizeCwd(String cwd) {
        return cwd == null ? "" : cwd.replace('\\', '/').trim();
    }

    private static void trimToSize(JsonArray sessions) {
        while (sessions.size() > MAX_SESSIONS) {
            sessions.remove(0);
        }
    }

    private static JsonObject findEntry(JsonArray sessions, String sessionId) {
        for (JsonElement element : sessions) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            if (entry.has("sessionId") && !entry.get("sessionId").isJsonNull()
                    && sessionId.equals(entry.get("sessionId").getAsString())) {
                return entry;
            }
        }
        return null;
    }

    private static String getTitle(JsonObject entry) {
        return entry.has("title") && !entry.get("title").isJsonNull()
                ? entry.get("title").getAsString() : "";
    }

    private JsonArray readSessions() {
        try {
            if (!Files.exists(indexFile)) {
                return new JsonArray();
            }
            String content = Files.readString(indexFile, StandardCharsets.UTF_8);
            if (content.isBlank()) {
                return new JsonArray();
            }
            JsonElement parsed = JsonParser.parseString(content);
            if (parsed.isJsonObject() && parsed.getAsJsonObject().has("sessions")) {
                return parsed.getAsJsonObject().getAsJsonArray("sessions");
            }
            if (parsed.isJsonArray()) {
                return parsed.getAsJsonArray();
            }
            return new JsonArray();
        } catch (Exception e) {
            LOG.warn("[OpenCodeSessionIndex] Failed to read index: " + e.getMessage());
            return new JsonArray();
        }
    }

    private void writeSessions(JsonArray sessions) {
        try {
            Path parent = indexFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            root.add("sessions", sessions);
            Path tmp = indexFile.resolveSibling(indexFile.getFileName() + ".tmp");
            Files.writeString(tmp, gson.toJson(root), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, indexFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, indexFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOG.warn("[OpenCodeSessionIndex] Failed to write index: " + e.getMessage());
        }
    }
}
