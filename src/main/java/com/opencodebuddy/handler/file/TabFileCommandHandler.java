package com.opencodebuddy.handler.file;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.opencodebuddy.handler.core.HandlerContext;
import com.opencodebuddy.session.OpencodeSession;
import com.opencodebuddy.tab.TabCommand;
import com.opencodebuddy.tab.TabEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * {@code files.list} — the "@" candidate command, scoped to the tab that sent it.
 *
 * <p>This replaces the old window-level {@code list_files} message (which is
 * kept as a legacy shim on {@link FileHandler}) for the same reason the session
 * commands moved: the directory must come from the <em>requesting</em> tab, not
 * from "the active tab at the moment the reply is produced". Two tabs with
 * different cwds must see their own project's files, and a late reply must land
 * on the tab that asked.</p>
 *
 * <p>The candidates themselves come from opencode (see {@link OpenCodeFileList});
 * the host only maps them and routes the answer back as a {@code files.listed}
 * event carrying {@code {files, requestId}}.</p>
 */
public final class TabFileCommandHandler {

    private static final Logger LOG = Logger.getInstance(TabFileCommandHandler.class);
    private static final Gson GSON = new Gson();

    private final Supplier<HandlerContext> contextSupplier;

    public TabFileCommandHandler(HandlerContext context) {
        this(() -> context);
    }

    public TabFileCommandHandler(Supplier<HandlerContext> contextSupplier) {
        this.contextSupplier = contextSupplier;
    }

    public void list(TabCommand command) {
        String tabId = command.tabId().value();
        String query = nullToEmpty(command.string("query"));
        String currentPath = nullToEmpty(command.string("currentPath"));
        Long requestId = requestId(command);
        HandlerContext context = contextSupplier != null ? contextSupplier.get() : null;
        String cwd = resolveCwd(context, tabId);

        if (context == null) {
            LOG.warn("[TabFileCommandHandler] no handler context; answering empty for tab=" + tabId);
            publish(null, tabId, new ArrayList<>(), requestId);
            return;
        }

        OpenCodeFileList.fetch(context, query, currentPath, cwd)
                .thenAccept(files -> {
                    LOG.info("[TabFileCommandHandler] files.list tab=" + tabId
                            + " query=\"" + query + "\" path=\"" + currentPath + "\""
                            + " cwd=" + (cwd.isEmpty() ? "-" : cwd)
                            + " n=" + files.size());
                    publish(context, tabId, files, requestId);
                })
                .exceptionally(e -> {
                    // OpenCodeFileList already degrades to an empty list; this is
                    // the last resort so the webview is never left waiting.
                    LOG.warn("[TabFileCommandHandler] files.list failed for tab=" + tabId
                            + ": " + e.getMessage());
                    publish(context, tabId, new ArrayList<>(), requestId);
                    return null;
                });
    }

    /**
     * The cwd of the tab that sent the command. Falls back to the window's
     * effective directory only when the tab has no session yet.
     */
    private String resolveCwd(HandlerContext context, String tabId) {
        if (context != null) {
            OpencodeSession session = context.getSessionForTab(tabId);
            if (session != null) {
                String cwd = session.getCwd();
                if (cwd != null && !cwd.isBlank()) {
                    return cwd;
                }
            }
            String fallback = context.resolveEffectiveWorkingDirectory();
            if (fallback != null && !fallback.isBlank()) {
                return fallback;
            }
        }
        return "";
    }

    private void publish(HandlerContext context, String tabId, List<JsonObject> files, Long requestId) {
        if (context != null) {
            JsonObject payload = new JsonObject();
            payload.add("files", GSON.toJsonTree(files));
            if (requestId != null) {
                payload.addProperty("requestId", requestId);
            }
            String data = GSON.toJson(payload);
            context.publishEvent(TabEvent.of("files.listed")
                    .tab(tabId)
                    .field("data", data)
                    .build());
        }
    }

    private static Long requestId(TabCommand command) {
        try {
            JsonObject payload = command.payload();
            if (payload != null && payload.has("requestId") && !payload.get("requestId").isJsonNull()) {
                return payload.get("requestId").getAsLong();
            }
        } catch (RuntimeException ignored) {
            // A non-numeric requestId just means "no id"; the webview then falls
            // back to its most recent pending request.
        }
        return null;
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }
}
