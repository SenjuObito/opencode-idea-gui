package com.opencodebuddy.handler;

import com.opencodebuddy.handler.core.BaseMessageHandler;
import com.opencodebuddy.handler.core.HandlerContext;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;

/**
 * Handler for clipboard operations from webview.
 * Security: rate limiting and size bounds protect against abuse from WebView JS.
 */
public class ClipboardHandler extends BaseMessageHandler {

    private static final Logger LOG = Logger.getInstance(ClipboardHandler.class);
    private static final String[] SUPPORTED_TYPES = {"read_clipboard", "write_clipboard", "copy_to_clipboard", "clipboard.write"};

    private static final long MIN_READ_INTERVAL_MS = 200;
    private static final int MAX_CLIPBOARD_WRITE_SIZE = 10 * 1024 * 1024; // 10 MB

    @FunctionalInterface
    public interface ClipboardSupplier {
        Clipboard get() throws Exception;
    }

    private final ClipboardSupplier clipboardSupplier;
    private static volatile Clipboard fallbackClipboard;
    private volatile long lastReadTime = 0;

    public ClipboardHandler(HandlerContext context) {
        this(context, () -> Toolkit.getDefaultToolkit().getSystemClipboard());
    }

    public ClipboardHandler(HandlerContext context, ClipboardSupplier clipboardSupplier) {
        super(context);
        this.clipboardSupplier = clipboardSupplier != null ? clipboardSupplier : () -> Toolkit.getDefaultToolkit().getSystemClipboard();
    }

    @Override
    public String[] getSupportedTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public boolean handle(String type, String content) {
        return switch (type) {
            case "read_clipboard" -> {
                handleReadClipboard();
                yield true;
            }
            case "write_clipboard", "copy_to_clipboard", "clipboard.write" -> {
                handleWriteClipboard(extractText(content));
                yield true;
            }
            default -> false;
        };
    }

    private Clipboard getClipboard() {
        try {
            return clipboardSupplier.get();
        } catch (Throwable t) {
            LOG.debug("Unable to obtain system clipboard, falling back to in-memory clipboard: " + t.getMessage());
            if (fallbackClipboard == null) {
                synchronized (ClipboardHandler.class) {
                    if (fallbackClipboard == null) {
                        fallbackClipboard = new Clipboard("HeadlessFallbackClipboard");
                    }
                }
            }
            return fallbackClipboard;
        }
    }

    private void handleReadClipboard() {
        // Rate limiting to prevent clipboard-monitoring abuse (checked synchronously before dispatch)
        long now = System.currentTimeMillis();
        if (now - lastReadTime < MIN_READ_INTERVAL_MS) {
            LOG.debug("Clipboard read rate-limited");
            context.publishEvent(com.opencodebuddy.tab.TabEvent.of("clipboard.read").field("data", "").build());
            return;
        }
        lastReadTime = now;

        // Dispatch clipboard access to EDT to avoid blocking the CEF browser thread.
        // Use ModalityState.any() so copy works even when a modal dialog (e.g. PermissionDialog) is open.
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                Clipboard clipboard = getClipboard();
                if (clipboard != null && clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                    String text = (String) clipboard.getData(DataFlavor.stringFlavor);
                    context.publishEvent(com.opencodebuddy.tab.TabEvent.of("clipboard.read").field("data", text != null ? text : "").build());
                } else {
                    context.publishEvent(com.opencodebuddy.tab.TabEvent.of("clipboard.read").field("data", "").build());
                }
            } catch (Exception e) {
                LOG.warn("Failed to read clipboard", e);
                context.publishEvent(com.opencodebuddy.tab.TabEvent.of("clipboard.read").field("data", "").build());
            }
        }, ModalityState.any());
    }

    private void handleWriteClipboard(String content) {
        if (content != null && content.length() > MAX_CLIPBOARD_WRITE_SIZE) {
            LOG.warn("Clipboard write rejected: content too large (" + content.length() + " chars)");
            context.publishEvent(com.opencodebuddy.tab.TabEvent.of("clipboard.copied").field("data", "false").build());
            return;
        }
        // Dispatch clipboard access to EDT to avoid blocking the CEF browser thread.
        // Use ModalityState.any() so copy works even when a modal dialog (e.g. PermissionDialog) is open.
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                Clipboard clipboard = getClipboard();
                if (clipboard != null) {
                    clipboard.setContents(new StringSelection(content != null ? content : ""), null);
                    context.publishEvent(com.opencodebuddy.tab.TabEvent.of("clipboard.copied").field("data", "true").build());
                } else {
                    context.publishEvent(com.opencodebuddy.tab.TabEvent.of("clipboard.copied").field("data", "false").build());
                }
            } catch (Exception e) {
                LOG.warn("Failed to write clipboard", e);
                context.publishEvent(com.opencodebuddy.tab.TabEvent.of("clipboard.copied").field("data", "false").build());
            }
        }, ModalityState.any());
    }

    /** Envelope payloads carry the text as JSON; legacy callers send the bare text. */
    static String extractText(String content) {
        if (content == null) {
            return null;
        }
        String trimmed = content.trim();
        if (!trimmed.startsWith("{")) {
            return content;
        }
        try {
            JsonObject payload = JsonParser.parseString(trimmed).getAsJsonObject();
            if (payload.has("text") && !payload.get("text").isJsonNull()) {
                return payload.get("text").getAsString();
            }
        } catch (Exception ignored) {
            // Fall through to the legacy raw value.
        }
        return content;
    }
}
