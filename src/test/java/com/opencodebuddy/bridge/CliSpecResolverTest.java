package com.opencodebuddy.bridge;

import org.junit.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the host side of the CLI contract.
 *
 * <p>Everything here runs through the injected seams (settings suppliers, explicit
 * home directory) so the assertions hold on Windows, macOS and Linux alike &mdash;
 * the same property the Node-side resolver is built around.</p>
 *
 * <p>The behaviour under test is what the Node resolver's L0 tier consumes:
 * {@code OPENCODEBUDDY_CLI_PATH} / {@code OPENCODEBUDDY_NODE_BIN} /
 * {@code OPENCODEBUDDY_CONFIG_DIR}.</p>
 */
public class CliSpecResolverTest {

    private static final String HOME = "C:\\Users\\me";

    private static CliSpecResolver resolverWith(String cliPath, String configPath) {
        return new CliSpecResolver(() -> cliPath, () -> configPath);
    }

    private static List<String> notes() {
        return new ArrayList<>();
    }

    private static String expand(String raw, String home, Map<String, String> env, List<String> notes) {
        return CliSpecResolver.expandPath(raw, home, env, "test", notes);
    }

    // =========================================================================
    // expandPath
    // =========================================================================

    @Test
    public void expandsTildeKeepingTheOriginalSeparator() {
        // A Windows-shaped path must stay Windows-shaped: the value is handed to a
        // Windows process, and re-writing it with "/" would be a silent rewrite of
        // the user's configuration.
        assertEquals("C:\\Users\\me\\.opencode\\opencode.exe",
                expand("~\\.opencode\\opencode.exe", HOME, Collections.emptyMap(), notes()));
        assertEquals("C:\\Users\\me/.opencode/opencode.exe",
                expand("~/.opencode/opencode.exe", HOME, Collections.emptyMap(), notes()));
    }

    @Test
    public void tildeAloneExpandsToTheHomeDirectory() {
        assertEquals(HOME, expand("~", HOME, Collections.emptyMap(), notes()));
    }

    @Test
    public void unsupportedTildeUserFormIsLeftLiteralAndNoted() {
        List<String> notes = notes();
        assertEquals("~root/bin/opencode",
                expand("~root/bin/opencode", HOME, Collections.emptyMap(), notes));
        assertEquals(1, notes.size());
        assertTrue(notes.get(0).contains("~user"));
    }

    @Test
    public void posixAbsolutePathIsNotReAnchoredToHome() {
        // isAbsoluteLike exists precisely so Paths.get() cannot reinterpret a POSIX
        // path as drive-relative on a Windows host (which would yield
        // "C:/Users/me/usr/local/bin/opencode").
        assertEquals("/usr/local/bin/opencode",
                expand("/usr/local/bin/opencode", HOME, Collections.emptyMap(), notes()));
    }

    @Test
    public void windowsAbsolutePathsArePassedThrough() {
        assertEquals("C:\\tools\\opencode.exe",
                expand("C:\\tools\\opencode.exe", HOME, Collections.emptyMap(), notes()));
        assertEquals("c:/tools/opencode.exe",
                expand("c:/tools/opencode.exe", HOME, Collections.emptyMap(), notes()));
        assertEquals("\\\\wsl.localhost\\Ubuntu\\usr\\bin\\opencode",
                expand("\\\\wsl.localhost\\Ubuntu\\usr\\bin\\opencode", HOME, Collections.emptyMap(), notes()));
    }

    @Test
    public void relativePathIsAnchoredAtTheHomeDirectoryNotTheProcessCwd() {
        // The IDE process cwd follows whatever project is open; anchoring a user
        // preference to it would make one configuration mean different things at
        // different times.
        String home = System.getProperty("user.home");
        assertEquals(Paths.get(home, "tools", "opencode").toString(),
                expand("tools/opencode", home, Collections.emptyMap(), notes()));
    }

