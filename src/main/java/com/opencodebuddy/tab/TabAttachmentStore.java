package com.opencodebuddy.tab;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The attachments a tab staged.
 *
 * <p>An attachment belongs to the tab that picked it: it is written into that
 * tab's own directory, it is only readable by that tab, and it disappears with
 * the tab. Two tabs can therefore hold the same file name without colliding,
 * and closing one tab never takes the other tab's attachments with it.</p>
 *
 * <p>The daemon protocol still carries the bytes inline, so the store's job is
 * ownership and lifetime: the bytes live in one place per tab, are read back
 * when the message is sent, and are deleted when the tab goes away.</p>
 */
public final class TabAttachmentStore {

    /** Refuse a single attachment larger than this (32 MiB of raw bytes). */
    public static final long MAX_ATTACHMENT_BYTES = 32L * 1024L * 1024L;

    /** One staged attachment. */
    public record Staged(String attachmentId, String fileName, String mediaType, Path path, long size) {
    }

    private final Path root;
    private final Map<String, Map<String, Staged>> byTab = new ConcurrentHashMap<>();

    public TabAttachmentStore(Path root) {
        this.root = root;
    }

    /**
     * Stage an attachment for a tab.
     *
     * <p>The bytes are decoded and written once; the send path reads them back
     * from there instead of carrying them through the bridge again.</p>
     *
     * @return the staged attachment, or {@code null} when the payload cannot
     *         be stored (too large, not base64, no disk)
     */
    public Staged stage(String tabId, String attachmentId, String fileName, String mediaType, String base64Data) {
        if (tabId == null || tabId.isBlank() || attachmentId == null || attachmentId.isBlank()) {
            return null;
        }
        if (base64Data == null || base64Data.isBlank()) {
            return null;
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Data);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (bytes.length > MAX_ATTACHMENT_BYTES) {
            return null;
        }
        try {
            Path tabDir = root.resolve(sanitize(tabId));
            Files.createDirectories(tabDir);
            Path file = tabDir.resolve(sanitize(attachmentId) + "-" + sanitize(fileName));
            Files.write(file, bytes);
            Staged staged = new Staged(attachmentId, fileName, mediaType, file, bytes.length);
            byTab.computeIfAbsent(tabId, key -> new LinkedHashMap<>()).put(attachmentId, staged);
            return staged;
        } catch (IOException e) {
            return null;
        }
    }

    /** The staged attachment of a tab, or {@code null}. */
    public Staged get(String tabId, String attachmentId) {
        Map<String, Staged> tab = byTab.get(tabId);
        return tab != null ? tab.get(attachmentId) : null;
    }

    /** Every staged attachment of a tab. */
    public List<Staged> list(String tabId) {
        Map<String, Staged> tab = byTab.get(tabId);
        return tab != null ? new ArrayList<>(tab.values()) : List.of();
    }

    /**
     * Read the bytes of a staged attachment back.
     *
     * @return the base64 payload, or {@code null} when the file is gone
     */
    public String readData(String tabId, String attachmentId) {
        Staged staged = get(tabId, attachmentId);
        if (staged == null) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(staged.path());
            return Base64.getEncoder().encodeToString(bytes);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Drop everything a tab staged and delete its files.
     *
     * <p>Called when the tab closes: the bytes must not outlive the tab that
     * owns them.</p>
     *
     * @return the number of attachments that were released
     */
    public int releaseTab(String tabId) {
        Map<String, Staged> tab = byTab.remove(tabId);
        if (tab == null) {
            return 0;
        }
        try {
            Path tabDir = root.resolve(sanitize(tabId));
            if (Files.isDirectory(tabDir)) {
                try (var paths = Files.list(tabDir)) {
                    paths.forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // The file stays behind; the directory is removed below.
                        }
                    });
                }
                Files.deleteIfExists(tabDir);
            }
        } catch (IOException ignored) {
            // A leftover file is not worth failing a tab close over.
        }
        return tab.size();
    }

    /** Drop every tab's attachments. */
    public void clear() {
        for (String tabId : new ArrayList<>(byTab.keySet())) {
            releaseTab(tabId);
        }
    }

    public int size() {
        int total = 0;
        for (Map<String, Staged> tab : byTab.values()) {
            total += tab.size();
        }
        return total;
    }

    /** Keep a path segment free of separators and traversal. */
    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "unnamed";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            sb.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.' ? c : '_');
        }
        String cleaned = sb.toString();
        while (cleaned.contains("..")) {
            cleaned = cleaned.replace("..", "_");
        }
        return cleaned.isBlank() ? "unnamed" : cleaned;
    }

    /** The default location: a directory under the system temp dir. */
    public static Path defaultRoot() {
        return Path.of(System.getProperty("java.io.tmpdir"), "opencode-buddy-attachments");
    }

    @Override
    public String toString() {
        return "TabAttachmentStore{" + byTab.size() + " tabs, " + size() + " attachments, root="
                + root.toString() + "}";
    }

    /** The root directory, for diagnostics. */
    public Path root() {
        return root;
    }

    /** The directory a tab's attachments live in. */
    public Path tabDirectory(String tabId) {
        return root.resolve(sanitize(tabId));
    }

    /** The charset used when a staged file is textual. */
    public static java.nio.charset.Charset charset() {
        return StandardCharsets.UTF_8;
    }
}
