package com.opencodebuddy.session;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
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

    private static JsonElement wrap(JsonObject entry) {
        JsonArray arr = new JsonArray();
        arr.add(entry);
        return arr;
    }
}
