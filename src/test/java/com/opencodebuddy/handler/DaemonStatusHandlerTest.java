package com.opencodebuddy.handler;

import com.opencodebuddy.handler.core.HandlerContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the daemon/serve status push chain. The webview keeps its
 * "starting OpenCode service" spinner until it receives
 * {@code {alive:true, serveReady:true}}, so every terminal state the handler
 * pushes must be one the frontend can leave the loading state on
 * ({@code serveReady:true} or {@code alive:false}).
 */
public class DaemonStatusHandlerTest {

    /** Records every window.updateDaemonStatus payload the handler pushes. */
    private static final class RecordingJsCallback implements HandlerContext.JsCallback {
        final List<JsonObject> pushed = new ArrayList<>();

        @Override
        public void callJavaScript(String functionName, String... args) {
            if ("window.updateDaemonStatus".equals(functionName) && args.length > 0) {
                pushed.add(JsonParser.parseString(args[0]).getAsJsonObject());
            }
        }

        @Override
        public String escapeJs(String str) {
            // Mirror JsUtils quoting so the JSON payload stays parseable.
            return str == null ? "" : str.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n");
        }
    }

    /** Scriptable daemon probe used to drive the handler without a live daemon. */
    private static final class ScriptedProbe implements DaemonStatusHandler.DaemonStatusProbe {
        boolean alive;
        CompletableFuture<Boolean> preconnectResult = new CompletableFuture<>();

        @Override
        public boolean isDaemonAlive() {
            return alive;
        }

        @Override
        public CompletableFuture<Boolean> preconnect(String sessionId, String cwd) {
            return preconnectResult;
        }
    }

    private static HandlerContext contextWith(RecordingJsCallback js) {
        return new HandlerContext(null, null, null, js);
    }

    /** check_daemon_status must be claimed by this handler. */
    @Test
    public void supportsCheckDaemonStatus() {
        DaemonStatusHandler handler = new DaemonStatusHandler(
                contextWith(new RecordingJsCallback()), new ScriptedProbe());
        assertTrue(handler.handle("check_daemon_status", ""));
    }

    /** Successful preconnect pushes {alive:true, serveReady:true} — clears loading. */
    @Test
    public void pushesServeReadyAfterPreconnectSuccess() throws Exception {
        RecordingJsCallback js = new RecordingJsCallback();
        ScriptedProbe probe = new ScriptedProbe();
        DaemonStatusHandler handler = new DaemonStatusHandler(contextWith(js), probe);

        CompletableFuture<Void> pushed = new CompletableFuture<>();
        handler.checkAndPush();
        // The immediate push lands synchronously; the preconnect result lands
        // on the pooled executor. Complete the preconnect and await the push.
        probe.preconnectResult.complete(true);
        waitForStatusCount(js, 2);

        assertEquals("{\"alive\":true,\"serveReady\":false}",
                js.pushed.get(0).toString());
        assertEquals("{\"alive\":true,\"serveReady\":true}",
                js.pushed.get(1).toString());
        pushed.complete(null);
    }

    /** Failed preconnect pushes a retryable state — never a permanent spinner. */
    @Test
    public void pushesRetryableStateAfterPreconnectFailure() throws Exception {
        RecordingJsCallback js = new RecordingJsCallback();
        ScriptedProbe probe = new ScriptedProbe();
        DaemonStatusHandler handler = new DaemonStatusHandler(contextWith(js), probe);

        handler.checkAndPush();
        probe.preconnectResult.complete(false);
        waitForStatusCount(js, 2);

        // alive:false even though the daemon may be up: the webview has no
        // "alive but serve broken" state, so the retryable row is the only exit.
        assertEquals("{\"alive\":false,\"serveReady\":false}",
                js.pushed.get(1).toString());
    }

    /** Preconnect exceptions also land in the retryable state. */
    @Test
    public void pushesRetryableStateAfterPreconnectException() throws Exception {
        RecordingJsCallback js = new RecordingJsCallback();
        ScriptedProbe probe = new ScriptedProbe();
        DaemonStatusHandler handler = new DaemonStatusHandler(contextWith(js), probe);

        handler.checkAndPush();
        probe.preconnectResult.completeExceptionally(new RuntimeException("daemon spawn failed"));
        waitForStatusCount(js, 2);

        assertEquals("{\"alive\":false,\"serveReady\":false}",
                js.pushed.get(1).toString());
    }

    /** Daemon death flips the webview to the retryable state immediately. */
    @Test
    public void daemonDeathPushesNotRunning() {
        RecordingJsCallback js = new RecordingJsCallback();
        DaemonStatusHandler handler = new DaemonStatusHandler(
                contextWith(js), new ScriptedProbe());

        handler.onDaemonDied();

        assertEquals(1, js.pushed.size());
        assertEquals("{\"alive\":false,\"serveReady\":false}",
                js.pushed.get(0).toString());
    }

    private static void waitForStatusCount(RecordingJsCallback js, int expected)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (js.pushed.size() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals("expected " + expected + " status pushes", expected, js.pushed.size());
    }
}
