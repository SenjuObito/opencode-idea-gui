package com.opencodebuddy.handler;

import com.opencodebuddy.handler.core.BaseMessageHandler;
import com.opencodebuddy.handler.core.HandlerContext;

import com.opencodebuddy.ui.toolwindow.OpencodeBuddyToolWindow;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;


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
        try {
            OpencodeBuddyToolWindow.createNewTab(project);
        } catch (Exception e) {
            LOG.error("[TabHandler] Error creating new tab: " + e.getMessage(), e);
            callJavaScript("addErrorMessage", escapeJs("创建新标签页失败: " + e.getMessage()));
        }
    }
}
