package com.opencodebuddy.handler.history;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SubagentHistoryServiceTest {

    @Test
    public void findsTheChildSessionInTheToolMetadata() {
        JsonArray entries = JsonParser.parseString("""
                [{
                  "info": {"id": "msg_1", "role": "assistant"},
                  "parts": [{
                    "type": "tool",
                    "id": "call_task",
                    "tool": "subagent",
                    "state": {
                      "status": "completed",
                      "input": {"description": "Explore repo"},
                      "output": "done",
                      "metadata": {"sessionID": "ses_child"}
                    }
                  }]
                }]
                """).getAsJsonArray();

        SubagentHistoryService.FoundTool tool =
                SubagentHistoryService.findTool(entries, "call_task");

        assertNotNull(tool);
        assertEquals("ses_child", tool.childSessionId);
        assertEquals("completed", tool.status);
        assertEquals("done", tool.output);
    }

    @Test
    public void fallsBackToTheSubagentWrapperWhenMetadataIsMissing() {
        JsonArray entries = JsonParser.parseString("""
                [{
                  "info": {"id": "msg_1", "role": "assistant"},
                  "parts": [{
                    "type": "tool",
                    "id": "call_task",
                    "tool": "subagent",
                    "state": {
                      "status": "completed",
                      "output": "<subagent sessionID=\\"ses_from_text\\" state=\\"completed\\">ok</subagent>"
                    }
                  }]
                }]
                """).getAsJsonArray();

        SubagentHistoryService.FoundTool tool =
                SubagentHistoryService.findTool(entries, "call_task");

        assertNotNull(tool);
        assertEquals("ses_from_text", tool.childSessionId);
        assertEquals("completed", tool.status);
    }

    @Test
    public void ignoresAnUnrelatedToolPart() {
        JsonArray entries = JsonParser.parseString("""
                [{"info": {"role": "assistant"},
                  "parts": [{"type": "tool", "id": "call_other", "tool": "read", "state": {}}]}]
                """).getAsJsonArray();

        assertNull(SubagentHistoryService.findTool(entries, "call_task"));
    }

    @Test
    public void choosesTheChildWhoseTitleMatchesTheDescription() {
        JsonObject sessions = JsonParser.parseString("""
                {"sessions": [
                  {"id": "ses_old", "title": "Other task", "time": {"updated": 10}},
                  {"id": "ses_match", "title": "Explore repo", "time": {"updated": 20}}
                ]}
                """).getAsJsonObject();

        assertEquals("ses_match",
                SubagentHistoryService.childFromList(sessions, "Explore repo"));
    }

    @Test
    public void readsTheTerminalFinishFromTheChildTranscriptWhenTheToolPartLags() {
        JsonArray entries = JsonParser.parseString("""
                [{"info": {"role": "assistant", "finish": "stop"}, "parts": [{"type": "text", "text": "done"}]}]
                """).getAsJsonArray();

        assertEquals("completed",
                SubagentHistoryService.statusFromEntries(entries));
    }

    @Test
    public void aRunningChildIsNotCompletedByTheParentLaunchAck() {
        JsonArray entries = JsonParser.parseString("""
                [{"info": {"role": "assistant", "finish": "tool-calls"}, "parts": []}]
                """).getAsJsonArray();

        assertEquals("running", SubagentHistoryService.statusFromEntries(entries));
    }

    @Test
    public void aChildWithNoAssistantMessageIsStillRunning() {
        JsonArray entries = JsonParser.parseString("""
                [{"info": {"role": "user"}, "parts": []}]
                """).getAsJsonArray();

        assertEquals("running", SubagentHistoryService.statusFromEntries(entries));
        assertEquals("running", SubagentHistoryService.statusFromEntries(new JsonArray()));
    }

    @Test
    public void aChildErrorIsAnError() {
        JsonArray entries = JsonParser.parseString("""
                [{"info": {"role": "assistant", "finish": "stop",
                           "error": {"type": "tool.execution", "message": "failed"}},
                  "parts": []}]
                """).getAsJsonArray();

        assertEquals("error", SubagentHistoryService.statusFromEntries(entries));
    }

    @Test
    public void convertsReasoningAndToolPartsForTheProcessCard() {
        JsonArray entries = JsonParser.parseString("""
                [{
                  "info": {"id": "msg_1", "role": "assistant"},
                  "parts": [
                    {"type": "reasoning", "text": "thinking"},
                    {"type": "text", "text": "report"},
                    {"type": "tool", "id": "call_read", "tool": "read",
                     "state": {"input": {"path": "/repo/a.txt"}}}
                  ]
                }]
                """).getAsJsonArray();

        JsonArray messages = SubagentHistoryService.convertEntries(entries);

        assertEquals(1, messages.size());
        JsonArray blocks = messages.get(0).getAsJsonObject()
                .getAsJsonObject("message").getAsJsonArray("content");
        assertEquals(3, blocks.size());
        assertEquals("thinking", blocks.get(0).getAsJsonObject().get("thinking").getAsString());
        assertEquals("report", blocks.get(1).getAsJsonObject().get("text").getAsString());
        JsonObject tool = blocks.get(2).getAsJsonObject();
        assertEquals("tool_use", tool.get("type").getAsString());
        assertEquals("read", tool.get("name").getAsString());
        assertEquals("/repo/a.txt",
                tool.getAsJsonObject("input").get("path").getAsString());
        assertTrue(messages.get(0).getAsJsonObject().get("message").isJsonObject());
    }
}
