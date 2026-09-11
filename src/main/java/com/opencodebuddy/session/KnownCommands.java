package com.opencodebuddy.session;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Known OpenCode slash commands definition and validation.
 *
 * Slash commands that match this set are dispatched to OpenCode's
 * /session/{id}/command endpoint. Unmatched slash-prefixed text (such as
 * /Users/... or /etc/...) is treated as standard user message content.
 */
public final class KnownCommands {

    private static final Set<String> KNOWN_OPENCODE_COMMANDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "commit",
            "review",
            "init",
            "undo",
            "redo",
            "clear",
            "help",
            "test",
            "compact",
            "editor",
            "context",
            "plan",
            "resume",
            "skills",
            "mcp",
            "status",
            "exit",
            "quit",
            "doctor",
            "version",
            "config",
            "login",
            "logout",
            "export",
            "share",
            "unshare"
    )));

    private KnownCommands() {
    }

    /**
     * Check if the given command name (case-insensitive, without leading '/') is a recognized OpenCode slash command.
     */
    public static boolean isKnownSlashCommand(String command) {
        if (command == null) {
            return false;
        }
        String normalized = command.trim().toLowerCase();
        return KNOWN_OPENCODE_COMMANDS.contains(normalized);
    }
}
