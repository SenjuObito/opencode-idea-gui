package com.opencodebuddy.session;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Fixtures mirror the parts opencode actually persists (verified live against
 * opencode serve 1.18.26 via .zcode/skills/opencode-api-verify/scripts/test-file-part.mjs):
 * user text part first, then per file part two synthetic text parts ("Called
 * the Read tool..." + inlined content) followed by the original file part.
 */
public class OpenCodeMessageConverterTest {

    private static JsonObject part(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static JsonObject entry(String role, String partsJson) {
        JsonObject info = new JsonObject();
        info.addProperty("role", role);
        JsonObject time = new JsonObject();
        time.addProperty("created", 1725900000000L);
        info.add("time", time);
        info.addProperty("id", "msg_test");
        JsonObject entry = new JsonObject();
        entry.add("info", info);
        entry.add("parts", JsonParser.parseString(partsJson).getAsJsonArray());
        return entry;
    }

    @Test
    public void dropsSyntheticPartsAndRestoresChipsAndImages() {
        String parts = "["
                + "{\"type\":\"text\",\"text\":\"Check these files please.\\n@notes.txt\"},"
                + "{\"type\":\"text\",\"synthetic\":true,\"text\":\"Called the Read tool with the following input: {\\\"filePath\\\":\\\"memory.txt\\\"}\"},"
                + "{\"type\":\"text\",\"synthetic\":true,\"text\":\"inline content from data: URL\"},"
                + "{\"type\":\"file\",\"mime\":\"text/plain\",\"filename\":\"memory.txt\",\"url\":\"data:text/plain;base64,aW5saW5l\"},"
                + "{\"type\":\"text\",\"synthetic\":true,\"text\":\"Called the Read tool with the following input: {\\\"filePath\\\":\\\"/tmp/notes.txt\\\"}\"},"
                + "{\"type\":\"text\",\"synthetic\":true,\"text\":\"<path>/tmp/notes.txt</path> file content\"},"
                + "{\"type\":\"file\",\"mime\":\"text/plain\",\"filename\":\"notes.txt\",\"url\":\"file:///tmp/notes.txt\"},"
                + "{\"type\":\"file\",\"mime\":\"image/png\",\"filename\":\"pixel.png\",\"url\":\"data:image/png;base64,iVBOR\"}"
                + "]";
        List<JsonObject> out = OpenCodeMessageConverter.convert(wrap(entry("user", parts)));

        assertEquals(1, out.size());
        JsonObject user = out.get(0);
        assertEquals("user", user.get("type").getAsString());
        assertEquals("Check these files please.\n@notes.txt", user.get("content").getAsString());

        JsonArray blocks = user.getAsJsonObject("raw")
                .getAsJsonObject("message")
                .getAsJsonArray("content");
        // 2 attachment chips + 1 image + 1 text, no synthetic text blocks
        assertEquals(4, blocks.size());
        assertEquals("attachment", blocks.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("memory.txt", blocks.get(0).getAsJsonObject().get("fileName").getAsString());
        assertEquals("text/plain", blocks.get(0).getAsJsonObject().get("mediaType").getAsString());
        assertEquals("attachment", blocks.get(1).getAsJsonObject().get("type").getAsString());
        assertEquals("notes.txt", blocks.get(1).getAsJsonObject().get("fileName").getAsString());
        assertEquals("image", blocks.get(2).getAsJsonObject().get("type").getAsString());
        assertEquals("data:image/png;base64,iVBOR",
                blocks.get(2).getAsJsonObject().get("src").getAsString());
        assertEquals("image/png", blocks.get(2).getAsJsonObject().get("mediaType").getAsString());
        assertEquals("text", blocks.get(3).getAsJsonObject().get("type").getAsString());
        for (int i = 0; i < blocks.size(); i++) {
            String blockText = blocks.get(i).getAsJsonObject().has("text")
                    ? blocks.get(i).getAsJsonObject().get("text").getAsString() : "";
            assertTrue("synthetic content leaked at block " + i,
                    !blockText.contains("Called the Read tool"));
        }
    }

    @Test
    public void stripsInjectedContextSectionsFromText() {
        String parts = "["
                + "{\"type\":\"text\",\"text\":\"帮我重构这段代码\\n\\n"
                + "## IDE Context\\n\\nActive file: `src/App.tsx#L10-20`\\n\\n"
                + "The user has selected the referenced lines in this file.\"}"
                + "]";
        List<JsonObject> out = OpenCodeMessageConverter.convert(wrap(entry("user", parts)));
        JsonObject user = out.get(0);
        assertEquals("帮我重构这段代码", user.get("content").getAsString());
        JsonArray blocks = user.getAsJsonObject("raw")
                .getAsJsonObject("message")
                .getAsJsonArray("content");
        assertEquals("帮我重构这段代码", blocks.get(0).getAsJsonObject().get("text").getAsString());
    }

    @Test
    public void attachmentOnlyMessageKeepsChipsWithoutText() {
        String parts = "["
                + "{\"type\":\"text\",\"text\":\"Please review the attached file(s).\"},"
                + "{\"type\":\"file\",\"mime\":\"text/plain\",\"filename\":\"gradlew\",\"url\":\"data:text/plain;base64,Z3JhZGVs\"}"
                + "]";
        List<JsonObject> out = OpenCodeMessageConverter.convert(wrap(entry("user", parts)));
        JsonObject user = out.get(0);
        assertEquals("", user.get("content").getAsString());
        JsonArray blocks = user.getAsJsonObject("raw")
                .getAsJsonObject("message")
                .getAsJsonArray("content");
        assertEquals(1, blocks.size());
        assertEquals("attachment", blocks.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("gradlew", blocks.get(0).getAsJsonObject().get("fileName").getAsString());
    }

    @Test
    public void filePartWithoutFilenameFallsBackToUrlBasename() {
        String parts = "["
                + "{\"type\":\"text\",\"text\":\"t\"},"
                + "{\"type\":\"file\",\"mime\":\"text/plain\",\"url\":\"file:///abs/path/config%20file.yml?start=1\"}"
                + "]";
        List<JsonObject> out = OpenCodeMessageConverter.convert(wrap(entry("user", parts)));
        JsonArray blocks = out.get(0).getAsJsonObject("raw")
                .getAsJsonObject("message")
                .getAsJsonArray("content");
        JsonObject chip = blocks.get(0).getAsJsonObject();
        assertEquals("attachment", chip.get("type").getAsString());
        assertEquals("config file.yml", chip.get("fileName").getAsString());
    }

    @Test
    public void emptyPartsUserMessageStillEmitted() {
        List<JsonObject> out = OpenCodeMessageConverter.convert(wrap(entry("user", "[]")));
        assertEquals(1, out.size());
        assertEquals("", out.get(0).get("content").getAsString());
    }

    @Test
    public void nonArrayInputYieldsEmptyList() {
        List<JsonObject> out = OpenCodeMessageConverter.convert(JsonParser.parseString("null"));
        assertTrue(out.isEmpty());
    }

    @Test
    public void assistantToolPartsStillConvert() {
        String parts = "["
                + "{\"type\":\"text\",\"text\":\"answer\"},"
                + "{\"type\":\"tool\",\"id\":\"prt_1\",\"tool\":\"read\",\"state\":{\"status\":\"completed\","
                + "\"input\":{\"filePath\":\"/a.txt\"},\"output\":\"file body\"}}"
                + "]";
        List<JsonObject> out = OpenCodeMessageConverter.convert(wrap(entry("assistant", parts)));
        assertEquals(2, out.size());
        JsonObject assistant = out.get(0);
        assertEquals("assistant", assistant.get("type").getAsString());
        assertEquals("answer", assistant.get("content").getAsString());
        JsonObject toolResult = out.get(1);
        assertEquals("[tool_result]", toolResult.get("content").getAsString());
    }

    @Test
    public void assistantMessageTokensConvertedToTurnUsage() {
        JsonObject info = new JsonObject();
        info.addProperty("role", "assistant");
        info.addProperty("id", "msg_asst_test");
        info.addProperty("modelID", "deepseek-v4-flash-free");
        info.addProperty("cost", 0.0025);

        JsonObject time = new JsonObject();
        time.addProperty("created", 1000L);
        time.addProperty("completed", 3200L);
        info.add("time", time);

        JsonObject tokens = new JsonObject();
        tokens.addProperty("total", 20975);
        tokens.addProperty("input", 158);
        tokens.addProperty("output", 162);
        tokens.addProperty("reasoning", 175);
        JsonObject cache = new JsonObject();
        cache.addProperty("read", 20480);
        cache.addProperty("write", 0);
        tokens.add("cache", cache);
        info.add("tokens", tokens);

        JsonObject entry = new JsonObject();
        entry.add("info", info);
        entry.add("parts", JsonParser.parseString("[{\"type\":\"text\",\"text\":\"hello\"}]").getAsJsonArray());

        List<JsonObject> out = OpenCodeMessageConverter.convert(wrap(entry));
        assertEquals(1, out.size());
        JsonObject assistant = out.get(0);
        JsonObject raw = assistant.getAsJsonObject("raw");
        assertNotNull(raw);
        assertEquals(2200000L, raw.get("durationMs").getAsLong());
        assertEquals(0.0025, raw.get("turnCostUsd").getAsDouble(), 0.0001);

        assertTrue(raw.has("turnUsage"));
        JsonObject turnUsage = raw.getAsJsonObject("turnUsage");
        assertEquals(158, turnUsage.get("input_tokens").getAsInt());
        assertEquals(337, turnUsage.get("output_tokens").getAsInt());
        assertEquals(175, turnUsage.get("reasoning_tokens").getAsInt());
        assertEquals(20480, turnUsage.get("cache_read_input_tokens").getAsInt());
        assertEquals(0, turnUsage.get("cache_creation_input_tokens").getAsInt());
        assertEquals(20975, turnUsage.get("total_tokens").getAsInt());

        // message.usage should also match
        JsonObject msgUsage = raw.getAsJsonObject("message").getAsJsonObject("usage");
        assertNotNull(msgUsage);
        assertEquals(20480, msgUsage.get("cache_read_input_tokens").getAsInt());
    }

    @Test
    public void standaloneAbortedTurnProducesNothing() {
        // Option A: stopping is silent. No assistant bubble, no notice, no error.
        String parts = "[{\"type\":\"text\",\"text\":\"已停止生成\",\"aborted\":true}]";
        List<JsonObject> out = OpenCodeMessageConverter.convert(wrap(entry("assistant", parts)));
        assertTrue(out.isEmpty());
    }

    @Test
    public void abortedWithPriorTextKeepsOnlyTheText() {
        String parts = "["
                + "{\"type\":\"text\",\"text\":\"Here is the partial content\"},"
                + "{\"type\":\"text\",\"text\":\"已停止生成\",\"aborted\":true}"
                + "]";
        List<JsonObject> out = OpenCodeMessageConverter.convert(wrap(entry("assistant", parts)));
        assertEquals(1, out.size());
        JsonObject assistant = out.get(0);
        assertEquals("assistant", assistant.get("type").getAsString());
        assertEquals("Here is the partial content", assistant.get("content").getAsString());
    }

    @Test
    public void recordedCommandTextReplacesTheExpandedTemplate() {
        String expanded = "You are a code reviewer. Your job is to review code changes...";
        JsonObject userEntry = entry("user", "[{\"type\":\"text\",\"text\":\"" + expanded + "\"}]");
        userEntry.getAsJsonObject("info").addProperty("id", "msg_cmd");

        List<JsonObject> out = OpenCodeMessageConverter.convert(
                wrap(userEntry), java.util.Map.of("msg_cmd", "/review src"));

        assertEquals(1, out.size());
        JsonObject user = out.get(0);
        assertEquals("user", user.get("type").getAsString());
        assertEquals("/review src", user.get("content").getAsString());
        JsonArray blocks = user.getAsJsonObject("raw")
                .getAsJsonObject("message")
                .getAsJsonArray("content");
        assertEquals(1, blocks.size());
        assertEquals("/review src", blocks.get(0).getAsJsonObject().get("text").getAsString());
    }

    @Test
    public void matchCommandTextsPicksNearestUnclaimedMessage() {
        // Epoch milliseconds, like the server's `time.created`.
        long base = 1_700_000_000_000L;
        JsonArray array = new JsonArray();
        array.add(userEntry("msg_a", base));
        array.add(userEntry("msg_b", base + 60L));
        List<SessionCommandTextStore.Entry> entries = List.of(
                new SessionCommandTextStore.Entry(base + 2L, "/review a"),
                new SessionCommandTextStore.Entry(base + 58L, "/review b"));

        java.util.Map<String, String> matched = OpenCodeMessageConverter.matchCommandTexts(array, entries);

        assertEquals("/review a", matched.get("msg_a"));
        assertEquals("/review b", matched.get("msg_b"));
    }

    @Test
    public void matchCommandTextsIgnoresMessagesOutsideTheWindow() {
        JsonArray array = new JsonArray();
        array.add(userEntry("msg_far", 1_700_000_000_000L));
        List<SessionCommandTextStore.Entry> entries = List.of(
                new SessionCommandTextStore.Entry(1_700_000_500_000L, "/review"));

        assertTrue(OpenCodeMessageConverter.matchCommandTexts(array, entries).isEmpty());
    }

    @Test
    public void matchCommandTextsNeverClaimsAPromptSentLater() {
        // A command the server answers without persisting a user message (a
        // client-side command routed to the endpoint) must not steal the text
        // of the next prompt the user sends minutes later.
        long base = 1_700_000_000_000L;
        JsonArray array = new JsonArray();
        array.add(userEntry("msg_next_prompt", base + 600_000L));
        List<SessionCommandTextStore.Entry> entries = List.of(
                new SessionCommandTextStore.Entry(base, "/undo"));

        assertTrue(OpenCodeMessageConverter.matchCommandTexts(array, entries).isEmpty());
    }

    @Test
    public void matchCommandTextsNeverClaimsAnOlderMessage() {
        // The record is taken when the send starts, so a message from before
        // that (beyond clock skew) belongs to an earlier turn.
        long base = 1_700_000_000_000L;
        JsonArray array = new JsonArray();
        array.add(userEntry("msg_older", base));
        List<SessionCommandTextStore.Entry> entries = List.of(
                new SessionCommandTextStore.Entry(base + 60_000L, "/review"));

        assertTrue(OpenCodeMessageConverter.matchCommandTexts(array, entries).isEmpty());
    }

    private static JsonObject userEntry(String id, long created) {
        JsonObject info = new JsonObject();
        info.addProperty("role", "user");
        info.addProperty("id", id);
        JsonObject time = new JsonObject();
        time.addProperty("created", created);
        info.add("time", time);
        JsonObject entry = new JsonObject();
        entry.add("info", info);
        entry.add("parts", JsonParser.parseString("[{\"type\":\"text\",\"text\":\"x\"}]").getAsJsonArray());
        return entry;
    }

    @Test
    public void convertsCompactionEntryToCompactionRecord() {
        String parts = "[{\"type\":\"text\",\"text\":\"This session was compacted.\"}]";
        JsonObject entryObj = entry("compaction", parts);
        JsonObject info = entryObj.getAsJsonObject("info");
        info.addProperty("isCompactSummary", true);
        info.addProperty("compactionStatus", "completed");
        info.addProperty("compactionReason", "manual");

        List<JsonObject> out = OpenCodeMessageConverter.convert(wrap(entryObj));
        assertEquals(1, out.size());
        JsonObject record = out.get(0);
        assertEquals("compaction", record.get("type").getAsString());
        assertEquals("This session was compacted.", record.get("content").getAsString());
        JsonObject meta = record.getAsJsonObject("compaction");
        assertNotNull(meta);
        assertEquals("completed", meta.get("status").getAsString());
        assertEquals("manual", meta.get("reason").getAsString());
        assertNotNull(record.getAsJsonObject("raw"));
    }

    @Test
    public void compactionReasonIsKeptOnTheRecord() {
        JsonObject entryObj = entry("compaction", "[{\"type\":\"text\",\"text\":\"## Objective\\nShip it.\"}]");
        JsonObject info = entryObj.getAsJsonObject("info");
        info.addProperty("reason", "auto");
        info.addProperty("isCompactSummary", true);

        List<JsonObject> out = OpenCodeMessageConverter.convert(wrap(entryObj));
        assertEquals(1, out.size());
        JsonObject meta = out.get(0).getAsJsonObject("compaction");
        assertEquals("auto", meta.get("reason").getAsString());
        assertEquals("## Objective\nShip it.", out.get(0).get("content").getAsString());
    }

    @Test
    public void failedCompactionIsDroppedFromHistory() {
        JsonObject entryObj = entry("compaction", "[]");
        JsonObject info = entryObj.getAsJsonObject("info");
        info.addProperty("isCompactSummary", true);
        info.addProperty("compactionStatus", "failed");
        info.addProperty("compactionReason", "manual");

        List<JsonObject> out = OpenCodeMessageConverter.convert(wrap(entryObj));
        assertEquals(0, out.size());
    }

    @Test
    public void convertsOpenCodeV1CompactionAssistantToCompactionRecord() {
        // In OpenCode v1, compaction summary is an assistant message with mode='compaction', agent='compaction', summary=true
        JsonObject entryObj = entry("assistant", "[{\"type\":\"text\",\"text\":\"## Objective\\nV1 compaction summary.\"}]");
        JsonObject info = entryObj.getAsJsonObject("info");
        info.addProperty("mode", "compaction");
        info.addProperty("agent", "compaction");
        info.addProperty("summary", true);

        List<JsonObject> out = OpenCodeMessageConverter.convert(wrap(entryObj));
        assertEquals(1, out.size());
        JsonObject record = out.get(0);
        assertEquals("compaction", record.get("type").getAsString());
        assertEquals("## Objective\nV1 compaction summary.", record.get("content").getAsString());
        JsonObject meta = record.getAsJsonObject("compaction");
        assertEquals("completed", meta.get("status").getAsString());
        assertEquals("manual", meta.get("reason").getAsString());
    }

    @Test
    public void skipsOpenCodeV1CompactionTriggerUserMessage() {
        // In OpenCode v1, compaction trigger is a user message with parts=[{type:'compaction', auto:false}]
        JsonObject triggerUser = entry("user", "[{\"type\":\"compaction\",\"auto\":false}]");
        JsonObject summaryAssistant = entry("assistant", "[{\"type\":\"text\",\"text\":\"## Summary\"}]");
        JsonObject info = summaryAssistant.getAsJsonObject("info");
        info.addProperty("mode", "compaction");
        info.addProperty("summary", true);

        com.google.gson.JsonArray array = new com.google.gson.JsonArray();
        array.add(triggerUser);
        array.add(summaryAssistant);

        List<JsonObject> out = OpenCodeMessageConverter.convert(array);
        assertEquals("Trigger user message should be skipped, only the compaction record kept", 1, out.size());
        assertEquals("compaction", out.get(0).get("type").getAsString());
        assertEquals("## Summary", out.get(0).get("content").getAsString());
    }

    @Test
    public void convertsErrorEntryToErrorMessage() {
        String parts = "[{\"type\":\"text\",\"text\":\"[Error: Rate limit exceeded. Please try again later.]\"}]";
        JsonObject entryObj = entry("assistant", parts);

        List<JsonObject> out = OpenCodeMessageConverter.convert(wrap(entryObj));
        assertEquals(1, out.size());
        JsonObject errorMsg = out.get(0);
        assertEquals("error", errorMsg.get("type").getAsString());
        assertEquals("Rate limit exceeded. Please try again later.", errorMsg.get("content").getAsString());
    }

    @Test
    public void consumesTheNormalizedErrorContractFromCore() {
        JsonObject entryObj = entry("assistant", "[]");
        JsonObject info = entryObj.getAsJsonObject("info");
        JsonObject error = new JsonObject();
        error.addProperty("kind", "APIError");
        error.addProperty("message", "Free usage limit reached. Subscribe to OpenCode Go to keep going: https://opencode.ai/go");
        error.addProperty("status", 429);
        error.addProperty("retryable", false);
        info.add("error", error);
        info.addProperty("finish", "error");

        List<JsonObject> out = OpenCodeMessageConverter.convert(wrap(entryObj));

        assertEquals(1, out.size());
        JsonObject errorMsg = out.get(0);
        assertEquals("error", errorMsg.get("type").getAsString());
        assertEquals("Free usage limit reached. Subscribe to OpenCode Go to keep going: https://opencode.ai/go",
                errorMsg.get("content").getAsString());
        JsonObject raw = errorMsg.getAsJsonObject("raw");
        assertEquals("error", raw.get("type").getAsString());
        assertEquals("APIError", raw.getAsJsonObject("error").get("kind").getAsString());
        assertEquals("error", raw.get("finish").getAsString());
    }

    @Test
    public void reconstructsUserShellCommandFromChildAssistantBashTool() {
        JsonObject userInfo = new JsonObject();
        userInfo.addProperty("role", "user");
        userInfo.addProperty("id", "msg_user_shell");
        JsonObject userTime = new JsonObject();
        userTime.addProperty("created", 1725900000000L);
        userInfo.add("time", userTime);
        JsonObject userEntry = new JsonObject();
        userEntry.add("info", userInfo);
        userEntry.add("parts", JsonParser.parseString("["
                + "{\"type\":\"text\",\"text\":\"The following tool was executed by the user\",\"synthetic\":true}"
                + "]").getAsJsonArray());

        JsonObject asstInfo = new JsonObject();
        asstInfo.addProperty("role", "assistant");
        asstInfo.addProperty("id", "msg_asst_shell");
        asstInfo.addProperty("parentID", "msg_user_shell");
        JsonObject asstTime = new JsonObject();
        asstTime.addProperty("created", 1725900001000L);
        asstInfo.add("time", asstTime);
        JsonObject asstEntry = new JsonObject();
        asstEntry.add("info", asstInfo);
        asstEntry.add("parts", JsonParser.parseString("["
                + "{\"type\":\"tool\",\"tool\":\"bash\",\"callID\":\"call_123\",\"state\":{\"input\":{\"command\":\"pwd\"},\"output\":\"/home/user\"}}"
                + "]").getAsJsonArray());

        JsonArray sessionEntries = new JsonArray();
        sessionEntries.add(userEntry);
        sessionEntries.add(asstEntry);

        List<JsonObject> out = OpenCodeMessageConverter.convert(sessionEntries);

        // out should contain:
        // 1. reconstructed user message "!bash pwd"
        // 2. assistant tool_use message
        // 3. user [tool_result] message
        assertTrue(out.size() >= 2);
        JsonObject userMsg = out.get(0);
        assertEquals("user", userMsg.get("type").getAsString());
        assertEquals("!bash pwd", userMsg.get("content").getAsString());
        JsonArray blocks = userMsg.getAsJsonObject("raw")
                .getAsJsonObject("message")
                .getAsJsonArray("content");
        assertEquals(1, blocks.size());
        assertEquals("!bash pwd", blocks.get(0).getAsJsonObject().get("text").getAsString());
    }

    private static JsonElement wrap(JsonObject entry) {
        JsonArray arr = new JsonArray();
        arr.add(entry);
        return arr;
    }
}
