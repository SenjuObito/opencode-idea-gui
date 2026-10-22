package com.opencodebuddy.handler.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Remembers command request ids so a replayed command runs at most once.
 *
 * <p>A webview reload can resend the same command (the user pressed send while
 * the page was being rebuilt, or a batch is retried). Sending the same prompt
 * twice is worse than dropping the duplicate, so ids are remembered before the
 * command runs and bounded so a long session cannot grow without limit.</p>
 */
public final class RequestIdLedger {

    private static final int MAX_REMEMBERED = 2_048;

    private final Map<String, Boolean> seen = new LinkedHashMap<>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > MAX_REMEMBERED;
        }
    };

    /**
     * Reserve a request id.
     *
     * @return {@code true} when this id was not seen before
     */
    public synchronized boolean remember(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return true;
        }
        if (seen.containsKey(requestId)) {
            return false;
        }
        seen.put(requestId, Boolean.TRUE);
        return true;
    }

    /** Release a reservation whose command was rejected before doing any work. */
    public synchronized void forget(String requestId) {
        if (requestId != null) {
            seen.remove(requestId);
        }
    }

    public synchronized int size() {
        return seen.size();
    }
}
