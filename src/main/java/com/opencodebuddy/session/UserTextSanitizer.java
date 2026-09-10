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
    private static final List<String> INJECTED_SECTION_TITLES = List.of(
            "## Workspace Context",
            "## Project Modules",
            "## Active Terminal Session",
            "## Referenced Files",
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
        Matcher matcher = INJECTED_SECTION.matcher(text);
        String stripped = matcher.replaceAll("");
        return stripped.strip();
    }

    /**
     * Whether anything would be removed — lets callers keep the original
     * content untouched when the text is already clean (avoids replacing a
     * non-identical object for nothing).
     */
    public static boolean needsSanitize(String text) {
        return text != null && INJECTED_SECTION.matcher(text).find();
    }
}
