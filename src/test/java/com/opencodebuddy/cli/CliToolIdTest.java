package com.opencodebuddy.cli;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class CliToolIdTest {

    @Test
    public void fromId_acceptsKnownTools() {
        assertEquals(CliToolId.OPENCODE, CliToolId.fromId(" opencode "));
        assertEquals(CliToolId.OPENCODE, CliToolId.fromId("OPENCODE"));
    }

    @Test
    public void fromId_rejectsUnknown() {
        assertNull(CliToolId.fromId(null));
        assertNull(CliToolId.fromId(""));
        assertNull(CliToolId.fromId("claude"));
        assertNull(CliToolId.fromId("grok"));
    }

    @Test
    public void binaryNames_matchExpected() {
        assertEquals("opencode", CliToolId.OPENCODE.getBinaryName());
        assertEquals("OpenCode", CliToolId.OPENCODE.getDisplayName());
        for (CliToolId tool : CliToolId.values()) {
            assertNotNull(tool.getDisplayName());
        }
    }
}
