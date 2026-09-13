package com.opencodebuddy.session;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strips plugin-injected context sections from user message text.
 *
 * On send, the plugin appends generated markdown sections (IDE context,
 * referenced files, agent instructions, …) to the text part so the model
 * receives them. That text is persisted verbatim by opencode, so without
 * this pass a history reload would render the injected sections inside the
 * user bubble. Each section starts with a known {@code ## title} and runs
 * until the next {@code ## heading} or end of text — the same anchor set
 * the frontend uses to keep legacy cached session titles clean.
 */
public final class UserTextSanitizer {

    /**
     * Section titles produced by SessionContextService#buildCodexContextAppend
     * and SessionSendService (agent prompt append). Only sections carrying one
     * of these exact headings are removed, so a user typing their own markdown
     * headings survives.
     */
    private static final Pattern ATTACHMENT_BLOCK_PATTERN = Pattern.compile(
            "<attachment\\b[^>]*>.*?(?:</attachment>|\\z)",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );

    private static final List<String> INJECTED_SECTION_TITLES = List.of(
            "## Workspace Context",
            "## Project Modules",
            "## Active Terminal Session",
            "## Referenced Files",
            "## Attached Files",
            "## IDE Context",
            "## User's Current IDE Context",
            "## Agent Role and Instructions"
    );

    /**
     * Matches one injected section: its title line plus everything up to (but
     * not including) the next line starting with {@code ## }. DOTALL lets the
     * body span newlines. The title anchors at line start — separator blank
     * lines before it belong to the user text and are kept. Idempotent by
     * construction: after a strip no injected title remains, so a second pass
     * is a no-op.
     */
    private static final Pattern INJECTED_SECTION = Pattern.compile(
            "^(" + String.join("|", INJECTED_SECTION_TITLES.stream().map(Pattern::quote).toList())
                    + ")\\s*$.*?(?=^## |\\z)",
            Pattern.MULTILINE | Pattern.DOTALL
    );

    private UserTextSanitizer() {
    }

    /**
     * Remove injected sections and trim leftover separator whitespace.
     *
     * @param text raw persisted user text, may be null
     * @return text without injected sections; never null
     */
    public static String sanitize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        // 1. Strip inlined <attachment> blocks first so any ## headers inside them
        // don't confuse the section regex.
        String withoutAttachments = ATTACHMENT_BLOCK_PATTERN.matcher(text).replaceAll("");
        // 2. Strip standard ## injected context sections.
        Matcher matcher = INJECTED_SECTION.matcher(withoutAttachments);
        String stripped = matcher.replaceAll("").strip();
        // 3. Drop synthetic fallback prompt text for attachment-only turns.
        if ("Please review the attached file(s).".equals(stripped)
                || "Please analyze the attached image(s).".equals(stripped)) {
            return "";
        }
        return stripped;
    }

    /**
     * Whether anything would be removed — lets callers keep the original
     * content untouched when the text is already clean (avoids replacing a
     * non-identical object for nothing).
     */
    public static boolean needsSanitize(String text) {
        if (text == null) {
            return false;
        }
        return ATTACHMENT_BLOCK_PATTERN.matcher(text).find()
                || INJECTED_SECTION.matcher(text).find()
                || "Please review the attached file(s).".equals(text.trim())
                || "Please analyze the attached image(s).".equals(text.trim());
    }
}
