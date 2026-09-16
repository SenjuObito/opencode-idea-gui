package com.opencodebuddy.export;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class SessionMarkdownFormatterTest {

    @Test
    public void testFormatSessionMarkdown() {
        String json = "[" +
                "  {" +
                "    \"info\": { \"role\": \"user\" }," +
                "    \"parts\": [ { \"type\": \"text\", \"text\": \"帮我写一个快速排序\" } ]" +
                "  }," +
                "  {" +
                "    \"info\": { \"role\": \"assistant\" }," +
                "    \"parts\": [" +
                "      { \"type\": \"reasoning\", \"text\": \"分析快速排序算法步骤...\" }," +
                "      { \"type\": \"text\", \"text\": \"以下是快速排序实现：\" }," +
                "      {" +
                "        \"type\": \"tool\"," +
                "        \"tool\": \"write_to_file\"," +
                "        \"state\": {" +
                "          \"status\": \"completed\"," +
                "          \"input\": { \"path\": \"QuickSort.java\" }," +
                "          \"output\": \"Success\"" +
                "        }" +
                "      }," +
                "      { \"type\": \"file\", \"filename\": \"QuickSort.java\" }" +
                "    ]" +
                "  }" +
                "]";

        var messagesElement = JsonParser.parseString(json);
        String md = SessionMarkdownFormatter.format("排序算法讨论", "ses_test_abc", messagesElement);

        assertTrue(md.contains("# 💬 排序算法讨论"));
        assertTrue(md.contains("> **会话 ID**: `ses_test_abc`"));
        assertTrue(md.contains("### 🧑 User"));
        assertTrue(md.contains("帮我写一个快速排序"));
        assertTrue(md.contains("### 🤖 Assistant"));
        assertTrue(md.contains("💭 思考过程 (Reasoning)"));
        assertTrue(md.contains("分析快速排序算法步骤..."));
        assertTrue(md.contains("以下是快速排序实现："));
        assertTrue(md.contains("🔧 工具调用: <code>write_to_file</code>"));
        assertTrue(md.contains("QuickSort.java"));
        assertTrue(md.contains("📎 **附件/文件**: `QuickSort.java`"));
    }
}
