package com.opencodebuddy.session;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of pending opencode permission requests.
 *
 * <p>opencode permission ids ({@code per_...}) are global on the server and the
 * HTTP reply endpoint is workspace-scoped, so a decision must carry the session
 * id and project directory that were current when the request arrived — not the
 * ones of whatever session happens to be active in the window when the user
 * clicks the dialog. The daemon-side marker handler registers the request here
 * (from the normalized {@code [PERMISSION_REQUEST]} payload); the decision
 * funnel in {@link ClaudeSession#handlePermissionDecision} consumes the entry
 * when the user answers and forwards the reply to the opencode server.</p>
 *
 * <p>Entries are small and self-cleaning: consumed on decision, removed on the
 * {@code permission.replied} marker, and the whole map is dropped if it exceeds
 * the cap (stale requests whose dialog was force-closed without a decision).</p>
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
    private static final Map<String, PendingPermission> PENDING = new ConcurrentHashMap<>();

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
        if (PENDING.size() >= MAX_ENTRIES) {
            PENDING.clear();
        }
        PENDING.put(permissionId, new PendingPermission(sessionId, directory));
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

    public static void clear() {
        PENDING.clear();
    }

    public static int size() {
        return PENDING.size();
    }
}
