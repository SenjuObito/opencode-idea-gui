package com.opencodebuddy.handler;

import com.opencodebuddy.handler.core.BaseMessageHandler;
import com.opencodebuddy.handler.core.HandlerContext;

import com.opencodebuddy.ui.toolwindow.OpencodeBuddyChatWindow;
import com.opencodebuddy.ui.toolwindow.OpencodeBuddyToolWindow;
import com.opencodebuddy.settings.TabStateService;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;


import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.function.Consumer;

/**
 * Tab management handler.
 * Handles creating new chat tabs and updating tab titles in the tool window.
 */
public class TabHandler extends BaseMessageHandler {

    private static final Logger LOG = Logger.getInstance(TabHandler.class);

    private static final String[] SUPPORTED_TYPES = {
        "create_new_tab",
        "update_tab_title"
    };

    private final Consumer<String> tabTitleUpdater;

    public TabHandler(HandlerContext context) {
        this(context, null);
    }

    public TabHandler(HandlerContext context, Consumer<String> tabTitleUpdater) {
        super(context);
        this.tabTitleUpdater = tabTitleUpdater;
    }

    @Override
    public String[] getSupportedTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public boolean handle(String type, String content) {
        if ("create_new_tab".equals(type)) {
            LOG.debug("[TabHandler] Processing create_new_tab");
            handleCreateNewTab();
            return true;
        }
        if ("update_tab_title".equals(type)) {
            LOG.debug("[TabHandler] Processing update_tab_title");
            handleUpdateTabTitle(content);
            return true;
        }
        return false;
    }

    private void handleUpdateTabTitle(String content) {
        String title = content;
        if (title != null && title.startsWith("{") && title.endsWith("}")) {
            try {
                JsonObject json = JsonParser.parseString(title).getAsJsonObject();
                if (json.has("title") && !json.get("title").isJsonNull()) {
                    title = json.get("title").getAsString();
                }
            } catch (Exception ignored) {
            }
        }
        if (tabTitleUpdater != null) {
            tabTitleUpdater.accept(title);
        }
    }

    /**
     * Create a new chat tab in the tool window
     */
    private void handleCreateNewTab() {
        Project project = context.getProject();

        ToolWindowManager.getInstance(project).invokeLater(() -> {
            try {
                // Get the tool window
                ToolWindow toolWindow = ToolWindowManager.getInstance(project)
                        .getToolWindow(OpencodeBuddyToolWindow.TOOL_WINDOW_ID);
                if (toolWindow == null) {
                    LOG.error("[TabHandler] Tool window not found");
                    callJavaScript("addErrorMessage", escapeJs("无法找到 OpenCode 工具窗口"));
                    return;
                }

                ContentManager contentManager = toolWindow.getContentManager();
                Content selectedContent = contentManager.getSelectedContent();
                OpencodeBuddyChatWindow sourceWindow = selectedContent == null
                        ? null
                        : OpencodeBuddyToolWindow.getChatWindowForContent(selectedContent);
                if (sourceWindow == null) {
                    sourceWindow = OpencodeBuddyToolWindow.getChatWindow(project);
                }

                // Create a new chat window instance with skipRegister=true (don't replace the main instance)
                OpencodeBuddyChatWindow newChatWindow = new OpencodeBuddyChatWindow(project, true);
                newChatWindow.inheritSessionPreferencesFrom(sourceWindow);

                // Get tab index before adding content
                int tabIndex = contentManager.getContentCount();

                // Check if there's a saved name for this tab index
                TabStateService tabStateService = TabStateService.getInstance(project);
                String savedName = tabStateService.getTabName(tabIndex);

                // Create a tab name: use saved name or generate new one
                String tabName;
                if (savedName != null && !savedName.isEmpty()) {
                    tabName = savedName;
                    LOG.info("[TabHandler] Restored tab name from storage: " + tabName);
                } else {
                    tabName = OpencodeBuddyToolWindow.getNextTabName(toolWindow);
                }

                // Create and add the new tab content
                ContentFactory contentFactory = ContentFactory.getInstance();
                Content content = contentFactory.createContent(newChatWindow.getContent(), tabName, false);
                content.setCloseable(true);
                content.setDisposer(newChatWindow::dispose);

                contentManager.addContent(content);
                newChatWindow.setParentContent(content);
                contentManager.setSelectedContent(content);

                // Ensure the tool window is visible
                toolWindow.show(null);

                LOG.info("[TabHandler] Created new tab: " + tabName);
            } catch (Exception e) {
                LOG.error("[TabHandler] Error creating new tab: " + e.getMessage(), e);
                callJavaScript("addErrorMessage", escapeJs("创建新标签页失败: " + e.getMessage()));
            }
        });
    }
}
