package com.opencodebuddy.utils;

import com.intellij.openapi.diagnostic.Logger;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Standalone file logger for the plugin.
 *
 * <p>The IDE log (idea.log) is noisy and lives inside the sandbox, which makes it awkward to
 * hand to an agent for triage. This logger writes a dedicated, self-contained trace file that
 * covers the whole Java &lt;-&gt; webview bridge: every message sent by the webview, every
 * console.* forwarded from the webview, every JS function invoked by the backend, and the
 * daemon startup/status transitions.
 *
 * <p>Location (in priority order):
 * <ol>
 *   <li>System property {@code -Dopencode.log.file=/abs/path.log}</li>
 *   <li>Environment variable {@code OPENCODE_PLUGIN_LOG_FILE}</li>
 *   <li>Default: {@code ~/Library/Logs/opencode-idea-gui/opencode-plugin.log} (macOS),
 *       {@code ~/.opencode-idea-gui/opencode-plugin.log} elsewhere</li>
 * </ol>
 */
public final class PluginFileLogger {

    private static final Logger LOG = Logger.getInstance(PluginFileLogger.class);

    private static final String PROP_FILE = "opencode.log.file";
    private static final String ENV_FILE = "OPENCODE_PLUGIN_LOG_FILE";
    private static final long MAX_BYTES = 8L * 1024 * 1024;
    private static final int MAX_BACKUPS = 3;
    private static final int MAX_LINE = 4000;

    private static final PluginFileLogger INSTANCE = new PluginFileLogger();

    private final File file;
    private final SimpleDateFormat stampFormat = new SimpleDateFormat("MM-dd HH:mm:ss.SSS");
    private final Map<String, long[]> throttles = new HashMap<>();
    private BufferedWriter writer;
    private long writtenBytes;
    private boolean disabled;

    private PluginFileLogger() {
        this.file = resolveFile();
        open();
        Runtime.getRuntime().addShutdownHook(new Thread(PluginFileLogger.this::close, "opencode-file-logger-shutdown"));
    }

    public static PluginFileLogger getInstance() {
        return INSTANCE;
    }

    /** Absolute path of the trace file (never null, even when logging is disabled). */
    public static String path() {
        return INSTANCE.file == null ? "<unresolved>" : INSTANCE.file.getAbsolutePath();
    }

    public static void debug(String tag, String message) {
        INSTANCE.write("DEBUG", tag, message, null);
    }

    public static void info(String tag, String message) {
        INSTANCE.write("INFO", tag, message, null);
    }

    public static void warn(String tag, String message) {
        INSTANCE.write("WARN", tag, message, null);
    }

    public static void error(String tag, String message) {
        INSTANCE.write("ERROR", tag, message, null);
    }

    public static void error(String tag, String message, Throwable t) {
        INSTANCE.write("ERROR", tag, message, t);
    }

    /**
     * Rate-limited variant. Messages sharing the same {@code key} are emitted at most once per
     * {@code minIntervalMs}; the dropped ones are counted and reported as a single
     * {@code (suppressed N)} suffix on the next emitted line. Use this for high-frequency
     * traffic (heartbeats, stream deltas) so the trace stays readable.
     */
    public static void throttled(String level, String tag, String key, String message, long minIntervalMs) {
        INSTANCE.writeThrottled(level, tag, key, message, minIntervalMs);
    }

    public static void debugThrottled(String tag, String key, String message) {
        throttled("DEBUG", tag, key, message, 2000L);
    }

    // ------------------------------------------------------------------ internals

