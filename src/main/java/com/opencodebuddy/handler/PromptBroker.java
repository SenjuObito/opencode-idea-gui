package com.opencodebuddy.handler;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns every in-flight prompt and the session it belongs to.
 *
 * <p>The maps used to live in {@link PermissionHandler} next to the dialog
 * rendering code. That made the handler the only place that knew which session
 * a permission/question/plan future belonged to, so clearing a closed tab and
 * settling a late reply had to duplicate the same bookkeeping at every call
 * site. Keeping the state here, keyed by request id with an explicit owner,
 * gives both paths one source of truth.</p>
 *
 * <p>Thread-safe: prompts arrive from daemon reader threads while decisions
 * arrive from the webview/JCEF thread.</p>
 */
final class PromptBroker {

    static final class PendingQuestion {
        final String sessionId;
        final JsonArray questions;
        final String toolName;
        final String directory;

        PendingQuestion(String sessionId, JsonArray questions, String toolName, String directory) {
            this.sessionId = sessionId;
            this.questions = questions;
            this.toolName = toolName;
            this.directory = directory;
        }
    }

    final Map<String, CompletableFuture<Integer>> permissions = new ConcurrentHashMap<>();
    final Map<String, String> permissionSessions = new ConcurrentHashMap<>();

    final Map<String, PendingQuestion> questions = new ConcurrentHashMap<>();
    final Map<String, CompletableFuture<JsonObject>> askFutures = new ConcurrentHashMap<>();
    final Map<String, String> askSessions = new ConcurrentHashMap<>();

    final Map<String, CompletableFuture<JsonObject>> planFutures = new ConcurrentHashMap<>();
    final Map<String, String> planSessions = new ConcurrentHashMap<>();
}
