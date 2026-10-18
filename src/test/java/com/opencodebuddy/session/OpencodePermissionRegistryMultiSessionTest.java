package com.opencodebuddy.session;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 多会话下的 opencode 权限注册表隔离测试。
 *
 * <p>权限 id 是服务端全局唯一的，所以回复必须带上「请求到达时」的 sessionId +
 * 工作目录，而不是用户点击时活跃会话的那些。这里守住三件事：</p>
 *
 * <ol>
 *   <li>{@code consume} 取回的永远是注册它的那个会话；</li>
 *   <li>{@code clearSession} 只清关掉的那个会话，另一个会话的待决请求不受影响
 *       （旧的「溢出就整表 clear」会把别人还没回的请求一起判成拒绝）；</li>
 *   <li>溢出时逐条淘汰最旧，而不是整表清空。</li>
 * </ol>
 *
 * <p>注册表是 static 的，所以每个用例前后都清空，避免相互污染。</p>
 */
public class OpencodePermissionRegistryMultiSessionTest {

    private int maxEntries;

    @Before
    public void setUp() throws Exception {
        OpencodePermissionRegistry.clear();
        Field f = OpencodePermissionRegistry.class.getDeclaredField("MAX_ENTRIES");
        f.setAccessible(true);
        maxEntries = f.getInt(null);
    }

    @After
    public void tearDown() {
        OpencodePermissionRegistry.clear();
    }

    @Test
    public void consumeReturnsTheSessionThatRegisteredTheRequest() {
        OpencodePermissionRegistry.register("per_a1", "ses_a", "/dir/a");
        OpencodePermissionRegistry.register("per_b1", "ses_b", "/dir/b");

        OpencodePermissionRegistry.PendingPermission a = OpencodePermissionRegistry.consume("per_a1");
        assertNotNull(a);
        assertEquals("ses_a", a.sessionId);
        assertEquals("/dir/a", a.directory);

        OpencodePermissionRegistry.PendingPermission b = OpencodePermissionRegistry.consume("per_b1");
        assertNotNull(b);
        assertEquals("ses_b", b.sessionId);
        assertEquals("/dir/b", b.directory);

        assertNull(OpencodePermissionRegistry.consume("per_a1"));
        assertEquals(0, OpencodePermissionRegistry.size());
    }

    @Test
    public void clearSessionDropsOnlyThatSessionsRequests() {
        OpencodePermissionRegistry.register("per_a1", "ses_a", "/dir/a");
        OpencodePermissionRegistry.register("per_a2", "ses_a", "/dir/a");
        OpencodePermissionRegistry.register("per_b1", "ses_b", "/dir/b");

        int removed = OpencodePermissionRegistry.clearSession("ses_a");

        assertEquals(2, removed);
        assertEquals(1, OpencodePermissionRegistry.size());
        assertNull(OpencodePermissionRegistry.consume("per_a1"));
        assertNotNull("another session's pending request must survive",
                OpencodePermissionRegistry.consume("per_b1"));
    }

    @Test
    public void clearSessionIgnoresNullOrBlankIds() {
        OpencodePermissionRegistry.register("per_a1", "ses_a", "/dir/a");

        assertEquals(0, OpencodePermissionRegistry.clearSession(null));
        assertEquals(0, OpencodePermissionRegistry.clearSession("  "));
        assertEquals(1, OpencodePermissionRegistry.size());
    }

    @Test
    public void overflowEvictsTheOldestEntriesIndividually() {
        // 交替注册两个会话的请求，超过上限后必须只淘汰最旧的若干条，
        // 而不是整表 clear（那会把另一个会话还没回的请求一并判成拒绝）。
        int total = maxEntries + 5;
        for (int i = 0; i < total; i++) {
            String session = (i % 2 == 0) ? "ses_a" : "ses_b";
            OpencodePermissionRegistry.register("per_" + i, session, "/dir/" + session);
        }

        assertEquals(maxEntries, OpencodePermissionRegistry.size());
        // 最早 5 条被逐条淘汰
        for (int i = 0; i < 5; i++) {
            assertNull("oldest entry per_" + i + " should have been evicted",
                    OpencodePermissionRegistry.consume("per_" + i));
        }
        // 两个会话的较新请求都还在
        for (int i = 5; i < total; i++) {
            String session = (i % 2 == 0) ? "ses_a" : "ses_b";
            OpencodePermissionRegistry.PendingPermission pending =
                    OpencodePermissionRegistry.consume("per_" + i);
            assertNotNull("entry per_" + i + " was dropped", pending);
            assertEquals(session, pending.sessionId);
        }
    }

    @Test
    public void nonOpencodeIdsAreNeverRegistered() {
        OpencodePermissionRegistry.register("ch_local", "ses_a", "/dir/a");
        assertEquals(0, OpencodePermissionRegistry.size());
        assertFalse(OpencodePermissionRegistry.isOpencodePermissionId("ch_local"));
        assertTrue(OpencodePermissionRegistry.isOpencodePermissionId("per_x"));
    }
}
