package com.opencodebuddy.provider.opencode;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.opencodebuddy.provider.common.DaemonBridge;
import com.opencodebuddy.provider.common.MessageCallback;
import com.opencodebuddy.provider.common.SDKResult;
import org.junit.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertEquals;

/**
 * Pins the transport half of model selection: the plugin does not call a
 * dedicated "set model" daemon method, it forwards the model on
 * {@code opencode.send}. The daemon then applies it to the v2 session
 * ({@code POST /api/session/{id}/model}) or seeds it at session creation.
 *
 * <p>If this field disappears, opencode v2 keeps running every turn on the
 * session's previous model, so it needs a regression test.</p>
 */
public class OpenCodeSDKBridgeModelTest {

    @Test
    public void sendMessageForwardsModelAndVariantOnOpencodeSend() {
        CapturingDaemonBridge daemon = new CapturingDaemonBridge();
        OpenCodeSDKBridge bridge = new OpenCodeSDKBridge(daemon, new Gson());

        bridge.sendMessage(
                "hello",
                "ses_123",
                "/repo",
                "opencode/new-model",
                "high",
                null,
                "default",
                "build",
                null,
                null,
                new NoopCallback());

        assertEquals("opencode.send", daemon.method);
        assertEquals("opencode/new-model", daemon.params.get("model").getAsString());
        assertEquals("high", daemon.params.get("reasoningEffort").getAsString());
        assertEquals("ses_123", daemon.params.get("sessionId").getAsString());
    }

    private static final class CapturingDaemonBridge extends DaemonBridge {
        private String method;
        private JsonObject params;

        private CapturingDaemonBridge() {
            super(null, null, null);
        }

        @Override
        public CompletableFuture<Boolean> sendCommand(
                String method,
                JsonObject params,
                DaemonOutputCallback callback,
                java.util.function.Consumer<String> onRequestId
        ) {
            this.method = method;
            this.params = params;
            if (onRequestId != null) {
                onRequestId.accept("req-test");
            }
            return CompletableFuture.completedFuture(true);
        }
    }

    private static final class NoopCallback implements MessageCallback {
        @Override
        public void onMessage(String type, String content) {
        }

        @Override
        public void onError(String error) {
        }

        @Override
        public void onComplete(SDKResult result) {
        }
    }
}
