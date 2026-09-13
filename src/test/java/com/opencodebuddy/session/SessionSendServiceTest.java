package com.opencodebuddy.session;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for slash command parsing in SessionSendService.
 */
public class SessionSendServiceTest {

    @Test
    public void testValidSlashCommands() {
        String[] reviewCmd = SessionSendService.parseSlashCommand("/review");
        assertNotNull(reviewCmd);
        assertEquals("review", reviewCmd[0]);
        assertEquals("", reviewCmd[1]);

        String[] reviewWithArgs = SessionSendService.parseSlashCommand("/review src/main.rs");
        assertNotNull(reviewWithArgs);
        assertEquals("review", reviewWithArgs[0]);
        assertEquals("src/main.rs", reviewWithArgs[1]);

        String[] initCmd = SessionSendService.parseSlashCommand("/init");
        assertNotNull(initCmd);
        assertEquals("init", initCmd[0]);
        assertEquals("", initCmd[1]);

        String[] compactCmd = SessionSendService.parseSlashCommand("/compact");
        assertNotNull(compactCmd);
        assertEquals("compact", compactCmd[0]);
        assertEquals("", compactCmd[1]);
    }

    @Test
    public void testUrlPathsAndFilePathsRejectedAsSlashCommands() {
        // User reported issue: URL path with Chinese conversation text
        String urlText = "/v2/risk-assessments/papers/acitons/submit又有动词，这不是但数码？重新修改List<QuestionVO>，发送了这条消息之后，直接就报错了。";
        assertNull(SessionSendService.parseSlashCommand(urlText));

        // Other URL and API paths
        assertNull(SessionSendService.parseSlashCommand("/v1/chat/completions"));
        assertNull(SessionSendService.parseSlashCommand("/api/v1/user/login"));
        assertNull(SessionSendService.parseSlashCommand("/openapi.json"));

        // System file paths
        assertNull(SessionSendService.parseSlashCommand("/Users/username/project/main.go"));
        assertNull(SessionSendService.parseSlashCommand("/etc/nginx/nginx.conf"));
        assertNull(SessionSendService.parseSlashCommand("/var/log/system.log"));
    }

    @Test
    public void testNonSlashAndShellInputs() {
        assertNull(SessionSendService.parseSlashCommand(null));
        assertNull(SessionSendService.parseSlashCommand(""));
        assertNull(SessionSendService.parseSlashCommand("   "));
        assertNull(SessionSendService.parseSlashCommand("hello /review"));
        assertNull(SessionSendService.parseSlashCommand("!git status"));
        assertNull(SessionSendService.parseSlashCommand("!ls -la"));
    }

    @Test
    public void testUnknownSlashCommandRejected() {
        // An unknown slash command (not in KnownCommands) should return null to send as regular prompt
        assertNull(SessionSendService.parseSlashCommand("/nonexistentcommand12345"));
    }
}
