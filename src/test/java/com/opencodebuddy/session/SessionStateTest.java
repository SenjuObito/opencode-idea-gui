package com.opencodebuddy.session;

import org.junit.Assert;
import org.junit.Test;

/**
 * Regression tests for retired Claude model id migration on session state writes
 * (persisted tab / history restore self-heal) - see #1678.
 */
public class SessionStateTest {

    @Test
    public void setModelMigratesRetiredSonnet47ToSonnet5() {
        SessionState state = new SessionState();
        // Saved by versions <= 0.5.2 where sonnet-4-7 was the default model.
        state.setModel("claude-sonnet-4-7");
        Assert.assertEquals("claude-sonnet-5", state.getModel());
    }

    @Test
    public void setModelMigratesRetiredSonnet46ToSonnet5() {
        SessionState state = new SessionState();
        state.setModel("claude-sonnet-4-6");
        Assert.assertEquals("claude-sonnet-5", state.getModel());
    }

    @Test
    public void setModelMigratesRetiredOpus46ToOpus48() {
        SessionState state = new SessionState();
        state.setModel("claude-opus-4-6");
        Assert.assertEquals("claude-opus-4-8", state.getModel());
    }

    @Test
    public void setModelPreserves1MSuffixWhenMigrating() {
        SessionState state = new SessionState();
        state.setModel("claude-sonnet-4-7[1m]");
        Assert.assertEquals("claude-sonnet-5[1m]", state.getModel());
    }

    @Test
    public void setModelLeavesLiveModelsUntouched() {
        SessionState state = new SessionState();
        state.setModel("claude-sonnet-5");
        Assert.assertEquals("claude-sonnet-5", state.getModel());
        state.setModel("claude-opus-4-8[1m]");
        Assert.assertEquals("claude-opus-4-8[1m]", state.getModel());
    }

    @Test
    public void setModelLeavesNonClaudeAndUnknownIdsUntouched() {
        SessionState state = new SessionState();
        // Non-Claude provider models must pass through unchanged.
        state.setModel("gpt-5.6-sol");
        Assert.assertEquals("gpt-5.6-sol", state.getModel());
        state.setModel("qwen3.5-plus");
        Assert.assertEquals("qwen3.5-plus", state.getModel());
    }

    @Test
    public void setModelHandlesNullAndBlank() {
        SessionState state = new SessionState();
        state.setModel(null);
        Assert.assertNull(state.getModel());
        // Blank input is trimmed like every other normalizeRetiredModelId path.
        state.setModel("  ");
        Assert.assertEquals("", state.getModel());
    }

    @Test
    public void defaultModelIsOpenCodeDefault() {
        SessionState state = new SessionState();
        Assert.assertEquals("opencode-default", state.getModel());
    }

    // ── Revert cleanup sync (message.removed mirroring) ──

    @Test
    public void removeMessagesByIdsDropsMessagesCarryingTheIdInRaw() {
        SessionState state = new SessionState();
        state.addMessage(messageWithRaw("u1", null));
        state.addMessage(messageWithRaw("a1", null));
        state.addMessage(messageWithRaw("u2", null));

        Assert.assertTrue(state.removeMessagesByIds(java.util.Collections.singletonList("a1")));

        java.util.List<OpencodeSession.Message> left = state.getMessages();
        Assert.assertEquals(2, left.size());
        Assert.assertEquals("u1", left.get(0).raw.get("id").getAsString());
        Assert.assertEquals("u2", left.get(1).raw.get("id").getAsString());
    }

    @Test
    public void removeMessagesByIdsMatchesRewindPatchedUuid() {
        SessionState state = new SessionState();
        // Rewind-patched user messages carry the provider id as raw.uuid, not raw.id.
        state.addMessage(messageWithRaw(null, "msg_uuid"));

        Assert.assertTrue(state.removeMessagesByIds(java.util.Collections.singletonList("msg_uuid")));
        Assert.assertTrue(state.getMessages().isEmpty());
    }

