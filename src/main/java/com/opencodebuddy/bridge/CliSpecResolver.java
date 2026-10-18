package com.opencodebuddy.bridge;

import com.opencodebuddy.settings.OpenCodeBuddySettingsService;
import com.intellij.openapi.diagnostic.Logger;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the user's CLI settings into the environment contract the Node bridge
 * understands (the {@code L0} tier of {@code utils/cli-path.js}).
 *
 * <h2>Why this exists</h2>
 * The user's configured paths used to be injected as {@code OPENCODE_BIN}, which
 * the Node resolver treats as a *best-effort* override (tier {@code L1}) sitting
 * in the same namespace as the user's shell environment. Two consequences:
 * <ul>
 *   <li>an unreadable/invalid configured path silently fell through to whatever
 *       {@code opencode} happened to be found later &mdash; the user's explicit
 *       choice turned into a mystery version instead of an error;</li>
 *   <li>{@code source} could not be attributed, because "settings" and "shell
 *       env" were the same variable.</li>
 * </ul>
 * The distinct names below make the setting authoritative and attributable:
 * see {@code docs/engine-resolution-architecture.md} (ADR-4, ADR-6).
 *
 * <h2>What it deliberately does NOT do</h2>
 * <ul>
 *   <li><b>No existence check.</b> The daemon may run in a different filesystem
 *       namespace than the IDE host (a WSL node is spawned through {@code wsl}),
 *       so a host-side {@code Files.exists} would reject perfectly valid paths
 *       and &mdash; because {@code L0} failures block &mdash; take the whole engine
 *       down. The Node resolver, which runs where the path will actually be used,
 *       is the single authority (ADR-6).</li>
 *   <li><b>No {@code --version} probe.</b> Same reason, plus it would duplicate
 *       the Windows {@code .cmd}-needs-a-shell handling in a second language.
 *       The version string is display-only anyway (ADR-2).</li>
 *   <li><b>No candidate scanning.</b> The one place that knows every install
 *       location is {@code cli-resolver.js}.</li>
 * </ul>
 */
public final class CliSpecResolver {

    private static final Logger LOG = Logger.getInstance(CliSpecResolver.class);

    /** Authoritative opencode binary path (tier L0 in the Node resolver). */
    public static final String ENV_CLI_PATH = "OPENCODEBUDDY_CLI_PATH";
    /** The Node.js runtime the host is configured to use (tier L0). */
    public static final String ENV_NODE_BIN = "OPENCODEBUDDY_NODE_BIN";
    /** Plugin-owned state directory, so both bridge processes agree on one path. */
    public static final String ENV_CONFIG_DIR = "OPENCODEBUDDY_CONFIG_DIR";

    /** {@code %NAME%} (Windows), {@code ${NAME}} and {@code $NAME} (POSIX). */
    private static final Pattern ENV_REF = Pattern.compile(
            "%([A-Za-z_][A-Za-z0-9_]*)%"
                    + "|\\$\\{([A-Za-z_][A-Za-z0-9_]*)}"
                    + "|\\$([A-Za-z_][A-Za-z0-9_]*)");
    /** Windows drive-absolute path, e.g. {@code C:\tools\opencode.exe}. */
    private static final Pattern WINDOWS_ABSOLUTE = Pattern.compile("^[A-Za-z]:[\\\\/].*");

    private final java.util.function.Supplier<String> opencodeCliPathSupplier;
    private final java.util.function.Supplier<String> configPathSupplier;

    public CliSpecResolver() {
        this(new OpenCodeBuddySettingsService());
    }

    CliSpecResolver(OpenCodeBuddySettingsService settingsService) {
        this(settingsService::getOpencodeCliPath, settingsService::getConfigPath);
    }

    /**
     * Test seam: pure suppliers instead of a settings file on disk. Mirrors the
     * injectable seams of {@code utils/cli-path.js}, so host and bridge behaviour
     * can be exercised without touching the real user configuration.
     */
    CliSpecResolver(java.util.function.Supplier<String> opencodeCliPathSupplier,
                    java.util.function.Supplier<String> configPathSupplier) {
        this.opencodeCliPathSupplier = opencodeCliPathSupplier;
        this.configPathSupplier = configPathSupplier;
    }

    /**
     * Resolve the spec from the current settings, anchoring relative paths at the
     * real user home directory.
     */
    public CliSpec resolve(String effectiveNodePath) {
        return resolve(effectiveNodePath, NodeDetector.resolveHomeForFileOps());
    }

