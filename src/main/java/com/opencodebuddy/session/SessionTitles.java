package com.opencodebuddy.session;

import java.util.regex.Pattern;

/**
 * Session title helpers shared by the host-side title paths.
 *
 * <p>While a session has not been named, the opencode engine refers to it as
 * {@code New session - <ISO>} (subagents: {@code Child session - <ISO>}). That
 * is a placeholder, not a conversation name: rendering it puts a raw timestamp
 * on the tab and then replaces it seconds later, when the engine's own title
 * generator finishes. Both are dropped here so the tab keeps its localized
 * placeholder until a real name arrives.</p>
 *
 * <p>The pattern must stay in sync with the engine's default-title shape and
 * with {@code isDefaultSessionTitle} in the bridge.</p>
 */
public final class SessionTitles {

    private static final Pattern ENGINE_PLACEHOLDER = Pattern.compile(
            "^(?:New|Child) session - \\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$");

    private SessionTitles() {
    }

    /**
     * @param title candidate title
     * @return {@code true} when the title is an engine placeholder and must not
     *         be shown or persisted as the conversation title
     */
    public static boolean isEnginePlaceholder(String title) {
        return title != null && ENGINE_PLACEHOLDER.matcher(title.trim()).matches();
    }
}