    @Test
    public void removeMessagesByIdsReturnsFalseWhenNothingMatches() {
        SessionState state = new SessionState();
        state.addMessage(messageWithRaw("u1", null));

        Assert.assertFalse(state.removeMessagesByIds(java.util.Collections.singletonList("nope")));
        Assert.assertEquals(1, state.getMessages().size());
    }

    @Test
    public void removeMessagesByIdsIgnoresMessagesWithoutRaw() {
        SessionState state = new SessionState();
        state.addMessage(new OpencodeSession.Message(OpencodeSession.Message.Type.USER, "no raw"));

        // Must not throw on a null raw payload.
        Assert.assertFalse(state.removeMessagesByIds(java.util.Collections.singletonList("u1")));
        Assert.assertEquals(1, state.getMessages().size());
    }

    @Test
    public void removeMessagesByIdsIgnoresEmptyInput() {
        SessionState state = new SessionState();
        state.addMessage(messageWithRaw("u1", null));

        Assert.assertFalse(state.removeMessagesByIds(null));
        Assert.assertFalse(state.removeMessagesByIds(java.util.Collections.emptyList()));
        Assert.assertEquals(1, state.getMessages().size());
    }

    @Test
    public void trimMessagesFromRevertBoundaryDropsBoundaryAndEverythingAfter() {
        SessionState state = new SessionState();
        state.addMessage(messageWithRaw("u1", null));
        state.addMessage(messageWithRaw("a1", null));
        state.addMessage(messageWithRaw("u2", null));
        state.addMessage(messageWithRaw("a2", null));
        state.setRevertState(new SessionState.RevertState("u2"));

        // Inclusive of the boundary: the server removes from it onward (no partID).
        Assert.assertTrue(state.trimMessagesFromRevertBoundary());

        java.util.List<OpencodeSession.Message> left = state.getMessages();
        Assert.assertEquals(2, left.size());
        Assert.assertEquals("u1", left.get(0).raw.get("id").getAsString());
        Assert.assertEquals("a1", left.get(1).raw.get("id").getAsString());
    }

    @Test
    public void trimMessagesFromRevertBoundaryIsNoOpWithoutRevert() {
        SessionState state = new SessionState();
        state.addMessage(messageWithRaw("u1", null));

        Assert.assertFalse(state.trimMessagesFromRevertBoundary());
        Assert.assertEquals(1, state.getMessages().size());
    }

    @Test
    public void trimMessagesFromRevertBoundaryIsNoOpWhenBoundaryIdIsBlank() {
        SessionState state = new SessionState();
        state.addMessage(messageWithRaw("u1", null));
        // The daemon used to emit REVERT_STATE without an id; that path must stay harmless.
        state.setRevertState(new SessionState.RevertState(""));

        Assert.assertFalse(state.trimMessagesFromRevertBoundary());
        Assert.assertEquals(1, state.getMessages().size());
    }

    @Test
    public void trimMessagesFromRevertBoundaryIsNoOpWhenBoundaryAbsentLocally() {
        SessionState state = new SessionState();
        state.addMessage(messageWithRaw("u1", null));
        // Boundary points at a message this host never loaded — leave the list alone,
        // the authoritative message.removed events still correct it.
        state.setRevertState(new SessionState.RevertState("u9"));

        Assert.assertFalse(state.trimMessagesFromRevertBoundary());
        Assert.assertEquals(1, state.getMessages().size());
    }

    /** Build a message whose raw payload carries the given provider id (raw.id / raw.uuid). */
    private static OpencodeSession.Message messageWithRaw(String id, String uuid) {
        com.google.gson.JsonObject raw = new com.google.gson.JsonObject();
        if (id != null) {
            raw.addProperty("id", id);
        }
        if (uuid != null) {
            raw.addProperty("uuid", uuid);
        }
        return new OpencodeSession.Message(OpencodeSession.Message.Type.USER, "text", raw);
    }
}
