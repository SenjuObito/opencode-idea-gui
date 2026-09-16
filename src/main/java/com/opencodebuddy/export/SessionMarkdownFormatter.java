package com.opencodebuddy.export;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Formats OpenCode SDK session message entries and metadata into standard Markdown document.
 */
public final class SessionMarkdownFormatter {

    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();

    private SessionMarkdownFormatter() {
    }

    /**
     * 将 OpenCode 会话元数据与消息列表格式化为 Markdown 字符串。
     */
    public static String format(String title, String sessionId, JsonElement messagesElement) {
        StringBuilder sb = new StringBuilder();

        // 1. Header & 元信息
        String displayTitle = (title != null && !title.trim().isEmpty()) ? title.trim() : "Untitled Session";
        sb.append("# 💬 ").append(displayTitle).append("\n\n");

        List<String> metaItems = new ArrayList<>();
        if (sessionId != null && !sessionId.trim().isEmpty()) {
            metaItems.add("> **会话 ID**: `" + sessionId.trim() + "`");
        }
        String currentTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        metaItems.add("> **导出时间**: `" + currentTime + "`");

        if (!metaItems.isEmpty()) {
            sb.append(String.join("  \n", metaItems)).append("\n\n---\n\n");
        }

        // 2. 遍历并格式化消息列表
        if (messagesElement != null && messagesElement.isJsonArray()) {
            JsonArray array = messagesElement.getAsJsonArray();
            for (JsonElement item : array) {
                if (!item.isJsonObject()) {
                    continue;
                }
                JsonObject entry = item.getAsJsonObject();
                JsonObject info = entry.has("info") && entry.get("info").isJsonObject()
                        ? entry.getAsJsonObject("info") : new JsonObject();
                JsonArray parts = entry.has("parts") && entry.get("parts").isJsonArray()
                        ? entry.getAsJsonArray("parts") : new JsonArray();

                String role = info.has("role") && !info.get("role").isJsonNull()
                        ? info.get("role").getAsString() : "";

                if ("user".equals(role)) {
                    sb.append("### 🧑 User\n\n");
                    String content = formatParts(parts);
                    sb.append(!content.isEmpty() ? content : "*(空消息)*");
                    sb.append("\n\n---\n\n");
                } else if ("assistant".equals(role)) {
                    sb.append("### 🤖 Assistant\n\n");
                    String content = formatParts(parts);
                    sb.append(!content.isEmpty() ? content : "*(无回复内容)*");
                    sb.append("\n\n---\n\n");
                } else if ("system".equals(role)) {
                    sb.append("### ⚙️ System\n\n");
                    String content = formatParts(parts);
                    sb.append(!content.isEmpty() ? content : "*(系统消息)*");
                    sb.append("\n\n---\n\n");
                }
            }
        }

        return sb.toString();
    }

    private static String formatParts(JsonArray parts) {
        List<String> out = new ArrayList<>();
        for (JsonElement element : parts) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject part = element.getAsJsonObject();
            String type = part.has("type") && !part.get("type").isJsonNull()
                    ? part.get("type").getAsString() : "";

            switch (type) {
                case "text": {
                    if (part.has("text") && !part.get("text").isJsonNull()) {
                        String text = part.get("text").getAsString().trim();
                        if (!text.isEmpty()) {
                            out.add(text);
                        }
                    }
                    break;
                }
                case "reasoning": {
                    if (part.has("text") && !part.get("text").isJsonNull()) {
                        String text = part.get("text").getAsString().trim();
                        if (!text.isEmpty()) {
                            out.add("<details>\n<summary>💭 思考过程 (Reasoning)</summary>\n\n" + text + "\n</details>");
                        }
                    }
                    break;
                }
                case "tool": {
                    out.add(formatToolPart(part));
                    break;
                }
                case "file": {
                    String name = "attachment";
                    if (part.has("filename") && !part.get("filename").isJsonNull()) {
                        name = part.get("filename").getAsString();
                    } else if (part.has("url") && !part.get("url").isJsonNull()) {
                        name = part.get("url").getAsString();
                    }
                    out.add("📎 **附件/文件**: `" + name + "`");
                    break;
                }
                case "image": {
                    if (part.has("url") && !part.get("url").isJsonNull()) {
                        out.add("![image](" + part.get("url").getAsString() + ")");
                    }
                    break;
                }
                default: {
                    if (part.has("text") && !part.get("text").isJsonNull()) {
                        String text = part.get("text").getAsString().trim();
                        if (!text.isEmpty()) {
                            out.add(text);
                        }
                    }
                    break;
                }
            }
        }
        return String.join("\n\n", out);
    }

    private static String formatToolPart(JsonObject part) {
        String toolName = "tool";
        if (part.has("tool") && !part.get("tool").isJsonNull()) {
            toolName = part.get("tool").getAsString();
        } else if (part.has("name") && !part.get("name").isJsonNull()) {
            toolName = part.get("name").getAsString();
        }

        JsonObject state = part.has("state") && part.get("state").isJsonObject()
                ? part.getAsJsonObject("state") : new JsonObject();

        String status = state.has("status") && !state.get("status").isJsonNull()
                ? state.get("status").getAsString() : "";
        String statusIcon = "error".equals(status) ? "❌" : "🔧";

        StringBuilder sb = new StringBuilder();
        sb.append("<details>\n<summary>").append(statusIcon).append(" 工具调用: <code>")
                .append(toolName).append("</code></summary>\n\n");

        if (state.has("input") && !state.get("input").isJsonNull()) {
            JsonElement inputElem = state.get("input");
            String inputStr;
            if (inputElem.isJsonPrimitive() && inputElem.getAsJsonPrimitive().isString()) {
                inputStr = inputElem.getAsString();
            } else {
                inputStr = PRETTY_GSON.toJson(inputElem);
            }
            sb.append("**输入参数**:\n```json\n").append(inputStr).append("\n```\n\n");
        }

        if (state.has("output") && !state.get("output").isJsonNull()) {
            JsonElement outputElem = state.get("output");
            String outputStr;
            if (outputElem.isJsonPrimitive() && outputElem.getAsJsonPrimitive().isString()) {
                outputStr = outputElem.getAsString();
            } else {
                outputStr = PRETTY_GSON.toJson(outputElem);
            }
            sb.append("**执行输出**:\n```text\n").append(outputStr).append("\n```\n");
        }

        sb.append("</details>");
        return sb.toString();
    }
}
