package com.opencodebuddy.tab;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable identity of one chat tab inside a webview.
 *
 * <p>This is deliberately a separate type from {@link SessionId}. A tab exists
 * before its opencode session is created and survives session binding; a
 * session id is the canonical {@code ses_…} identity reported by the server.
 * Mixing the two is what made reload/rekey paths route by guesses.</p>
 */
public record TabId(String value) {

    public TabId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("tabId must not be blank");
        }
    }

    public static TabId create() {
        return new TabId(UUID.randomUUID().toString());
    }

    public static TabId of(String value) {
        return new TabId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
