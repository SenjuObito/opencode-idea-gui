package com.opencodebuddy.handler;

import com.opencodebuddy.session.OpencodeSession;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * A tab that has not bound a session yet cannot send {@code set_model}: its
 * model travels with the first message and is applied when the host creates the
 * session. These tests pin that write path (and its no-op cases).
 */
public class SessionHandlerRequestedModelTest {

    @Test
    public void appliesTrimmedModelToTargetSession() {
        OpencodeSession session = new OpencodeSession(null, null);

        SessionHandler.applyRequestedModel(session, "  opencode/new-model  ");

        assertEquals("opencode/new-model", session.getModel());
    }

    @Test
    public void ignoresBlankModel() {
        OpencodeSession session = new OpencodeSession(null, null);
        String original = session.getModel();

        SessionHandler.applyRequestedModel(session, "   ");

        assertEquals(original, session.getModel());
    }

    @Test
    public void ignoresNullModelAndNullSession() {
        OpencodeSession session = new OpencodeSession(null, null);
        String original = session.getModel();

        SessionHandler.applyRequestedModel(session, null);
        SessionHandler.applyRequestedModel(null, "opencode/x");

        assertEquals(original, session.getModel());
    }
}
