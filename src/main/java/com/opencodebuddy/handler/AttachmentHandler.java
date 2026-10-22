package com.opencodebuddy.handler;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.opencodebuddy.handler.core.BaseMessageHandler;
import com.opencodebuddy.handler.core.HandlerContext;
import com.opencodebuddy.handler.core.HostCommand;
import com.opencodebuddy.tab.TabEvent;

/**
 * Chunked attachment upload.
 *
 * <p>The webview reads a file the user picked, but a multi-megabyte base64 blob
 * must not travel as one bridge frame: a single frame that size stalls the
 * renderer and blows the frame budget. The upload is therefore announced, sent
 * in chunks the frame budget can carry, and finished — the host writes each
 * chunk into the tab's own directory and the send path only names the
 * attachment.</p>
 *
 * <p>Every command names its tab: an attachment belongs to the tab that picked
 * it, and a late chunk must land in the same tab even when the user has
 * switched away in the meantime.</p>
 */
public class AttachmentHandler extends BaseMessageHandler {

    private static final Logger LOG = Logger.getInstance(AttachmentHandler.class);

    private static final String[] SUPPORTED_TYPES = {
            "attachment.prepare",
            "attachment.chunk",
            "attachment.complete",
            "attachment.abort"
    };

    public AttachmentHandler(HandlerContext context) {
        super(context);
    }

    @Override
    public String[] getSupportedTypes() {
        return SUPPORTED_TYPES.clone();
    }

    @Override
    public boolean handle(String type, String content) {
        switch (type) {
            case "attachment.prepare":
                return handlePrepare(content);
            case "attachment.chunk":
                return handleChunk(content);
            case "attachment.complete":
                return handleComplete(content);
            case "attachment.abort":
                return handleAbort(content);
            default:
                return false;
        }
    }

    private boolean handlePrepare(String content) {
        HostCommand command;
        try {
            command = HostCommand.parse(content, "attachment.prepare");
        } catch (HostCommand.Invalid invalid) {
            fail(null, null, invalid.getCode(), invalid.getMessage());
            return true;
        }
        String attachmentId = string(command, "attachmentId");
        String fileName = string(command, "fileName");
        String mediaType = string(command, "mediaType");
        if (attachmentId == null) {
            fail(command.getTabId(), command.getRequestId(), "MISSING_ATTACHMENT_ID",
                    "an upload must name the attachment it prepares");
            return true;
        }
        long size = longField(command, "size");
        String path = context.prepareAttachment(
                command.getTabId(), attachmentId, fileName, mediaType, size);
        if (path == null) {
            fail(command.getTabId(), command.getRequestId(), "UPLOAD_REFUSED",
                    "the attachment cannot be staged (too large or no disk space)");
            return true;
        }
        // Success needs no event: the caller already queued the chunks and the
        // send, and the bridge keeps them in order.
        LOG.debug("[AttachmentHandler] prepared " + attachmentId + " for tab "
                + command.getTabId() + " at " + path);
        return true;
    }

    private boolean handleChunk(String content) {
        HostCommand command;
        try {
            command = HostCommand.parse(content, "attachment.chunk");
        } catch (HostCommand.Invalid invalid) {
            fail(null, null, invalid.getCode(), invalid.getMessage());
            return true;
        }
        String attachmentId = string(command, "attachmentId");
        String data = string(command, "data");
        long offset = longField(command, "offset");
        long written = context.appendAttachment(
                command.getTabId(), attachmentId, offset, data);
        if (written < 0) {
            // A refused chunk leaves the upload unusable: a gap would corrupt the
            // file, so the caller must start again.
            context.abortAttachment(command.getTabId(), attachmentId);
            fail(command.getTabId(), command.getRequestId(), "CHUNK_REFUSED",
                    "the chunk does not continue the upload");
            return true;
        }
        return true;
    }

    private boolean handleComplete(String content) {
        HostCommand command;
        try {
            command = HostCommand.parse(content, "attachment.complete");
        } catch (HostCommand.Invalid invalid) {
            fail(null, null, invalid.getCode(), invalid.getMessage());
            return true;
        }
        String attachmentId = string(command, "attachmentId");
        boolean complete = context.completeAttachment(command.getTabId(), attachmentId);
        if (!complete) {
            context.abortAttachment(command.getTabId(), attachmentId);
            fail(command.getTabId(), command.getRequestId(), "UPLOAD_INCOMPLETE",
                    "the upload did not reach the announced size");
            return true;
        }
        return true;
    }

    private boolean handleAbort(String content) {
        HostCommand command;
        try {
            command = HostCommand.parse(content, "attachment.abort");
        } catch (HostCommand.Invalid invalid) {
            LOG.debug("[AttachmentHandler] aborted upload rejected: " + invalid.getMessage());
            return true;
        }
        context.abortAttachment(command.getTabId(), string(command, "attachmentId"));
        return true;
    }

    private void fail(String tabId, String requestId, String code, String message) {
        LOG.warn("[AttachmentHandler] " + code + ": " + message);
        context.publishEvent(TabEvent.of("attachment.failed")
                .tab(tabId)
                .field("tabId", tabId)
                .field("requestId", requestId)
                .field("code", code)
                .field("message", message)
                .build());
    }

    private static String string(HostCommand command, String field) {
        JsonObject payload;
        try {
            payload = JsonParser.parseString(command.getPayloadJson()).getAsJsonObject();
        } catch (RuntimeException e) {
            return null;
        }
        if (!payload.has(field) || payload.get(field).isJsonNull()) {
            return null;
        }
        String value = payload.get(field).getAsString();
        return value.isBlank() ? null : value;
    }

    /** A numeric field, or -1 when the caller did not send one. */
    private static long longField(HostCommand command, String field) {
        try {
            JsonObject payload = JsonParser.parseString(command.getPayloadJson()).getAsJsonObject();
            if (!payload.has(field) || payload.get(field).isJsonNull()) {
                return -1L;
            }
            return payload.get(field).getAsLong();
        } catch (RuntimeException e) {
            return -1L;
        }
    }
}
