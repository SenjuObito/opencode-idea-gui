package com.opencodebuddy.session;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * v2 数据形状回归：转换器不能再让「一条消息的一个字段」毁掉整个历史会话。
 *
 * <p>这三个形状都来自真实库（{@code ~/.local/share/opencode/opencode.db}）：</p>
 *
 * <ol>
 *   <li>{@code tool.state.error} 在 v2 是对象 {@code {type, message}}，在 v1 是字符串。
 *       旧代码按字符串读，Gson 抛 {@code UnsupportedOperationException("JsonObject")}，
 *       一路冒到 {@code SessionProviderRouter}，用户看到的就是
 *       「Failed to load messages for session X: JsonObject」+ 空白标签页。
 *       只有带出错工具的会话会中招，所以症状是「个别会话加载失败」。</li>
 *   <li>中断/错误语义标记（{@code aborted} / {@code isError}）必须转成前端认识的
 *       消息类型：{@code aborted} → {@code notification}（subtype=stopped），
 *       {@code isError} → {@code error} 消息。丢掉标记的话，前端只能把它们当成
 *       普通文本渲染。</li>
 *   <li>任何字段类型不符都只该丢掉那一个字段/那一条消息，不该抛出。</li>
 * </ol>
 */
public class OpenCodeMessageConverterV2ShapeTest {

    // ===== error 形状：v2 对象 / v1 字符串都不能崩 =====

    @Test
    public void anObjectErrorDoesNotFailTheWholeSessionLoad() {
        JsonObject entry = assistantToolEntry("ls", null, "error");
        entry.getAsJsonArray("parts").get(0).getAsJsonObject().getAsJsonObject("state")
                .add("error", errorObject("aborted", "Tool execution interrupted"));

        List<JsonObject> messages = OpenCodeMessageConverter.convert(arrayOf(entry));

        // 关键：不抛异常，且 tool_result 带上了人话错误（而不是整场加载失败）
        assertEquals("assistant tool_use + tool_result 两条都应产出", 2, messages.size());
        String resultText = toolResultText(messages.get(1));
        assertTrue("错误信息应取自 error.message，实际: " + resultText,
                resultText.contains("Tool execution interrupted"));
    }

    @Test
    public void aStringErrorStillWorksForV1Payloads() {
        JsonObject entry = assistantToolEntry("ls", null, "error");
        entry.getAsJsonArray("parts").get(0).getAsJsonObject().getAsJsonObject("state")
                .addProperty("error", "plain v1 failure");

        List<JsonObject> messages = OpenCodeMessageConverter.convert(arrayOf(entry));

        assertEquals(2, messages.size());
        assertTrue(toolResultText(messages.get(1)).contains("plain v1 failure"));
    }

    @Test
    public void anErrorObjectWithoutMessageFallsBackToItsType() {
        JsonObject entry = assistantToolEntry("ls", null, "error");
        entry.getAsJsonArray("parts").get(0).getAsJsonObject().getAsJsonObject("state")
                .add("error", errorObject("aborted", null));

        List<JsonObject> messages = OpenCodeMessageConverter.convert(arrayOf(entry));

        assertTrue("type 应该兜底，实际: " + toolResultText(messages.get(1)),
                toolResultText(messages.get(1)).contains("aborted"));
    }

    // ===== 容错：一条坏消息不拖垮整场加载 =====

    @Test
    public void oneMalformedEntryIsSkippedAndTheRestStillLoad() {
        JsonObject good1 = userEntry("第一条（正常）");
        JsonObject bad = userEntry("第二条（role 是对象，畸形）");
        bad.getAsJsonObject("info").add("role", errorObject("weird", "role should be a string"));
        JsonObject good2 = userEntry("第三条（正常）");

        List<JsonObject> messages = OpenCodeMessageConverter.convert(arrayOf(good1, bad, good2));

        assertEquals("坏消息被跳过，两条好的必须还在", 2, messages.size());
        assertTrue(textOf(messages.get(0)).contains("第一条"));
        assertTrue(textOf(messages.get(1)).contains("第三条"));
    }

    @Test
    public void aTextFieldThatIsAnObjectIsTreatedAsEmptyNotAFailure() {
        JsonObject entry = userEntry(null);
        JsonObject part = entry.getAsJsonArray("parts").get(0).getAsJsonObject();
        part.remove("text");
        part.add("text", errorObject("not-a-string", "text should be a string"));

        List<JsonObject> messages = OpenCodeMessageConverter.convert(arrayOf(entry));

        assertEquals("消息仍应产出（文本为空）", 1, messages.size());
        assertEquals("", textOf(messages.get(0)));
    }

    // ===== 语义标记透传 =====

    @Test
    public void anAbortedNoticeCarriesItsMarkerToTheFrontend() {
        JsonObject entry = assistantEntry("已停止生成", 1, 1);
        JsonObject part = entry.getAsJsonArray("parts").get(0).getAsJsonObject();
        part.addProperty("aborted", true);

        List<JsonObject> messages = OpenCodeMessageConverter.convert(arrayOf(entry));

        assertEquals(1, messages.size());
        JsonObject msg = messages.get(0);
        assertEquals("notification", msg.get("type").getAsString());
        assertEquals("已停止生成", msg.get("content").getAsString());
        JsonObject raw = msg.getAsJsonObject("raw");
        assertTrue(raw.has("aborted") && raw.get("aborted").getAsBoolean());
        assertEquals("stopped", raw.get("subtype").getAsString());
    }

