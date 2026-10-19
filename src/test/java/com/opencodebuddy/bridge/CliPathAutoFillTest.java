package com.opencodebuddy.bridge;

import com.opencodebuddy.util.PlatformUtils;
import org.junit.Assume;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the "fill the setting from the daemon's own resolution" rule.
 *
 * <p>Everything runs through the {@link CliPathAutoFill.SettingsStore} seam, so no
 * test reads or writes the real {@code config.json}.</p>
 */
public class CliPathAutoFillTest {

    private static final String KNOWN_DIR = "known-dir";
    private static final String BARE = "bare";

    /** Records what the fill logic decided to persist. */
    private static final class RecordingStore implements CliPathAutoFill.SettingsStore {
        private final String current;
        private final boolean failOnWrite;
        private String saved;
        private int saveCount;

        RecordingStore(String current) {
            this(current, false);
        }

        RecordingStore(String current, boolean failOnWrite) {
            this.current = current;
            this.failOnWrite = failOnWrite;
        }

        @Override
        public String currentCliPath() {
            return current;
        }

        @Override
        public void saveAutoDetected(String cliPath) throws IOException {
            if (failOnWrite) {
                throw new IOException("config.json is read-only");
            }
            saved = cliPath;
            saveCount++;
        }
    }

    private static String existingBinary() throws IOException {
        Path binary = Files.createTempFile("opencode-auto-fill-", ".exe");
        binary.toFile().deleteOnExit();
        return binary.toAbsolutePath().toString();
    }

    private static String missingBinary() {
        return Paths.get(System.getProperty("java.io.tmpdir"), "opencode-missing-for-fill-test").toString();
    }

    @Test
    public void fillsAnEmptySettingWithTheResolvedPath() throws Exception {
        String resolved = existingBinary();
        RecordingStore store = new RecordingStore(null);

        assertTrue(CliPathAutoFill.apply(store, resolved, KNOWN_DIR));
        assertEquals(resolved, store.saved);
        assertEquals(1, store.saveCount);
    }

    @Test
    public void treatsABlankSettingAsEmpty() throws Exception {
        String resolved = existingBinary();
        RecordingStore store = new RecordingStore("   ");

        assertTrue(CliPathAutoFill.apply(store, resolved, KNOWN_DIR));
        assertEquals(resolved, store.saved);
    }

    @Test
    public void neverOverwritesAnExplicitUserSetting() throws Exception {
        RecordingStore store = new RecordingStore("C:\\tools\\opencode.exe");

        assertFalse(CliPathAutoFill.apply(store, existingBinary(), KNOWN_DIR));
        assertNull(store.saved);
    }

    @Test
    public void doesNotRefillWhenTheDaemonReportsTheSettingItself() throws Exception {
        // source=settings means the daemon read our own value back as L0: nothing
        // new to learn, and rewriting would only bump the config file on every boot.
        RecordingStore store = new RecordingStore(null);

        assertFalse(CliPathAutoFill.apply(store, existingBinary(), "settings"));
        assertEquals(0, store.saveCount);
    }

    @Test
    public void ignoresAMissingBinary() {
        RecordingStore store = new RecordingStore(null);

        assertFalse(CliPathAutoFill.apply(store, missingBinary(), KNOWN_DIR));
        assertNull(store.saved);
    }

    @Test
    public void ignoresBareCommandNamesFromTheLastTier() {
        // L4 resolves to a bare "opencode"; it is not a path, and CliSpecResolver
        // would refuse to inject it as L0 on the next launch.
        assertFalse(CliPathAutoFill.shouldFill("opencode", BARE, null));
    }

    @Test
    public void ignoresBlankAndNullReports() {
        assertFalse(CliPathAutoFill.shouldFill(null, KNOWN_DIR, null));
        assertFalse(CliPathAutoFill.shouldFill("", KNOWN_DIR, null));
        assertFalse(CliPathAutoFill.shouldFill("   ", KNOWN_DIR, null));
    }

    @Test
    public void trustsWslPathsBecauseTheHostCannotCheckThem() {
        // A Linux path cannot be validated from the IDE host; every valid one would
        // look missing. Only meaningful on Windows, where WSL paths are recognised.
        Assume.assumeTrue(PlatformUtils.isWindows());
        assertTrue(CliPathAutoFill.shouldFill("/usr/local/bin/opencode", KNOWN_DIR, null));
    }

    @Test
    public void writeFailureIsSwallowedInsteadOfPropagating() throws Exception {
        RecordingStore store = new RecordingStore(null, true);

        assertFalse(CliPathAutoFill.apply(store, existingBinary(), KNOWN_DIR));
        assertEquals(0, store.saveCount);
    }

    @Test
    public void toleratesANullStore() {
        assertFalse(CliPathAutoFill.apply(null, "C:\\tools\\opencode.exe", KNOWN_DIR));
    }
}
