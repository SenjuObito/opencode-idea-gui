package com.opencodebuddy.handler.history;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.fileChooser.FileSaverDialog;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.opencodebuddy.export.SessionMarkdownFormatter;
import com.opencodebuddy.handler.core.HandlerContext;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Service for exporting session data as Markdown.
 * Messages are loaded through the OpenCode daemon ({@code opencode.listMessages})
 * and formatted into standard Markdown.
 */
class HistoryExportService {

    private static final Logger LOG = Logger.getInstance(HistoryExportService.class);

    private final HandlerContext context;
    private final Gson gson = new Gson();

    HistoryExportService(HandlerContext context) {
        this.context = context;
    }

    /**
     * Export session data as Markdown.
     * Reads all messages of the session and opens a save dialog for the user.
     */
    void handleExportSession(String content, String currentProvider) {
        CompletableFuture.runAsync(() -> {
            LOG.info("[HistoryHandler] ========== 开始导出会话为 Markdown ==========");

            try {
                // Parse JSON from frontend to extract sessionId and title
                JsonObject exportRequest = gson.fromJson(content, JsonObject.class);
                String sessionId = exportRequest.has("sessionId") && !exportRequest.get("sessionId").isJsonNull()
                        ? exportRequest.get("sessionId").getAsString() : "";
                String title = exportRequest.has("title") && !exportRequest.get("title").isJsonNull()
                        ? exportRequest.get("title").getAsString() : "Session";

                if (sessionId.isBlank()) {
                    LOG.warn("[HistoryHandler] Session ID is empty");
                    showToast("无法导出会话：会话 ID 为空", "error");
                    return;
                }

                String projectPath = context.resolveEffectiveWorkingDirectory();
                if (projectPath == null) {
                    LOG.warn("[HistoryHandler] Project base path is null");
                    showToast("无法导出会话：工作目录不可用", "error");
                    return;
                }

                LOG.info("[HistoryHandler] SessionId: " + sessionId + ", Title: " + title);

                // 1. 从 daemon 获取会话的原始消息
                JsonElement rawMessagesElement = context.getOpenCodeSDKBridge()
                        .listMessages(sessionId, projectPath)
                        .get(30, TimeUnit.SECONDS);

                // 2. 格式化为 Markdown
                String markdown = SessionMarkdownFormatter.format(title, sessionId, rawMessagesElement);

                // 3. 在 EDT 中调起保存文件弹窗
                ApplicationManager.getApplication().invokeLater(() -> {
                    try {
                        String sanitizedTitle = title.replaceAll("[/\\\\?%*:|\"<>]", "_").replaceAll("\\s+", "_");
                        if (sanitizedTitle.length() > 50) {
                            sanitizedTitle = sanitizedTitle.substring(0, 50);
                        }
                        if (sanitizedTitle.isBlank()) {
                            sanitizedTitle = "session_" + sessionId.substring(0, Math.min(8, sessionId.length()));
                        }
                        String defaultFileName = sanitizedTitle + ".md";

                        FileSaverDescriptor descriptor = new FileSaverDescriptor(
                                "导出会话为 Markdown",
                                "选择保存 Markdown 文件的路径",
                                "md"
                        );
                        FileSaverDialog dialog = FileChooserFactory.getInstance()
                                .createSaveFileDialog(descriptor, context.getProject());

                        VirtualFile baseDir = context.getProject() != null ? context.getProject().getBaseDir() : null;
                        VirtualFileWrapper fileWrapper = dialog.save(baseDir, defaultFileName);

                        if (fileWrapper != null) {
                            File targetFile = fileWrapper.getFile();
                            Files.writeString(targetFile.toPath(), markdown, StandardCharsets.UTF_8);
                            LOG.info("[HistoryHandler] 会话已成功导出至: " + targetFile.getAbsolutePath());
                            showToast("会话已成功导出为 Markdown", "success");
                        }
                    } catch (Exception e) {
                        LOG.error("[HistoryHandler] 保存 Markdown 失败: " + e.getMessage(), e);
                        showToast("保存 Markdown 失败: " + e.getMessage(), "error");
                    }
                });

                LOG.info("[HistoryHandler] ========== 导出会话请求处理完成 ==========");

            } catch (Exception e) {
                LOG.error("[HistoryHandler] 导出会话失败: " + e.getMessage(), e);
                showToast("导出失败: " + (e.getMessage() != null ? e.getMessage() : "未知错误"), "error");
            }
        });
    }

    private void showToast(String message, String type) {
        ApplicationManager.getApplication().invokeLater(() -> {
            String jsCode = "if (window.addToast) { " +
                    "  window.addToast('" + context.escapeJs(message) + "', '" + type + "'); " +
                    "}";
            context.executeJavaScriptOnEDT(jsCode);
        });
    }
}
