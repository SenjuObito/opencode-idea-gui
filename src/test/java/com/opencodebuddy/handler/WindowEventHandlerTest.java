package com.opencodebuddy.handler;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.opencodebuddy.handler.core.HandlerContext;
import com.opencodebuddy.provider.common.DaemonBridge;
import com.opencodebuddy.provider.opencode.OpenCodeSDKBridge;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for window-level bridge event dispatch, including share/unshare callbacks,
 * restored-history DOM commit acknowledgment, and surface-damage phase acknowledgments.
 */
public class WindowEventHandlerTest {

    @Test
    public void dispatchesShareSessionSuccess() {
        MockJsCallback jsCallback = new MockJsCallback();
        OpenCodeSDKBridge bridge = new OpenCodeSDKBridge(null, new Gson()) {
            @Override
            public CompletableFuture<Boolean> request(
                    String method, JsonObject params, DaemonBridge.DaemonOutputCallback callback) {
                assertEquals("opencode.shareSession", method);
                callback.onLine("{\"success\":true,\"share\":{\"url\":\"https://opencode.ai/share/session_123\"}}");
                callback.onComplete(true);
                return CompletableFuture.completedFuture(true);
            }
        };

        HandlerContext context = new HandlerContext(null, bridge, null, jsCallback);
        WindowEventHandler handler = new WindowEventHandler(context, new NoOpCallback());

        assertTrue(handler.handle("share_session", "session_123"));
        assertEquals(1, jsCallback.calls.size());
        assertEquals("onShareSuccess:https://opencode.ai/share/session_123", jsCallback.calls.get(0));
    }

    @Test
    public void dispatchesShareSessionErrorOnFailure() {
        MockJsCallback jsCallback = new MockJsCallback();
        OpenCodeSDKBridge bridge = new OpenCodeSDKBridge(null, new Gson()) {
            @Override
            public CompletableFuture<Boolean> request(
                    String method, JsonObject params, DaemonBridge.DaemonOutputCallback callback) {
                assertEquals("opencode.shareSession", method);
                callback.onError("Share disabled");
                callback.onComplete(false);
                return CompletableFuture.completedFuture(false);
            }
        };

        HandlerContext context = new HandlerContext(null, bridge, null, jsCallback);
        WindowEventHandler handler = new WindowEventHandler(context, new NoOpCallback());

        assertTrue(handler.handle("share_session", "session_123"));
        assertTrue(jsCallback.calls.contains("onShareError:Share disabled") || jsCallback.calls.contains("onShareError:"));
    }

    @Test
    public void dispatchesUnshareSessionSuccess() {
        MockJsCallback jsCallback = new MockJsCallback();
        OpenCodeSDKBridge bridge = new OpenCodeSDKBridge(null, new Gson()) {
            @Override
            public CompletableFuture<Boolean> request(
                    String method, JsonObject params, DaemonBridge.DaemonOutputCallback callback) {
                assertEquals("opencode.unshareSession", method);
                callback.onLine("{\"success\":true}");
                callback.onComplete(true);
                return CompletableFuture.completedFuture(true);
            }
        };

        HandlerContext context = new HandlerContext(null, bridge, null, jsCallback);
        WindowEventHandler handler = new WindowEventHandler(context, new NoOpCallback());

        assertTrue(handler.handle("unshare_session", "session_123"));
        assertEquals(1, jsCallback.calls.size());
        assertEquals("onUnshareSuccess:", jsCallback.calls.get(0));
    }

    @Test
    public void dispatchesUnshareSessionError() {
        MockJsCallback jsCallback = new MockJsCallback();
        OpenCodeSDKBridge bridge = new OpenCodeSDKBridge(null, new Gson()) {
            @Override
            public CompletableFuture<Boolean> request(
                    String method, JsonObject params, DaemonBridge.DaemonOutputCallback callback) {
                assertEquals("opencode.unshareSession", method);
                callback.onError("Unshare error");
                callback.onComplete(false);
                return CompletableFuture.completedFuture(false);
            }
        };

        HandlerContext context = new HandlerContext(null, bridge, null, jsCallback);
        WindowEventHandler handler = new WindowEventHandler(context, new NoOpCallback());

        assertTrue(handler.handle("unshare_session", "session_123"));
        assertTrue(jsCallback.calls.contains("onUnshareError:Unshare error") || jsCallback.calls.contains("onUnshareError:"));
    }

    /** Verifies that history_dom_committed is supported and dispatched exactly once. */
    @Test
    public void dispatchesHistoryDomCommittedCallback() {
        AtomicReference<Long> historyCommitEpoch = new AtomicReference<>();
        HandlerContext context = new HandlerContext(null, null, null, new MockJsCallback());
        WindowEventHandler handler = new WindowEventHandler(context, new NoOpCallback() {
            @Override
            public void onHistoryRenderComplete(long commitEpoch) {
                historyCommitEpoch.set(commitEpoch);
            }
        });

        assertTrue(handler.handle("history_dom_committed", "7"));
        assertEquals(Long.valueOf(7L), historyCommitEpoch.get());
    }

    /** Verifies that a tokenized surface phase acknowledgment is parsed without losing identity. */
    @Test
    public void dispatchesSurfaceDamageAppliedCallback() {
        AtomicReference<String> observed = new AtomicReference<>();
        HandlerContext context = new HandlerContext(null, null, null, new MockJsCallback());
        WindowEventHandler handler = new WindowEventHandler(context, new NoOpCallback() {
            @Override
            public void onSurfaceDamageApplied(String token, String phase, boolean applied) {
                observed.set(token + ":" + phase + ":" + applied);
            }
        });

        assertTrue(handler.handle(
                "surface_damage_applied",
                "{\"token\":\"4:5:6:7\",\"phase\":\"B\",\"applied\":true}"));
        assertEquals("4:5:6:7:B:true", observed.get());
    }

    private static final class MockJsCallback implements HandlerContext.JsCallback {
        final List<String> calls = new ArrayList<>();

        @Override
        public void callJavaScript(String functionName, String... args) {
            calls.add(functionName + ":" + (args != null ? String.join(",", args) : ""));
        }

        @Override
        public String escapeJs(String str) {
            return str != null ? str : "";
        }
    }

    /** Callback adapter that keeps unrelated window events inert for focused event tests. */
    private static class NoOpCallback implements WindowEventHandler.Callback {
        @Override public void onHeartbeat(String content) { }
        @Override public void onTabLoadingChanged(boolean loading) { }
        @Override public void onTabStatusChanged(String status) { }
        @Override public void onCreateNewSession() { }
        @Override public void onFrontendReady() { }
        @Override public void onHistoryRenderComplete(long commitEpoch) { }
        @Override public void onSurfaceDamageApplied(
                String token,
                String phase,
                boolean applied
        ) { }
        @Override public void onRefreshSlashCommands() { }
    }
}
