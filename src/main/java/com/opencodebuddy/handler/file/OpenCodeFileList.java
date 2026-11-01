package com.opencodebuddy.handler.file;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.opencodebuddy.handler.core.HandlerContext;
import com.opencodebuddy.provider.opencode.OpenCodeSDKBridge;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The "@" file candidate source: opencode, and only opencode.
 *
 * <p>The plugin is a GUI for opencode, so it does not compute candidates. Two
 * server endpoints cover the whole interaction:</p>
 *
 * <ul>
 *   <li>{@code GET /api/fs/list?path=} — one directory level. Used when there is
 *       no search text: right after {@code @}, and for {@code @src/}.</li>
 *   <li>{@code GET /api/fs/find?query=} — server-side fuzzy search with the
 *       project's frecency ranking. Used once the user types.</li>
 * </ul>
 *
 * <p>The trap this class exists for: {@code fs.find} is a fuzzy search. An empty
 * query matches nothing and the server correctly answers {@code []} — it is not
 * a "default ranked set". Sending {@code ""} there is why typing {@code @}
 * showed no files at all.</p>
 *
 * <p>Both calls are scoped by the tab's cwd: core forwards it as
 * {@code x-opencode-directory}, which is the only parameter the server honours
 * (a {@code directory} query parameter is ignored).</p>
 */
final class OpenCodeFileList {

    private static final Logger LOG = Logger.getInstance(OpenCodeFileList.class);

    private OpenCodeFileList() {
    }

    /**
     * Fetch candidates for one "@" request.
     *
     * <p>Never completes exceptionally: a transport failure is an empty list
     * plus a log line, and the caller still answers the webview so the dropdown
     * does not hang until its timeout.</p>
     *
     * @param context     handler context (bridge access)
     * @param query       text after the last "/" (may be empty)
     * @param currentPath directory prefix the user navigated into (may be empty)
     * @param cwd         project directory of the requesting tab
     */
    static CompletableFuture<List<JsonObject>> fetch(
            HandlerContext context,
            String query,
            String currentPath,
            String cwd
    ) {
        String path = normalizeRelative(currentPath);
        String search = query != null ? query.trim() : "";
        String directory = cwd != null ? cwd : "";

        OpenCodeSDKBridge bridge = context != null ? context.getOpenCodeSDKBridge() : null;
        if (bridge == null) {
            LOG.warn("[OpenCodeFileList] SDK bridge unavailable; returning no candidates");
            return CompletableFuture.completedFuture(new ArrayList<>());
        }

        CompletableFuture<JsonElement> call;
        try {
            call = search.isEmpty()
                    ? bridge.listFiles(path, directory)
                    // Prefixing the directory makes the server's fuzzy match stay
                    // inside it, so `@src/ho` cannot surface `webview/src/host`.
                    : bridge.findFiles(path.isEmpty() ? search : path + "/" + search, directory);
        } catch (RuntimeException e) {
            LOG.warn("[OpenCodeFileList] Failed to start request: " + e.getMessage());
            return CompletableFuture.completedFuture(new ArrayList<>());
        }

        return call
                .thenApply(json -> toFileItems(json, directory))
                .exceptionally(e -> {
                    LOG.warn("[OpenCodeFileList] opencode fs." + (search.isEmpty() ? "list" : "find")
                            + " failed (cwd=" + directory + "): " + e.getMessage());
                    return new ArrayList<>();
                });
    }

    /**
     * Map the daemon payload {@code {entries:[{path,type}]}} to the webview's
     * {@code FileItem} shape {@code {name,path,absolutePath,type,extension}}.
     *
     * <p>{@code fs.list} returns Windows-style paths with a trailing separator
     * for directories ({@code src\host\}), so entries are normalised to POSIX
     * without the trailing slash before {@code basename}/{@code extension}.</p>
     */
    static List<JsonObject> toFileItems(JsonElement json, String basePath) {
        List<JsonObject> files = new ArrayList<>();
        JsonArray entries = null;
        if (json != null && json.isJsonObject()) {
            JsonObject obj = json.getAsJsonObject();
            if (obj.has("entries") && obj.get("entries").isJsonArray()) {
                entries = obj.getAsJsonArray("entries");
            }
        } else if (json != null && json.isJsonArray()) {
            entries = json.getAsJsonArray();
        }
        if (entries == null) {
            return files;
        }

        String base = normalizeBase(basePath);
        for (JsonElement element : entries) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject raw = element.getAsJsonObject();
            String relPath = raw.has("path") && !raw.get("path").isJsonNull()
                    ? raw.get("path").getAsString()
                    : "";
            relPath = normalizeEntryPath(relPath);
            if (relPath.isEmpty()) {
                continue;
            }
            boolean directory = raw.has("type")
                    && !raw.get("type").isJsonNull()
                    && "directory".equals(raw.get("type").getAsString());
            String name = baseName(relPath);
            String absolutePath = isAbsolutePath(relPath) ? relPath : joinPath(base, relPath);

            JsonObject item = new JsonObject();
            item.addProperty("name", name);
            item.addProperty("path", relPath);
            item.addProperty("absolutePath", absolutePath);
            item.addProperty("type", directory ? "directory" : "file");
            if (!directory) {
                int dot = name.lastIndexOf('.');
                if (dot > 0 && dot < name.length() - 1) {
                    item.addProperty("extension", name.substring(dot + 1));
                }
            }
            files.add(item);
        }
        return files;
    }

    /**
     * Normalise the directory prefix taken from the webview: {@code 'src/'} to
     * {@code 'src'}, {@code './src/'} to {@code 'src'}, {@code 'src\\host\\'}
     * to {@code 'src/host'}. Absolute paths and {@code ..} segments are rejected
     * (empty prefix = project root) so the webview cannot walk the filesystem.
     */
    static String normalizeRelative(String raw) {
        String value = raw != null ? raw.replace('\\', '/') : "";
        while (value.startsWith("./")) {
            value = value.substring(2);
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.isEmpty() || value.startsWith("/") || value.matches("^[A-Za-z]:.*")) {
            return "";
        }
        for (String segment : value.split("/")) {
            if ("..".equals(segment)) {
                return "";
            }
        }
        return value;
    }

    private static String normalizeEntryPath(String raw) {
        String value = raw != null ? raw.replace('\\', '/') : "";
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String normalizeBase(String basePath) {
        String base = basePath != null ? basePath.replace('\\', '/') : "";
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    private static String joinPath(String base, String relative) {
        if (base.isEmpty()) {
            return relative;
        }
        return relative.isEmpty() ? base : base + "/" + relative;
    }

    private static boolean isAbsolutePath(String path) {
        return path.startsWith("/") || path.matches("^[A-Za-z]:.*");
    }

    private static String baseName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
