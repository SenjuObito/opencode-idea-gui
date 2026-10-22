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

/**
 * The attachments one tab staged.
 *
 * <p>An attachment belongs to the tab that picked it: it is written into that
 * tab's own directory, it is not readable by another tab, and it disappears
 * with the tab. Two tabs can therefore hold the same file name without
 * colliding, and closing one tab never takes the other tab's attachments with
 * it.</p>
 *
 * <p>The daemon protocol still carries the bytes inline, so the store's job is
 * ownership and lifetime: the bytes live in one place per tab, are read back
 * when the message is sent, and are deleted when the tab goes away.</p>
 */
public final class TabAttachmentStore {

    /** Refuse a single attachment larger than this (32 MiB of raw bytes). */
    public static final long MAX_ATTACHMENT_BYTES = 32L * 1024L * 1024L;

    /**
     * One staged attachment.
     *
     * @param size         the bytes written so far
     * @param expectedSize the size the webview announced, or -1 when it did not
     * @param complete     whether the upload finished
     */
    public record Staged(
            String attachmentId,
            String fileName,
            String mediaType,
            Path path,
            long size,
            long expectedSize,
            boolean complete
    ) {
        /** A staged attachment that is ready to send. */
        public boolean ready() {
            return complete;
        }
    }

    private final Path directory;
    private final Map<String, Staged> staged = new LinkedHashMap<>();

    public TabAttachmentStore(Path directory) {
        this.directory = directory;
    }

    /** The directory this tab's attachments live in. */
    public Path directory() {
        return directory;
    }

    /**
     * Stage an attachment.
     *
     * <p>The bytes are decoded and written once; the send path reads them back
     * from there instead of carrying them through the bridge again.</p>
     *
     * @return the staged attachment, or {@code null} when the payload cannot
     *         be stored (too large, not base64, no disk)
     */
    public Staged stage(String attachmentId, String fileName, String mediaType, String base64Data) {
        if (base64Data == null || base64Data.isBlank()) {
            return null;
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Data);
        } catch (IllegalArgumentException e) {
            return null;
        }
        Staged prepared = prepare(attachmentId, fileName, mediaType, bytes.length);
        if (prepared == null) {
            return null;
        }
        if (append(attachmentId, 0, base64Data) == null) {
            abort(attachmentId);
            return null;
        }
        Staged complete = complete(attachmentId);
        if (complete == null) {
            abort(attachmentId);
        }
        return complete;
    }

    /**
     * Open an upload: create the file this attachment will be written to.
     *
     * <p>The webview announces the size first, so the host can refuse an
     * oversized upload before a single byte crosses the bridge.</p>
     *
     * @param expectedSize the announced size in bytes, or -1 when unknown
     * @return the empty attachment, or {@code null} when it cannot be created
     */
    public Staged prepare(
            String attachmentId,
            String fileName,
            String mediaType,
            long expectedSize
    ) {
        if (attachmentId == null || attachmentId.isBlank()) {
            return null;
        }
        if (expectedSize > MAX_ATTACHMENT_BYTES) {
            return null;
        }
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve(sanitize(attachmentId) + "-" + sanitize(fileName));
            Files.write(file, new byte[0]);
            Staged result = new Staged(attachmentId, fileName, mediaType, file, 0, expectedSize, false);
            staged.put(attachmentId, result);
            return result;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Append one chunk of an upload.
     *
     * <p>The offset is the number of bytes already written. A chunk that does
     * not continue the file exactly is refused: a lost or reordered chunk would
     * otherwise produce a silently corrupt attachment.</p>
     *
     * @return the attachment as it stands, or {@code null} when the chunk does
     *         not belong at that offset
     */
    public Staged append(String attachmentId, long offset, String base64Chunk) {
        Staged current = staged.get(attachmentId);
        if (current == null || base64Chunk == null) {
            return null;
        }
        if (offset != current.size()) {
            return null;
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Chunk);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (current.size() + bytes.length > MAX_ATTACHMENT_BYTES) {
            return null;
        }
        try {
            Files.write(current.path(), bytes, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            return null;
        }
        Staged next = new Staged(current.attachmentId(), current.fileName(), current.mediaType(),
                current.path(), current.size() + bytes.length, current.expectedSize(), false);
        staged.put(attachmentId, next);
        return next;
    }

    /**
     * Finish an upload.
     *
     * @return the complete attachment, or {@code null} when the announced size
     *         and the written size disagree
     */
    public Staged complete(String attachmentId) {
        Staged current = staged.get(attachmentId);
        if (current == null) {
            return null;
        }
        if (current.expectedSize() >= 0 && current.expectedSize() != current.size()) {
            return null;
        }
        Staged done = new Staged(current.attachmentId(), current.fileName(), current.mediaType(),
                current.path(), current.size(), current.expectedSize(), true);
        staged.put(attachmentId, done);
        return done;
    }

    /** Drop one upload and its file. */
    public void abort(String attachmentId) {
        Staged current = staged.remove(attachmentId);
        if (current == null) {
            return;
        }
        try {
            Files.deleteIfExists(current.path());
        } catch (IOException ignored) {
            // The directory is cleaned when the tab closes.
        }
    }

    /** The staged attachment, or {@code null}. */
    public Staged get(String attachmentId) {
        return staged.get(attachmentId);
    }

    /** Every staged attachment. */
    public List<Staged> list() {
        return new ArrayList<>(staged.values());
    }

    /**
     * Read the bytes of a staged attachment back.
     *
     * @return the base64 payload, or {@code null} when the file is gone
     */
    public String readData(String attachmentId) {
        Staged attachment = staged.get(attachmentId);
        if (attachment == null || !attachment.ready()) {
            // A half-uploaded file would be sent as if it were whole.
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(attachment.path());
            return Base64.getEncoder().encodeToString(bytes);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Drop everything this tab staged and delete the files.
     *
     * <p>Called when the tab closes: the bytes must not outlive the tab that
     * owns them.</p>
     *
     * @return the number of attachments that were released
     */
    public int release() {
        int count = staged.size();
        staged.clear();
        try {
            if (Files.isDirectory(directory)) {
                try (var paths = Files.list(directory)) {
                    paths.forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // The file stays behind; the directory is removed below.
                        }
                    });
                }
                Files.deleteIfExists(directory);
            }
        } catch (IOException ignored) {
            // A leftover file is not worth failing a tab close over.
        }
        return count;
    }

    public int size() {
        return staged.size();
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
        return "TabAttachmentStore{" + staged.size() + " attachments, dir=" + directory + "}";
    }

    /** The charset used when a staged file is textual. */
    public static java.nio.charset.Charset charset() {
        return StandardCharsets.UTF_8;
    }
}