    /**
     * Resolve the spec with an explicit home directory.
     *
     * @param effectiveNodePath the Node.js executable the host will actually
     *                          spawn (usually {@code NodeDetector#findNodeExecutable()}),
     *                          or null/blank when unknown
     * @param homeDir           anchor for {@code ~} and relative paths
     * @return a spec whose {@link CliSpec#applyTo(Map)} is safe to call on any
     *         process environment
     */
    CliSpec resolve(String effectiveNodePath, String homeDir) {
        List<String> notes = new ArrayList<>();
        Map<String, String> env = System.getenv();

        String configuredCli = expandPath(safeGetOpencodeCliPath(), homeDir, env, "opencodeCliPath", notes);
        String nodePath = expandPath(blankToNull(effectiveNodePath), homeDir, env, "nodePath", notes);
        if (configuredCli != null && isBareCommandName(configuredCli)) {
            notes.add("No separator in opencodeCliPath; L0 must be a path, so it is left to PATH lookup: "
                    + configuredCli);
            configuredCli = null;
        }
        if (nodePath != null && isBareCommandName(nodePath)) {
            // "node" is NodeDetector's not-found fallback, not a user choice.
            // Injecting a bare name as L0 would make the resolver evaluate it as a
            // configured path that does not exist, which blocks the engine.
            notes.add("No separator in nodePath; L0 must be a path, so it is left to PATH lookup: "
                    + nodePath);
            nodePath = null;
        }

        String configDir = resolveConfigDir(homeDir);

        CliSpec spec = new CliSpec(configuredCli, nodePath, configDir, notes);
        LOG.info("[CliSpec] " + spec.describe());
        for (String note : notes) {
            LOG.warn("[CliSpec] " + note);
        }
        return spec;
    }

    private String safeGetOpencodeCliPath() {
        try {
            return opencodeCliPathSupplier.get();
        } catch (Exception e) {
            LOG.warn("[CliSpec] Could not read opencodeCliPath: " + e.getMessage());
            return null;
        }
    }

    /**
     * The directory holding the plugin's own {@code config.json} (parent of
     * {@link OpenCodeBuddySettingsService#getConfigPath()}).
     */
    private String resolveConfigDir(String homeDir) {
        try {
            String configPath = configPathSupplier.get();
            if (configPath != null && !configPath.trim().isEmpty()) {
                java.nio.file.Path parent = Paths.get(configPath.trim()).getParent();
                if (parent != null) {
                    return parent.toString();
                }
            }
        } catch (Exception e) {
            LOG.warn("[CliSpec] Could not resolve config dir: " + e.getMessage());
        }
        return homeDir != null && !homeDir.isEmpty()
                ? Paths.get(homeDir, ".opencodebuddy").toString() : null;
    }

    // =========================================================================
    // Path expansion (pure helpers, package-private for tests)
    // =========================================================================

    /**
     * Expand {@code ~}, {@code %VAR%} / {@code $VAR} and resolve a relative
     * result against the user's home directory.
     *
     * <p>Relative paths resolve against the home directory rather than the
     * IDE's working directory: the setting is a user preference, and the IDE
     * process cwd changes with whatever project is open &mdash; anchoring to it
     * would make the same configuration mean different things at different
     * times.</p>
     *
     * @return the expanded path, or null when the input is blank
     */
    static String expandPath(
            String raw,
            String homeDir,
            Map<String, String> env,
            String label,
            List<String> notes
    ) {
        String value = blankToNull(raw);
        if (value == null) {
            return null;
        }
        value = expandEnvVars(value, env, label, notes);
        value = expandTilde(value, homeDir, label, notes);
        value = value.trim();
        if (value.isEmpty()) {
            return null;
        }
        // Values returned from here on are deliberately NOT re-anchored at the home
        // directory. In each case anchoring would fabricate a plausible-looking path
        // out of something the user did not write, and a plausible-looking wrong
        // path is worse than a literal one: it hides the mistake behind a "file not
        // found" that does not point at the setting.
        if (isBareCommandName(value)) {
            // A bare command name is not a path at all; the caller decides what it
            // means (and for L0 the answer is "leave it to PATH lookup").
            return value;
        }
        if (value.startsWith("~")) {
            return value;
        }
        if (ENV_REF.matcher(value).find()) {
            // An unresolved `%VAR%` / `$VAR` reference survives expansion on purpose.
            return value;
        }
        if (!isAbsoluteLike(value)) {
            if (homeDir == null || homeDir.isEmpty()) {
                notes.add(label + ": cannot resolve relative path without a home directory: " + value);
                return value;
            }
            try {
                return Paths.get(homeDir, value).normalize().toString();
            } catch (Exception e) {
                notes.add(label + ": could not resolve relative path " + value + ": " + e.getMessage());
                return value;
            }
        }
        return value;
    }

    /**
     * Absolute in either convention, so a POSIX path stays usable on a Windows
     * host (and vice versa) instead of being silently re-anchored to the home
     * directory by {@link Paths}.
     */
    static boolean isAbsoluteLike(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        return path.startsWith("/")
                || path.startsWith("\\\\")                       // UNC \\server\share
                || WINDOWS_ABSOLUTE.matcher(path).matches();
    }

