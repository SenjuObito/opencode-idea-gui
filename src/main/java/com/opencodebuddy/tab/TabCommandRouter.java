package com.opencodebuddy.tab;

import com.intellij.openapi.diagnostic.Logger;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The single entry point for tab-scoped commands.
 *
 * <p>Responsibilities kept here so no call site repeats them:</p>
 * <ul>
 *   <li>resolve {@code tabId} against the registry (never "the active tab");</li>
 *   <li>drop duplicate {@code requestId}s after a webview reload;</li>
 *   <li>run the handler on the tab's {@link TabSessionActor}, preserving per-tab
 *       order against daemon callbacks and close.</li>
 * </ul>
 *
 * <p>{@code tab.create} is the only command that may name a tab that does not
 * exist yet; it runs inline because it has no per-tab state to race with and
 * every later command for that tab must observe the created record.</p>
 */
public final class TabCommandRouter {

    private static final Logger LOG = Logger.getInstance(TabCommandRouter.class);
    private static final int MAX_REMEMBERED_REQUEST_IDS = 2_048;
    private static final Set<String> INLINE_TYPES = Set.of("tab.create");

    public enum Result {
        ROUTED,
        DUPLICATE,
        UNKNOWN_TAB,
        UNKNOWN_COMMAND,
        CLOSED
    }

    @FunctionalInterface
    public interface CommandHandler {
        void handle(TabCommand command);
    }

    private final TabRegistry registry;
    private final Map<String, CommandHandler> handlers = new HashMap<>();
    private final LinkedHashMap<String, Boolean> handledRequestIds =
            new LinkedHashMap<>(64, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > MAX_REMEMBERED_REQUEST_IDS;
                }
            };

    public TabCommandRouter(TabRegistry registry) {
        this.registry = registry;
    }

    public void register(String type, CommandHandler handler) {
        if (type == null || type.isBlank() || handler == null) {
            throw new IllegalArgumentException("type and handler are required");
        }
        handlers.put(type, handler);
    }

    /** True when this type belongs to the tab registry, not the window dispatcher. */
    public boolean handles(String type) {
        return type != null && handlers.containsKey(type);
    }

    public Result route(TabCommand command) {
        if (command == null) {
            return Result.UNKNOWN_COMMAND;
        }

        String requestId = command.requestId();
        if (requestId != null && !requestId.isBlank() && isRemembered(requestId)) {
            return Result.DUPLICATE;
        }

        CommandHandler handler = handlers.get(command.type());
        if (handler == null) {
            LOG.warn("[TabCommandRouter] unknown command: " + command.type());
            return Result.UNKNOWN_COMMAND;
        }

        if (INLINE_TYPES.contains(command.type())) {
            try {
                handler.handle(command);
                remember(requestId);
                return Result.ROUTED;
            } catch (RuntimeException e) {
                LOG.warn("[TabCommandRouter] inline command failed: " + command.type(), e);
                return Result.UNKNOWN_TAB;
            }
        }

        TabRecord record = registry.get(command.tabId());
        if (record == null || record.isClosed()) {
            LOG.info("[TabCommandRouter] " + command.type() + " for unknown/closed tab "
                    + command.tabId().value());
            return Result.UNKNOWN_TAB;
        }

        // Reserve the id before submitting so a concurrent replay is rejected;
        // a rejected submission releases it and may be retried.
        if (!remember(requestId)) {
            return Result.DUPLICATE;
        }
        boolean accepted = record.getActor().submit(() -> {
            // The tab may have closed between the lookup above and the actor
            // picking this up; a queued command must not revive it.
            if (record.isClosed()) {
                return;
            }
            try {
                handler.handle(command);
            } catch (RuntimeException e) {
                LOG.warn("[TabCommandRouter] command failed: " + command.type()
                        + " tab=" + command.tabId().value(), e);
            }
        });
        if (!accepted) {
            forget(requestId);
            return Result.CLOSED;
        }
        return Result.ROUTED;
    }

    private boolean isRemembered(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return false;
        }
        synchronized (handledRequestIds) {
            return handledRequestIds.containsKey(requestId);
        }
    }

    /** @return false when the id was already taken. */
    private boolean remember(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return true;
        }
        synchronized (handledRequestIds) {
            if (handledRequestIds.containsKey(requestId)) {
                return false;
            }
            handledRequestIds.put(requestId, Boolean.TRUE);
            return true;
        }
    }

    private void forget(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return;
        }
        synchronized (handledRequestIds) {
            handledRequestIds.remove(requestId);
        }
    }

    int rememberedRequestIdCount() {
        synchronized (handledRequestIds) {
            return handledRequestIds.size();
        }
    }
}
