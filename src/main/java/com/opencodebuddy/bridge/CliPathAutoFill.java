package com.opencodebuddy.bridge;

import com.intellij.openapi.diagnostic.Logger;
import com.opencodebuddy.settings.OpenCodeBuddySettingsService;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * Copies the opencode CLI path the daemon resolved on its own into the user's
 * setting, so the setting reflects what the plugin is actually launching.
 *
 * <h2>Why this exists</h2>
 * The daemon resolves the binary with {@code utils/cli-path.js} (L0 settings →
 * L1 env → L2 known dirs → L3 PATH → L4 bare name) and reports the result as
 * {@code engine_status.cliPath} / {@code cliSource}. Without this class, an
 * unset setting stayed empty forever while the plugin happily used a binary
 * found on PATH &mdash; the user could not tell which binary that was.
 *
 * <h2>Why the daemon's value and nothing else</h2>
 * A host-side scan would be a second implementation with a different candidate
 * table and a different order, so the saved value could disagree with the one
 * the next launch actually spawns. The daemon's resolution is the only
 * authority: its {@code cliPath} is, by construction, the path that
 * {@code findBinary()} will be handed.
 *
 * <h2>What it deliberately does NOT do</h2>
 * <ul>
 *   <li><b>No overwrite.</b> A non-empty setting is never touched: the user's
 *       explicit choice outranks anything discovered automatically.</li>
 *   <li><b>No re-scan.</b> The decision is a pure predicate over the daemon's
 *       report; discovery belongs to {@code cli-path.js}.</li>
 *   <li><b>No failure propagation.</b> Filling the setting is a convenience;
 *       a failed write must never affect the daemon or the tool window.</li>
 * </ul>
 */
public final class CliPathAutoFill {

    private static final Logger LOG = Logger.getInstance(CliPathAutoFill.class);

    /** {@code cliSource} value meaning "this came from the setting itself". */
    private static final String SOURCE_SETTINGS = "settings";

    /**
     * The settings file, as this class needs it. A seam so the fill logic can be
     * tested without touching the real {@code config.json}.
     */
    interface SettingsStore {
        /** Currently persisted opencode CLI path, or null/blank when unset. */
        String currentCliPath();

        /** Persist {@code cliPath} and mark it as auto-detected. */
        void saveAutoDetected(String cliPath) throws IOException;
    }

    private CliPathAutoFill() {
    }

    /**
     * Whether the resolved path should be written back.
     *
     * @param cliPath        {@code engine_status.cliPath} (may be null)
     * @param cliSource      {@code engine_status.cliSource} (may be null)
     * @param currentSetting what {@code config.json} currently holds
     * @return true when the setting is empty and the resolved path is usable
     */
    static boolean shouldFill(String cliPath, String cliSource, String currentSetting) {
        if (cliPath == null || cliPath.trim().isEmpty()) {
            return false;
        }
        if (SOURCE_SETTINGS.equals(cliSource)) {
            // Already the user's value (or a previous auto-fill played back as L0).
            return false;
        }
        if (currentSetting != null && !currentSetting.trim().isEmpty()) {
            return false;
        }
        String path = cliPath.trim();
        // A bare command name (L4) is not a path. Writing it back would be a no-op
        // anyway: CliSpecResolver refuses to inject a bare value as L0.
        if (path.indexOf('/') < 0 && path.indexOf('\\') < 0) {
            return false;
        }
        if (NodeDetector.isWslPath(path)) {
            // A Linux path inside WSL cannot be checked from the IDE host: File
            // would report "missing" for every valid one. Trust the daemon, which
            // resolved it in the namespace where it will be executed.
            return true;
        }
        return new File(path).isFile();
    }

    /**
     * Fill the setting when {@link #shouldFill} allows it.
     *
     * @return true when a value was written
     */
    static boolean apply(SettingsStore store, String cliPath, String cliSource) {
        if (store == null) {
            return false;
        }
        try {
            if (!shouldFill(cliPath, cliSource, store.currentCliPath())) {
                return false;
            }
            String path = cliPath.trim();
            store.saveAutoDetected(path);
            LOG.info("[CliPathAutoFill] Saved auto-detected opencode CLI path: " + path
                    + " (source=" + cliSource + ")");
            return true;
        } catch (Exception e) {
            // Convenience only: a read-only config directory or a concurrent
            // writer must not turn into a daemon-side error.
            LOG.warn("[CliPathAutoFill] Could not save auto-detected opencode CLI path: " + e.getMessage());
            return false;
        }
    }

    /**
     * Fire-and-forget entry point for the daemon event reader thread: writing
     * {@code config.json} on that thread would stall every subsequent event.
     */
    public static void applyAsync(String cliPath, String cliSource) {
        if (cliPath == null || cliPath.trim().isEmpty()) {
            return;
        }
        if (SOURCE_SETTINGS.equals(cliSource)) {
            // Nothing to learn from our own value, and building the settings
            // service is not free: skip it on the common steady-state event.
            return;
        }
        CompletableFuture.runAsync(() -> apply(newSettingsStore(), cliPath, cliSource));
    }

    private static SettingsStore newSettingsStore() {
        OpenCodeBuddySettingsService settings = new OpenCodeBuddySettingsService();
        return new SettingsStore() {
            @Override
            public String currentCliPath() {
                return settings.getOpencodeCliPath();
            }

            @Override
            public void saveAutoDetected(String cliPath) throws IOException {
                settings.setOpencodeCliPathAutoDetected(cliPath);
            }
        };
    }
}