    /**
     * A command name with no directory component ({@code node}, {@code opencode}).
     *
     * <p>Such a value must never be injected as L0. Anchoring it at the home
     * directory would manufacture a path that does not exist, and because an L0
     * path that does not exist is a <em>blocking</em> failure, the engine would
     * be disabled over a value whose actual meaning is the opposite: "look it up
     * on PATH" &mdash; which is exactly what tiers L1&ndash;L3 already do.</p>
     */
    static boolean isBareCommandName(String value) {
        return value != null
                && !value.isEmpty()
                && value.indexOf('/') < 0
                && value.indexOf('\\') < 0;
    }

    static String expandEnvVars(
            String value,
            Map<String, String> env,
            String label,
            List<String> notes
    ) {
        if (value == null || (value.indexOf('%') < 0 && value.indexOf('$') < 0)) {
            return value;
        }
        Matcher matcher = ENV_REF.matcher(value);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            String name = matcher.group(1) != null ? matcher.group(1)
                    : matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
            String replacement = env == null ? null : env.get(name);
            out.append(value, last, matcher.start());
            if (replacement == null) {
                // Leave the token untouched: an unresolved reference is a user
                // error worth seeing literally, not a path to invent.
                notes.add(label + ": environment variable " + name + " is not set (kept as-is)");
                out.append(matcher.group());
            } else {
                out.append(replacement);
            }
            last = matcher.end();
        }
        out.append(value, last, value.length());
        return out.toString();
    }

    static String expandTilde(String value, String homeDir, String label, List<String> notes) {
        if (value == null || !value.startsWith("~")) {
            return value;
        }
        if (homeDir == null || homeDir.isEmpty()) {
            notes.add(label + ": cannot expand '~' without a home directory: " + value);
            return value;
        }
        if (value.equals("~")) {
            return homeDir;
        }
        char next = value.charAt(1);
        if (next == '/' || next == '\\') {
            String home = homeDir;
            // Avoid "C:\me" + "\bin" producing a doubled separator; the original
            // separator is preserved so a Windows path stays Windows-shaped.
            boolean homeEndsWithSep = home.endsWith("/") || home.endsWith("\\");
            return homeEndsWithSep ? home + value.substring(2) : home + value.substring(1);
        }
        notes.add(label + ": '~user' expansion is not supported: " + value);
        return value;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // =========================================================================
    // Result
    // =========================================================================

    /**
     * The resolved environment contract. Immutable.
     */
    public static final class CliSpec {
        private final String opencodePath;
        private final String nodePath;
        private final String configDir;
        private final List<String> notes;

        CliSpec(String opencodePath, String nodePath, String configDir, List<String> notes) {
            this.opencodePath = opencodePath;
            this.nodePath = nodePath;
            this.configDir = configDir;
            this.notes = Collections.unmodifiableList(new ArrayList<>(notes));
        }

        /** Configured opencode binary, already expanded; null when unset. */
        public String getOpencodePath() {
            return opencodePath;
        }

        /** Node runtime to advertise as L0; null when not injectable. */
        public String getNodePath() {
            return nodePath;
        }

        /** Plugin state directory; null only when no home directory is resolvable. */
        public String getConfigDir() {
            return configDir;
        }

        public List<String> getNotes() {
            return notes;
        }

        /**
         * Write the spec into a process environment.
         *
         * <p>Only non-null entries are written. Omitting a key is meaningful:
         * the resolver distinguishes "the host has no opinion" from "the host
         * says this path", and only the latter can produce a blocking
         * {@code settings-invalid} failure.</p>
         */
        public void applyTo(Map<String, String> environment) {
            if (environment == null) {
                return;
            }
            if (opencodePath != null) {
                environment.put(ENV_CLI_PATH, opencodePath);
            }
            if (nodePath != null) {
                environment.put(ENV_NODE_BIN, nodePath);
            }
            if (configDir != null) {
                environment.put(ENV_CONFIG_DIR, configDir);
            }
        }

        /** Environment view of the spec, for logging and tests. */
        public Map<String, String> toEnvironment() {
            Map<String, String> out = new LinkedHashMap<>();
            applyTo(out);
            return out;
        }

        /** Single-line summary for the plugin log. */
        public String describe() {
            return "cli=" + (opencodePath != null ? opencodePath : "(unset, will scan)")
                    + " node=" + (nodePath != null ? nodePath : "(unset)")
                    + " configDir=" + (configDir != null ? configDir : "(unset)")
                    + (notes.isEmpty() ? "" : " notes=" + notes.size());
        }
    }
}