    @Test
    public void aRealErrorCarriesIsErrorAndKeepsItsText() {
        JsonObject entry = assistantEntry("[Error: upstream connect error]", 1, 1);
        entry.getAsJsonArray("parts").get(0).getAsJsonObject().addProperty("isError", true);

        List<JsonObject> messages = OpenCodeMessageConverter.convert(arrayOf(entry));

        // isError 的 part 走的是独立的 error 消息，而不是带 isError 标记的普通
        // text block：前端的 MessageItem 只按 type === 'error' 渲染错误气泡，
        // 从来没有人读 block.isError。把它留在 assistant 气泡里反而会显示成
        // 普通回复。
        assertEquals("错误 part 应产出独立的 error 消息", 1, messages.size());
        JsonObject msg = messages.get(0);
        assertEquals("error", msg.get("type").getAsString());
        assertEquals("文本必须保留，只剥掉 [Error: ] 外壳",
                "upstream connect error", msg.get("content").getAsString());
        assertFalse("aborted 与 isError 互斥", msg.getAsJsonObject("raw").has("aborted"));
    }

    @Test
    public void aPlainTextPartCarriesNeitherMarker() {
        JsonObject entry = assistantEntry("普通回复", 1, 1);

        List<JsonObject> messages = OpenCodeMessageConverter.convert(arrayOf(entry));

        JsonObject block = firstContentBlock(messages.get(0));
        assertFalse(block.has("aborted"));
        assertFalse(block.has("isError"));
    }

    // ===== helpers =====

    private static JsonArray arrayOf(JsonObject... entries) {
        JsonArray array = new JsonArray();
        for (JsonObject entry : entries) {
            array.add(entry);
        }
        return array;
    }

    private static JsonObject errorObject(String type, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("type", type);
        if (message != null) {
            error.addProperty("message", message);
        }
        return error;
    }

    private static JsonObject userEntry(String text) {
        JsonObject part = new JsonObject();
        part.addProperty("type", "text");
        if (text != null) {
            part.addProperty("text", text);
        }

        JsonArray parts = new JsonArray();
        parts.add(part);

        JsonObject info = new JsonObject();
        info.addProperty("id", "msg_u");
        info.addProperty("role", "user");
        JsonObject time = new JsonObject();
        time.addProperty("created", 1_700_000_000_000L);
        info.add("time", time);

        JsonObject entry = new JsonObject();
        entry.add("info", info);
        entry.add("parts", parts);
        return entry;
    }

    private static JsonObject assistantEntry(String text, long input, long output) {
        JsonObject entry = userEntry(text);
        JsonObject info = entry.getAsJsonObject("info");
        info.addProperty("role", "assistant");
        info.addProperty("modelID", "deepseek-v4-pro");
        JsonObject tokens = new JsonObject();
        tokens.addProperty("input", input);
        tokens.addProperty("output", output);
        tokens.addProperty("reasoning", 0);
        info.add("tokens", tokens);
        return entry;
    }

    /** 一个只带 tool part 的 assistant 条目；{@code output} 为 null 表示不设置 output 字段。 */
    private static JsonObject assistantToolEntry(String command, String output, String status) {
        JsonObject tool = new JsonObject();
        tool.addProperty("type", "tool");
        tool.addProperty("id", "call_1");
        tool.addProperty("callID", "call_1");
        tool.addProperty("tool", "bash");

        JsonObject state = new JsonObject();
        JsonObject toolInput = new JsonObject();
        toolInput.addProperty("command", command);
        state.add("input", toolInput);
        if (output != null) {
            state.addProperty("output", output);
        }
        state.addProperty("status", status);
        tool.add("state", state);

        JsonArray parts = new JsonArray();
        parts.add(tool);

        JsonObject info = new JsonObject();
        info.addProperty("id", "msg_a_tool");
        info.addProperty("role", "assistant");
        JsonObject time = new JsonObject();
        time.addProperty("created", 1_700_000_001_000L);
        info.add("time", time);

        JsonObject entry = new JsonObject();
        entry.add("info", info);
        entry.add("parts", parts);
        return entry;
    }

    private static String textOf(JsonObject message) {
        JsonObject raw = message.getAsJsonObject("raw");
        JsonObject messageObj = raw.getAsJsonObject("message");
        JsonArray content = messageObj.getAsJsonArray("content");
        for (com.google.gson.JsonElement element : content) {
            JsonObject block = element.getAsJsonObject();
            if ("text".equals(block.get("type").getAsString())) {
                return block.get("text").getAsString();
            }
        }
        return "";
    }

    private static String toolResultText(JsonObject message) {
        JsonObject raw = message.getAsJsonObject("raw");
        return raw.getAsJsonObject("message").getAsJsonArray("content")
                .get(0).getAsJsonObject().get("content").getAsString();
    }

    private static JsonObject firstContentBlock(JsonObject message) {
        return message.getAsJsonObject("raw").getAsJsonObject("message")
                .getAsJsonArray("content").get(0).getAsJsonObject();
    }
}
