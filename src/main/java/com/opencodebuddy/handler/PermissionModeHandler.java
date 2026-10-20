package com.opencodebuddy.handler;

import com.opencodebuddy.handler.core.HandlerContext;
import com.opencodebuddy.session.OpencodeSession;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

/**
 * Handles permission mode (bypassPermissions, etc.) get/set operations.
 */
public class PermissionModeHandler {

    private static final Logger LOG = Logger.getInstance(PermissionModeHandler.class);

    static final String PERMISSION_MODE_PROPERTY_KEY = "opencodebuddy.permission.mode";

    private final HandlerContext context;
    private final Gson gson = new Gson();

    public PermissionModeHandler(HandlerContext context) {
        this.context = context;
    }

    /**
     * Get current permission mode.
     */
    public void handleGetMode() {
        try {
            String currentMode = "default";  // Default value (prompt on each tool call)

            // Prefer getting from session first
            if (this.context.getSession() != null) {
                String sessionMode = this.context.getSession().getPermissionMode();
                if (sessionMode != null && !sessionMode.trim().isEmpty()) {
                    currentMode = sessionMode;
                }
            } else {
                // If session does not exist, load from persistent storage
                PropertiesComponent props = PropertiesComponent.getInstance();
                String savedMode = props.getValue(PERMISSION_MODE_PROPERTY_KEY);
                if (savedMode != null && !savedMode.trim().isEmpty()) {
                    currentMode = savedMode.trim();
                }
            }

            final String modeToSend = currentMode;

            ApplicationManager.getApplication().invokeLater(() -> {
                this.context.callJavaScript("window.onModeReceived", this.context.escapeJs(modeToSend));
            });
        } catch (Exception e) {
            LOG.error("[PermissionModeHandler] Failed to get mode: " + e.getMessage(), e);
        }
    }

    /**
     * Handle set mode request.
     * <p>
     * Accepts either a bare mode string (legacy, window-scoped fallback) or a
     * JSON object {@code {"sessionId":"…","mode":"…"}} so the change can be
     * scoped to the calling tab's session instead of always hitting the window's
     * current session. An explicitly scoped payload that cannot be resolved is a
     * no-op: falling back would rewrite the previously active tab.
     */
    public void handleSetMode(String content) {
        try {
            String mode = content;
            boolean scopedPayload = false;
            String targetSessionId = null;
            if (content != null && !content.isEmpty()) {
                try {
                    JsonObject json = this.gson.fromJson(content, JsonObject.class);
                    scopedPayload = true;
                    if (json.has("mode")) {
                        mode = json.get("mode").getAsString();
                    }
                    if (json.has("sessionId") && !json.get("sessionId").isJsonNull()) {
                        targetSessionId = json.get("sessionId").getAsString();
                    }
                } catch (Exception e) {
                    // content itself is the mode
                }
            }

            OpencodeSession targetSession = null;
            if (targetSessionId != null && !targetSessionId.trim().isEmpty()) {
                targetSession = context.getSession(targetSessionId);
                if (targetSession == null) {
                    LOG.warn("[PermissionModeHandler] Unknown sessionId " + targetSessionId
                            + " — ignoring instead of falling back to the window's current session");
                    return;
                }
            } else if (scopedPayload) {
                LOG.warn("[PermissionModeHandler] Payload has no sessionId — ignoring "
                        + "(an unbound tab sends its mode with the message)");
                return;
            } else {
                // Legacy bare-string callers keep the window-scoped behaviour.
                targetSession = context.getSession();
            }

            if (targetSession != null) {
                targetSession.setPermissionMode(mode);

                // Save permission mode to persistent storage
                PropertiesComponent props = PropertiesComponent.getInstance();
                props.setValue(PERMISSION_MODE_PROPERTY_KEY, mode);
                LOG.info("Saved permission mode to settings: " + mode);
                com.opencodebuddy.notifications.OpencodeNotifier.setMode(this.context.getProject(), mode);
            } else {
                LOG.warn("[PermissionModeHandler] WARNING: Session is null! Cannot set permission mode");
            }
        } catch (Exception e) {
            LOG.error("[PermissionModeHandler] Failed to set mode: " + e.getMessage(), e);
        }
    }

    /**
     * Notify the live AI Bridge runtime of a mode change so it applies
     * immediately to the in-progress conversation.
     */
    private void pushPermissionModeLive(String mode) {
        try {
            String provider = this.context.getCurrentProvider();
            if (provider == null || provider.isEmpty()) {
                provider = com.opencodebuddy.handler.core.HandlerContext.DEFAULT_PROVIDER;
            }

            // opencode applies the permission mode on the next turn; there is no
            // live per-turn mode switch to push over the daemon today.
            if (!com.opencodebuddy.handler.core.HandlerContext.DEFAULT_PROVIDER.equals(provider)) {
                return;
            }
        } catch (Exception e) {
            LOG.warn("[PermissionModeHandler] Live mode push skipped: " + e.getMessage());
        }
    }
}
