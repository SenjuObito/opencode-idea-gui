package com.opencodebuddy.handler;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.opencodebuddy.handler.core.BaseMessageHandler;
import com.opencodebuddy.handler.core.HandlerContext;
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
        JsonObject payload;
        try {
            payload = JsonParser.parseString(content).getAsJsonObject();
        } catch (RuntimeException e) {
            fail(null, null, "BAD_ENVELOPE", "the payload is not a JSON object");
            return true;
        }

        String tabId = stringField(payload, "tabId");
        String requestId = stringField(payload, "requestId");
        String attachmentId = stringField(payload, "attachmentId");
        String fileName = stringField(payload, "fileName");
        String mediaType = stringField(payload, "mediaType");

        if (attachmentId == null) {
            fail(tabId, requestId, "MISSING_ATTACHMENT_ID",
                    "an upload must name the attachment it prepares");
            return true;
        }
        long size = longField(payload, "size");
        String path = context.prepareAttachment(
                tabId, attachmentId, fileName, mediaType, size);
        if (path == null) {
            fail(tabId, requestId, "UPLOAD_REFUSED",
                    "the attachment cannot be staged (too large or no disk space)");
            return true;
        }
        // Success needs no event: the caller already queued the chunks and the
        // send, and the bridge keeps them in order.
        LOG.debug("[AttachmentHandler] prepared " + attachmentId + " for tab "
                + tabId + " at " + path);
        return true;
    }

    private boolean handleChunk(String content) {
        JsonObject payload;
        try {
            payload = JsonParser.parseString(content).getAsJsonObject();
        } catch (RuntimeException e) {
            fail(null, null, "BAD_ENVELOPE", "the payload is not a JSON object");
            return true;
        }

        String tabId = stringField(payload, "tabId");
        String requestId = stringField(payload, "requestId");
        String attachmentId = stringField(payload, "attachmentId");
        String data = stringField(payload, "data");
        long offset = longField(payload, "offset");
        long written = context.appendAttachment(
                tabId, attachmentId, offset, data);
        if (written < 0) {
            // A refused chunk leaves the upload unusable: a gap would corrupt the
            // file, so the caller must start again.
            context.abortAttachment(tabId, attachmentId);
            fail(tabId, requestId, "CHUNK_REFUSED",
                    "the chunk does not continue the upload");
            return true;
        }
        return true;
    }

    private boolean handleComplete(String content) {
        JsonObject payload;
        try {
            payload = JsonParser.parseString(content).getAsJsonObject();
        } catch (RuntimeException e) {
            fail(null, null, "BAD_ENVELOPE", "the payload is not a JSON object");
            return true;
        }

        String tabId = stringField(payload, "tabId");
        String requestId = stringField(payload, "requestId");
        String attachmentId = stringField(payload, "attachmentId");
        boolean complete = context.completeAttachment(tabId, attachmentId);
        if (!complete) {
            context.abortAttachment(tabId, attachmentId);
            fail(tabId, requestId, "UPLOAD_INCOMPLETE",
                    "the upload did not reach the announced size");
            return true;
        }
        return true;
    }

    private boolean handleAbort(String content) {
        JsonObject payload;
        try {
            payload = JsonParser.parseString(content).getAsJsonObject();
        } catch (RuntimeException e) {
            LOG.debug("[AttachmentHandler] aborted upload malformed");
            return true;
        }

        String tabId = stringField(payload, "tabId");
        context.abortAttachment(tabId, stringField(payload, "attachmentId"));
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

    private static String stringField(JsonObject payload, String field) {
        if (payload == null || !payload.has(field) || payload.get(field).isJsonNull()) {
            return null;
        }
        String value = payload.get(field).getAsString();
        return value.isBlank() ? null : value;
    }

    /** A numeric field, or -1 when the caller did not send one. */
    private static long longField(JsonObject payload, String field) {
        if (payload == null || !payload.has(field) || payload.get(field).isJsonNull()) {
            return -1L;
        }
        try {
            return payload.get(field).getAsLong();
        } catch (RuntimeException e) {
            return -1L;
        }
    }
}
