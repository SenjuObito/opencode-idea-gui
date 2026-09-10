package com.opencodebuddy.session;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class OpencodePermissionRegistryTest {

    @SuppressWarnings("unchecked")
    private static Map<String, OpencodePermissionRegistry.PendingPermission> pendingMap() throws Exception {
        Field field = OpencodePermissionRegistry.class.getDeclaredField("PENDING");
        field.setAccessible(true);
        return (Map<String, OpencodePermissionRegistry.PendingPermission>) field.get(null);
    }

    @Test
    public void recognizesOpencodePermissionIdsOnly() {
        assertTrue(OpencodePermissionRegistry.isOpencodePermissionId("per_08a323e83001xWmH"));
        assertTrue(OpencodePermissionRegistry.isOpencodePermissionId("per_"));
        assertFalse(OpencodePermissionRegistry.isOpencodePermissionId("call_abc"));
        assertFalse(OpencodePermissionRegistry.isOpencodePermissionId(""));
        assertFalse(OpencodePermissionRegistry.isOpencodePermissionId(null));
    }

    @Test
    public void registerIgnoresNonOpencodeIds() throws Exception {
        OpencodePermissionRegistry.clear();
        OpencodePermissionRegistry.register("call_abc", "ses_1", "/tmp");
        OpencodePermissionRegistry.register(null, "ses_1", "/tmp");
        assertEquals(0, pendingMap().size());
    }

    @Test
    public void registerAndConsumeRoundTripsContext() {
        OpencodePermissionRegistry.clear();
        OpencodePermissionRegistry.register(
                "per_1", "ses_f75e", "/Users/obito/IdeaProjects/OpenCode GUI");

        OpencodePermissionRegistry.PendingPermission pending =
                OpencodePermissionRegistry.consume("per_1");
        assertEquals("ses_f75e", pending.sessionId);
        assertEquals("/Users/obito/IdeaProjects/OpenCode GUI", pending.directory);

        // Consume is one-shot: a second decision (double click) must not
        // re-fire a reply at an already-answered request.
        assertNull(OpencodePermissionRegistry.consume("per_1"));
    }

    @Test
    public void removeDropsEntryWithoutConsuming() {
        OpencodePermissionRegistry.clear();
        OpencodePermissionRegistry.register("per_2", "ses_x", null);
        OpencodePermissionRegistry.remove("per_2");
        assertNull(OpencodePermissionRegistry.consume("per_2"));
    }

    @Test
    public void consumeReturnsNullForUnknownOrNull() {
        OpencodePermissionRegistry.clear();
        assertNull(OpencodePermissionRegistry.consume("per_unknown"));
        assertNull(OpencodePermissionRegistry.consume(null));
    }

    @Test
    public void clearDropsAllEntries() {
        OpencodePermissionRegistry.clear();
        OpencodePermissionRegistry.register("per_a", "ses_1", "/a");
        OpencodePermissionRegistry.register("per_b", "ses_2", "/b");
        assertEquals(2, OpencodePermissionRegistry.size());
        OpencodePermissionRegistry.clear();
        assertEquals(0, OpencodePermissionRegistry.size());
    }
}