    @Test
    public void expandsDollarBracedPlainAndPercentReferences() {
        Map<String, String> env = new HashMap<>();
        env.put("OC_ROOT", "/opt/oc");
        assertEquals("/opt/oc/bin/opencode",
                expand("${OC_ROOT}/bin/opencode", HOME, env, notes()));
        assertEquals("/opt/oc/bin/opencode",
                expand("$OC_ROOT/bin/opencode", HOME, env, notes()));
        assertEquals("/opt/oc\\bin\\opencode",
                expand("%OC_ROOT%\\bin\\opencode", HOME, env, notes()));
    }

    @Test
    public void unresolvedReferenceIsKeptLiteralAndReported() {
        // Not re-anchored at the home directory: `C:/Users/me/$NOPE/bin/opencode`
        // would look like a real path and bury the real problem (a typo'd or unset
        // variable) behind an unrelated "file not found".
        List<String> notes = notes();
        assertEquals("$NOPE/bin/opencode",
                expand("$NOPE/bin/opencode", HOME, Collections.emptyMap(), notes));
        assertEquals(1, notes.size());
        assertTrue(notes.get(0).contains("NOPE"));
    }

    @Test
    public void bareCommandNameIsNotTurnedIntoAHomeRelativePath() {
        // "opencode" means "look it up on PATH", not "~/opencode". Anchoring it
        // would manufacture a path that does not exist.
        List<String> notes = notes();
        assertEquals("opencode", expand("opencode", HOME, Collections.emptyMap(), notes));
        assertTrue(notes.isEmpty());
    }

    @Test
    public void blankInputsYieldNull() {
        assertNull(expand(null, HOME, Collections.emptyMap(), notes()));
        assertNull(expand("", HOME, Collections.emptyMap(), notes()));
        assertNull(expand("   ", HOME, Collections.emptyMap(), notes()));
    }

    @Test
    public void isAbsoluteLikeRecognisesBothConventionsAndRejectsBareNames() {
        assertTrue(CliSpecResolver.isAbsoluteLike("/usr/bin/opencode"));
        assertTrue(CliSpecResolver.isAbsoluteLike("C:\\tools\\opencode.exe"));
        assertTrue(CliSpecResolver.isAbsoluteLike("c:/tools/opencode.exe"));
        assertTrue(CliSpecResolver.isAbsoluteLike("\\\\server\\share\\opencode.exe"));

        assertFalse(CliSpecResolver.isAbsoluteLike("opencode"));
        assertFalse(CliSpecResolver.isAbsoluteLike("./opencode"));
        assertFalse(CliSpecResolver.isAbsoluteLike("..\\opencode"));
        assertFalse(CliSpecResolver.isAbsoluteLike("C:opencode"));
        assertFalse(CliSpecResolver.isAbsoluteLike(""));
        assertFalse(CliSpecResolver.isAbsoluteLike(null));
    }

    // =========================================================================
    // resolve
    // =========================================================================

    @Test
    public void configuredCliPathIsInjectedAsTheAuthoritativeL0Key() {
        CliSpecResolver.CliSpec spec = resolverWith("C:\\tools\\opencode.exe", null)
                .resolve(null, HOME);
        assertEquals("C:\\tools\\opencode.exe",
                spec.toEnvironment().get(CliSpecResolver.ENV_CLI_PATH));
    }

    @Test
    public void configuredCliPathIsInjectedEvenWhenTheHostCannotSeeTheFile() {
        // Deliberate: the daemon may run in a different filesystem namespace than
        // the IDE host (a WSL node is spawned through `wsl`), so a host-side
        // Files.exists() would reject valid paths. Because L0 failures BLOCK the
        // engine, gating on the host's own view would take a working setup down.
        // The resolver that runs where the path is used is the authority (ADR-6).
        CliSpecResolver.CliSpec spec = resolverWith("/mnt/c/tools/opencode.exe", null)
                .resolve(null, HOME);
        assertEquals("/mnt/c/tools/opencode.exe",
                spec.toEnvironment().get(CliSpecResolver.ENV_CLI_PATH));
        assertTrue(spec.getNotes().isEmpty());
    }