    private static File resolveFile() {
        String configured = System.getProperty(PROP_FILE);
        if (configured == null || configured.trim().isEmpty()) {
            configured = System.getenv(ENV_FILE);
        }
        if (configured != null && !configured.trim().isEmpty()) {
            return new File(configured.trim());
        }
        String home = com.opencodebuddy.util.PlatformUtils.getHomeDirectory();
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac") || os.contains("darwin")) {
            return new File(home, "Library/Logs/opencode-idea-gui/opencode-plugin.log");
        }
        return new File(home, ".opencode-idea-gui/opencode-plugin.log");
    }

    private synchronized void open() {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                disabled = true;
                LOG.warn("[FileLog] Cannot create log directory: " + parent);
                return;
            }
            boolean fresh = !file.exists();
            this.writer = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8), 64 * 1024);
            this.writtenBytes = file.length();
            if (fresh) {
                writeLocked("INFO", "FileLog", "Trace file created: " + file.getAbsolutePath(), null);
            } else {
                writeLocked("INFO", "FileLog",
                        "--- new session, appending to " + file.getAbsolutePath() + " ---", null);
            }
            flushLocked();
        } catch (Exception e) {
            disabled = true;
            LOG.warn("[FileLog] Failed to open " + file, e);
        }
    }

    private void write(String level, String tag, String message, Throwable t) {
        synchronized (this) {
            writeLocked(level, tag, message, t);
        }
    }

    private void writeThrottled(String level, String tag, String key, String message, long minIntervalMs) {
        synchronized (this) {
            long now = System.currentTimeMillis();
            long[] state = throttles.computeIfAbsent(tag + "#" + key, k -> new long[]{0L, 0L});
            if (now - state[0] < minIntervalMs) {
                state[1]++;
                return;
            }
            long suppressed = state[1];
            state[0] = now;
            state[1] = 0L;
            String body = suppressed > 0 ? message + " (suppressed " + suppressed + " similar)" : message;
            writeLocked(level, tag, body, null);
        }
    }

    private void writeLocked(String level, String tag, String message, Throwable t) {
        if (disabled || writer == null) {
            return;
        }
        try {
            String line = format(level, tag, message);
            writer.write(line);
            writer.newLine();
            writtenBytes += line.length() + 1;
            if (t != null) {
                String stack = formatThrowable(t);
                writer.write(stack);
                writer.newLine();
                writtenBytes += stack.length() + 1;
            }
            flushLocked();
            if (writtenBytes > MAX_BYTES) {
                rotateLocked();
            }
        } catch (IOException e) {
            disabled = true;
            LOG.warn("[FileLog] Write failed: " + e.getMessage());
        }
    }

    private String format(String level, String tag, String message) {
        String stamp;
        synchronized (stampFormat) {
            stamp = stampFormat.format(new Date());
        }
        String body = message == null ? "" : message;
        body = body.replace('\n', ' ').replace('\r', ' ');
        if (body.length() > MAX_LINE) {
            body = body.substring(0, MAX_LINE) + "...<truncated " + (message.length() - MAX_LINE) + " chars>";
        }
        return stamp + " [" + Thread.currentThread().getName() + "] " + level + " " + tag + " | " + body;
    }

    private static String formatThrowable(Throwable t) {
        StringBuilder sb = new StringBuilder();
        sb.append("    caused by ").append(t.getClass().getName()).append(": ").append(t.getMessage());
        StackTraceElement[] stack = t.getStackTrace();
        int limit = Math.min(stack.length, 8);
        for (int i = 0; i < limit; i++) {
            sb.append("\n      at ").append(stack[i]);
        }
        return sb.toString();
    }

    private void flushLocked() {
        try {
            if (writer != null) {
                writer.flush();
            }
        } catch (IOException ignored) {
            // best effort
        }
    }

    private void rotateLocked() {
        try {
            writer.close();
        } catch (IOException ignored) {
            // best effort
        }
        for (int i = MAX_BACKUPS; i >= 1; i--) {
            File src = i == 1 ? file : new File(file.getAbsolutePath() + "." + (i - 1));
            File dst = new File(file.getAbsolutePath() + "." + i);
            if (dst.exists() && !dst.delete()) {
                // ignore
            }
            if (src.exists() && !src.renameTo(dst)) {
                // ignore
            }
        }
        open();
    }

    private synchronized void close() {
        try {
            if (writer != null) {
                writer.flush();
                writer.close();
            }
        } catch (IOException ignored) {
            // best effort
        } finally {
            writer = null;
        }
    }
}
