package com.opencodebuddy.session;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registry of pending opencode permission requests — a cross-layer lookup table
 * keyed by the server's globally unique {@code per_...} id.
 *
 * <p>opencode permission ids are global on the server and the HTTP reply
 * endpoint is workspace-scoped, so a decision must carry the session id and
 * project directory that were current when the request arrived — not the ones of
 * whatever session happens to be active in the window when the user clicks. The
 * daemon-side marker handler registers the request here (from the normalized
 * {@code [PERMISSION_REQUEST]} payload); the decision funnel in
 * {@link OpencodeSession#handlePermissionDecision} consumes the entry and
 * forwards the reply to the opencode server.</p>
 *
 * <p><b>Why this is static:</b> the key is the server's permission id, which is
 * unique across sessions, so the table holds no per-session state and cannot
 * misroute on its own. Session identity is stored <i>inside</i> each entry and is
 * read back at decision time — the active window is never consulted. Entries are
 * removed on decision, on {@code permission.replied}, and by
 * {@link #clearSession(String)} when a session is disposed.</p>
 *
 * <p><b>Overflow policy:</b> evicts the oldest entries individually. The previous
 * "clear the whole map" behaviour dropped pending requests belonging to
 * <i>other</i> sessions, which silently resolved them as rejected.</p>
 */
public final class OpencodePermissionRegistry {

    /** Session and directory context captured when the server asked. */
    public static final class PendingPermission {
        public final String sessionId;
        public final String directory;

        PendingPermission(String sessionId, String directory) {
            this.sessionId = sessionId;
            this.directory = directory;
        }
    }

    private static final int MAX_ENTRIES = 200;

    /**
     * Insertion-ordered so overflow can evict the oldest entries one by one
     * instead of nuking the table.
     */
    private static final Map<String, PendingPermission> PENDING =
            Collections.synchronizedMap(new LinkedHashMap<>());

    private OpencodePermissionRegistry() {
    }

    /** opencode permission request ids are prefixed {@code per_} by the server. */
    public static boolean isOpencodePermissionId(String channelId) {
        return channelId != null && channelId.startsWith("per_");
    }

    public static void register(String permissionId, String sessionId, String directory) {
        if (!isOpencodePermissionId(permissionId)) {
            return;
        }
        synchronized (PENDING) {
            if (PENDING.size() >= MAX_ENTRIES && !PENDING.containsKey(permissionId)) {
                evictOldest(PENDING.size() - MAX_ENTRIES + 1);
            }
            PENDING.put(permissionId, new PendingPermission(sessionId, directory));
        }
    }

    /**
     * Evict the oldest {@code count} entries (stale requests whose dialog was
     * force-closed without a decision). Caller must hold the map's lock.
     */
    private static void evictOldest(int count) {
        Iterator<String> it = PENDING.keySet().iterator();
        int evicted = 0;
        while (it.hasNext() && evicted < count) {
            it.next();
            it.remove();
            evicted++;
        }
    }

    /** Remove and return the registration for a decided request. */
    public static PendingPermission consume(String permissionId) {
        if (permissionId == null) {
            return null;
        }
        return PENDING.remove(permissionId);
    }

    /** Drop a request without replying (server already resolved or aborted it). */
    public static void remove(String permissionId) {
        if (permissionId != null) {
            PENDING.remove(permissionId);
        }
    }

    /**
     * Drop every pending request belonging to a disposed session.
     *
     * <p>Symmetric counterpart to session teardown: without it, a closed session
     * leaves entries behind until the cap is reached.</p>
     *
     * @return number of entries removed
     */
    public static int clearSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return 0;
        }
        int removed = 0;
        synchronized (PENDING) {
            Iterator<Map.Entry<String, PendingPermission>> it = PENDING.entrySet().iterator();
            while (it.hasNext()) {
                PendingPermission pending = it.next().getValue();
                if (sessionId.equals(pending.sessionId)) {
                    it.remove();
                    removed++;
                }
            }
        }
        return removed;
    }

    public static void clear() {
        PENDING.clear();
    }

    public static int size() {
        return PENDING.size();
    }
}