    @Test
    public void bareNodeFallbackIsNeverInjectedAsL0() {
        // NodeDetector returns the literal "node" when detection fails. Passing that
        // through as L0 would make the resolver evaluate a bare name against the
        // filesystem, fail, and treat it as settings-invalid — blocking the engine
        // over a value the user never configured.
        CliSpecResolver.CliSpec spec = resolverWith(null, null).resolve("node", HOME);
        assertFalse(spec.toEnvironment().containsKey(CliSpecResolver.ENV_NODE_BIN));
        assertEquals(1, spec.getNotes().size());
        assertTrue(spec.getNotes().get(0).contains("L0 must be a path"));
    }

    @Test
    public void bareConfiguredCliNameIsNotInjectedEither() {
        // Same reasoning on the opencode side: "opencode" as a setting means "resolve
        // it on PATH", which the lower tiers already do. Fabricating
        // HOME/opencode and then blocking on its absence would be a self-inflicted
        // outage.
        CliSpecResolver.CliSpec spec = resolverWith("opencode", null).resolve(null, HOME);
        assertFalse(spec.toEnvironment().containsKey(CliSpecResolver.ENV_CLI_PATH));
        assertEquals(1, spec.getNotes().size());
        assertTrue(spec.getNotes().get(0).contains("opencodeCliPath"));
    }

    @Test
    public void absoluteNodePathIsInjected() {
        CliSpecResolver.CliSpec spec = resolverWith(null, null)
                .resolve("C:\\node\\node.exe", HOME);
        assertEquals("C:\\node\\node.exe",
                spec.toEnvironment().get(CliSpecResolver.ENV_NODE_BIN));
    }

    @Test
    public void unsetSettingsOmitTheKeysRatherThanWritingBlanks() {
        // Omitting a key is meaningful: the resolver distinguishes "the host has no
        // opinion" (scan) from "the host says this path" (authoritative, blocking
        // on failure). Writing "" would be read as a configured-but-missing path.
        CliSpecResolver.CliSpec spec = resolverWith(null, null).resolve(null, HOME);
        Map<String, String> env = spec.toEnvironment();
        assertFalse(env.containsKey(CliSpecResolver.ENV_CLI_PATH));
        assertFalse(env.containsKey(CliSpecResolver.ENV_NODE_BIN));
        assertTrue(env.containsKey(CliSpecResolver.ENV_CONFIG_DIR));
    }

    @Test
    public void configDirIsTheParentOfTheSettingsFile() {
        String configPath = Paths.get(HOME, ".opencodebuddy", "config.json").toString();
        CliSpecResolver.CliSpec spec = resolverWith(null, configPath).resolve(null, HOME);
        assertEquals(Paths.get(HOME, ".opencodebuddy").toString(),
                spec.toEnvironment().get(CliSpecResolver.ENV_CONFIG_DIR));
    }

    @Test
    public void configDirFallsBackToHomeDotOpencodebuddy() {
        CliSpecResolver.CliSpec spec = resolverWith(null, null).resolve(null, HOME);
        assertEquals(Paths.get(HOME, ".opencodebuddy").toString(),
                spec.toEnvironment().get(CliSpecResolver.ENV_CONFIG_DIR));
        assertNotNull(spec.getConfigDir());
    }

    @Test
    public void blankConfigPathFallsBackInsteadOfYieldingAStrayParent() {
        CliSpecResolver.CliSpec spec = resolverWith(null, "   ").resolve(null, HOME);
        assertEquals(Paths.get(HOME, ".opencodebuddy").toString(),
                spec.toEnvironment().get(CliSpecResolver.ENV_CONFIG_DIR));
    }

