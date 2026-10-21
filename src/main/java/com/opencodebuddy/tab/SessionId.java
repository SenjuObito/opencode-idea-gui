package com.opencodebuddy.tab;

import java.util.Objects;

/**
 * Canonical opencode session identity.
 *
 * <p>Only ids reported by the opencode server belong here. UI tab ids,
 * temporary request ids and the old permission-service key must never be
 * wrapped in this type; that is the invariant enforced by the factory
 * methods.</p>
 */
public record SessionId(String value) {

    private static final String PREFIX = "ses_";

    public SessionId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (!value.startsWith(PREFIX)) {
            throw new IllegalArgumentException("not a canonical opencode session id: " + value);
        }
    }

    public static SessionId of(String value) {
        return new SessionId(value);
    }

    /** @return the canonical id, or {@code null} when the input is not a session id */
    public static SessionId tryParse(String value) {
        if (value == null || !value.startsWith(PREFIX)) {
            return null;
        }
        return new SessionId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
