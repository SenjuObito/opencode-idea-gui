package com.opencodebuddy.handler.history;

import com.opencodebuddy.bridge.NodeDetector;
import com.opencodebuddy.handler.core.HandlerContext;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

/**
 * Service for loading history sessions and injecting them into the frontend.
 *
 * <p>opencode-only: session transcripts are restored through the session load
 * callback (see {@link SessionLifecycleManager}-side wiring), which pulls the
 * messages from the daemon via {@code opencode.listMessages}. The injector only
 * resolves the payload and drives the frontend load-completion handshake.</p>
 */
public class HistoryMessageInjector {

    private static final Logger LOG = Logger.getInstance(HistoryMessageInjector.class);
    static final int HISTORY_BATCH_TARGET_CHAR_LIMIT = 180_000;

    private final HandlerContext context;

    HistoryMessageInjector(HandlerContext context) {
        this.context = context;
    }

    /**
     * Load a history session.
     */
    void handleLoadSession(String sessionId, String currentProvider, HistoryHandler.SessionLoadCallback sessionLoadCallback) {
        String provider = currentProvider;
        String resolvedSessionId = sessionId;
        String model = null;

        try {
            JsonObject payload = new com.google.gson.Gson().fromJson(sessionId, JsonObject.class);
            if (payload != null) {
                if (payload.has("sessionId") && !payload.get("sessionId").isJsonNull()) {
                    resolvedSessionId = payload.get("sessionId").getAsString();
                }
                if (payload.has("provider") && !payload.get("provider").isJsonNull()) {
                    provider = payload.get("provider").getAsString();
                }
                if (payload.has("model") && !payload.get("model").isJsonNull()) {
                    String m = payload.get("model").getAsString();
                    if (m != null && !m.trim().isEmpty()) {
                        model = m.trim();
                    }
                }
            }
        } catch (Exception ignored) {
            // Backward compatible: legacy payload is the raw sessionId string.
        }

        String rawPath = context.resolveEffectiveWorkingDirectory();
        String nodePath = NodeDetector.getInstance().getCachedNodePath();
        String projectPath = NodeDetector.isWslPath(nodePath) ? NodeDetector.convertToWslPath(rawPath) : rawPath;
        if (projectPath == null) {
            LOG.warn("[HistoryHandler] Project base path is null");
            notifyHistoryLoadComplete();
            return;
        }
        LOG.info("[HistoryHandler] Loading history session: " + resolvedSessionId
                + " from project: " + projectPath + ", provider: " + provider
                + (model != null ? ", model: " + model : ""));

        if (sessionLoadCallback != null) {
            sessionLoadCallback.onLoadSession(resolvedSessionId, projectPath, provider, model);
        } else {
            LOG.warn("[HistoryHandler] WARNING: No session load callback set");
            notifyHistoryLoadComplete();
        }
    }

    void notifyHistoryLoadComplete() {
        ApplicationManager.getApplication().invokeLater(() -> {
            String jsCode = "if (window.historyLoadComplete) { " +
                                    "  try { " +
                                    "    window.historyLoadComplete(); " +
                                    "  } catch(e) { " +
                                    "    console.error('[HistoryHandler] historyLoadComplete callback failed:', e); " +
                                    "  } " +
                                    "}";
            context.executeJavaScriptOnEDT(jsCode);
        });
    }

    /**
     * Split a large payload into escaped-size-bounded chunks so long histories
     * do not exceed the JavaScript bridge's single-call limits.
     */
    static java.util.List<String> splitHistoryPayload(String payload) {
        java.util.List<String> chunks = new java.util.ArrayList<>();
        if (payload == null || payload.isEmpty()) {
            return chunks;
        }

        StringBuilder current = new StringBuilder(HISTORY_BATCH_TARGET_CHAR_LIMIT);
        int escapedChars = 0;
        for (int i = 0; i < payload.length(); i++) {
            char value = payload.charAt(i);
            int charCount = escapedCharCount(current, value);
            boolean surrogatePair = Character.isHighSurrogate(value)
                    && i + 1 < payload.length()
                    && Character.isLowSurrogate(payload.charAt(i + 1));
            if (surrogatePair) {
                charCount += 1;
            }

            if (escapedChars + charCount > HISTORY_BATCH_TARGET_CHAR_LIMIT && current.length() > 0) {
                chunks.add(current.toString());
                current.setLength(0);
                escapedChars = 0;
                charCount = escapedCharCount(current, value) + (surrogatePair ? 1 : 0);
            }

            current.append(value);
            if (surrogatePair) {
                current.append(payload.charAt(++i));
            }
            escapedChars += charCount;
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    private static int escapedCharCount(StringBuilder current, char value) {
        if (value == '\u0085' || value == '\u2028' || value == '\u2029') {
            return 6;
        }
        if (value == '\\' || value == '\'' || value == '"' || value == '`'
                || value == '\n' || value == '\r' || value == '\t'
                || value == '\b' || value == '\f' || value == '\0') {
            return 2;
        }
        if (value == '/' && current.length() > 0 && current.charAt(current.length() - 1) == '<') {
            return 2;
        }
        return 1;
    }
}