    @Test
    public void applyToWritesOnlyTheKeysTheHostActuallyHasAnOpinionOn() {
        CliSpecResolver.CliSpec spec = resolverWith(null, null).resolve(null, HOME);
        Map<String, String> env = new HashMap<>();
        env.put("PRE_EXISTING", "kept");
        spec.applyTo(env);
        assertEquals("kept", env.get("PRE_EXISTING"));
        // PRE_EXISTING + CONFIG_DIR only: no CLI path and no node path were configured.
        assertEquals(2, env.size());
        assertFalse(env.containsKey(CliSpecResolver.ENV_CLI_PATH));
        assertFalse(env.containsKey(CliSpecResolver.ENV_NODE_BIN));
    }

    @Test
    public void applyToToleratesANullEnvironment() {
        resolverWith("C:\\tools\\opencode.exe", null).resolve(null, HOME).applyTo(null);
        // no exception == pass
    }

    @Test
    public void describeAlwaysMentionsEveryTier() {
        CliSpecResolver.CliSpec spec = resolverWith(null, null).resolve(null, HOME);
        String described = spec.describe();
        assertTrue(described, described.contains("cli="));
        assertTrue(described, described.contains("node="));
        assertTrue(described, described.contains("configDir="));
    }

    @Test
    public void settingsReadFailureDegradesToScanInsteadOfThrowing() {
        CliSpecResolver resolver = new CliSpecResolver(
                () -> {
                    throw new IllegalStateException("config unreadable");
                },
                null);
        CliSpecResolver.CliSpec spec = resolver.resolve(null, HOME);
        assertNull(spec.getOpencodePath());
        assertFalse(spec.toEnvironment().containsKey(CliSpecResolver.ENV_CLI_PATH));
    }

    // =========================================================================
    // Auto-detected values (opaquely filled in by CliPathAutoFill)
    // =========================================================================

    private static CliSpecResolver resolverWithAuto(String cliPath, boolean autoDetected) {
        return new CliSpecResolver(() -> cliPath, () -> autoDetected, () -> null);
    }

    private static String missingPath() {
        return Paths.get(System.getProperty("java.io.tmpdir"), "opencode-missing-for-test").toString();
    }

    @Test
    public void staleAutoDetectedPathIsDroppedSoTheResolverCanScan() {
        // The plugin filled this value in itself; the binary is gone. Blocking the
        // engine over a value the user never typed would be a failure they cannot
        // even explain, so L0 is dropped and the daemon re-scans instead.
        CliSpecResolver.CliSpec spec = resolverWithAuto(missingPath(), true).resolve(null, HOME);
        assertNull(spec.getOpencodePath());
        assertFalse(spec.toEnvironment().containsKey(CliSpecResolver.ENV_CLI_PATH));
        assertFalse("a note must explain why L0 was dropped", spec.getNotes().isEmpty());
    }

    @Test
    public void staleUserConfiguredPathKeepsItsBlockingSemantics() {
        // ADR-6: an explicit choice must surface as settings-invalid, never as a
        // silent fallthrough to some mystery binary.
        String missing = missingPath();
        CliSpecResolver.CliSpec spec = resolverWithAuto(missing, false).resolve(null, HOME);
        assertEquals(missing, spec.getOpencodePath());
    }

    @Test
    public void autoDetectedPathThatStillExistsIsInjected() throws Exception {
        java.nio.file.Path binary = java.nio.file.Files.createTempFile("opencode-auto-", ".exe");
        binary.toFile().deleteOnExit();
        String resolved = binary.toAbsolutePath().toString();
        CliSpecResolver.CliSpec spec = resolverWithAuto(resolved, true).resolve(null, HOME);
        assertEquals(resolved, spec.getOpencodePath());
    }

    @Test
    public void autoDetectedFlagReadFailureFallsBackToInjectingThePath() {
        CliSpecResolver resolver = new CliSpecResolver(
                () -> "C:\\tools\\opencode.exe",
                () -> {
                    throw new IllegalStateException("config unreadable");
                },
                () -> null);
        assertEquals("C:\\tools\\opencode.exe", resolver.resolve(null, HOME).getOpencodePath());
    }
}
