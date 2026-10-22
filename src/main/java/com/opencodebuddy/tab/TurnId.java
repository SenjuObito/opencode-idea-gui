package com.opencodebuddy.tab;

/**
 * Server-side turn identity.
 *
 * <p>The daemon stamps every data line with the turn it belongs to and ends the
 * turn with an authoritative {@code [TURN_END] {turnId}} marker. A turn can
 * settle through several paths (marker, stream end, interrupted send, daemon
 * restart), so the host needs one key to make the terminal idempotent:
 * {@code (tabId, turnId)}. Keeping the type distinct makes "a number that
 * happens to identify a turn" impossible to confuse with a message index or a
 * sequence number.</p>
 */
public record TurnId(long value) {

    public TurnId {
        if (value <= 0) {
            throw new IllegalArgumentException("turnId must be positive: " + value);
        }
    }

    public static TurnId of(long value) {
        return new TurnId(value);
    }

    /** @return the turn id, or {@code null} when the input is not a positive number */
    public static TurnId tryParse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed > 0 ? new TurnId(parsed) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public String asString() {
        return Long.toString(value);
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
