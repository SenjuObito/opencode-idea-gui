package com.opencodebuddy.ui.toolwindow;

import com.opencodebuddy.i18n.OpenCodeBuddyBundle;
import com.opencodebuddy.settings.TabStateService;
import com.opencodebuddy.startup.BridgePreloader;
import com.opencodebuddy.ui.detached.DetachedWindowManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.JBColor;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;
import com.intellij.ui.content.ContentManagerEvent;
import com.intellij.ui.content.ContentManagerListener;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Claude SDK chat tool window.
 * Implements DumbAware to allow usage during index building.
 */
public class ClaudeSDKToolWindow implements ToolWindowFactory, DumbAware {

    private static final Logger LOG = Logger.getInstance(ClaudeSDKToolWindow.class);
    public static final String TOOL_WINDOW_ID = "OpenCode";
    public static final String TOOL_WINDOW_DISPLAY_NAME = "OpenCode Buddy";
    private static final Map<Project, ClaudeChatWindow> instances = new ConcurrentHashMap<>();
    private static final Map<Content, ClaudeChatWindow> contentToWindowMap = new ConcurrentHashMap<>();
    private static volatile boolean shutdownHookRegistered = false;
    private static final String TAB_NAME_PREFIX = "AI";
    /** Matches tab names like "AI1", "AI1..." (answering) or "AI1 (completed)" — extracts the numeric part. */
    private static final java.util.regex.Pattern TAB_NAME_PATTERN =
            java.util.regex.Pattern.compile("^" + TAB_NAME_PREFIX + "(\\d+)");
    private static final Set<Content> detachingContents =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    public static ClaudeChatWindow getChatWindow(Project project) {
        return instances.get(project);
    }

    public static String getNextTabName(ToolWindow toolWindow) {
        if (toolWindow == null) {
            return TAB_NAME_PREFIX + "1";
        }

        ContentManager contentManager = toolWindow.getContentManager();
        int maxNumber = 0;

        for (Content content : contentManager.getContents()) {
            String displayName = content.getDisplayName();
            if (displayName == null) {
                continue;
            }
            // Extract the leading number after the "AI" prefix so status suffixes
            // like "AI1..." (answering) or "AI1 (completed)" still count.
            java.util.regex.Matcher matcher = TAB_NAME_PATTERN.matcher(displayName);
            if (matcher.find()) {
                try {
                    int number = Integer.parseInt(matcher.group(1));
                    if (number > maxNumber) {
                        maxNumber = number;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }

        return TAB_NAME_PREFIX + (maxNumber + 1);
    }

    static void registerWindow(Project project, ClaudeChatWindow window) {
        synchronized (instances) {
            ClaudeChatWindow oldInstance = instances.get(project);
            if (oldInstance != null && oldInstance != window) {
                LOG.warn("Window instance already exists for project " + project.getName() + ", replacing old instance");
                oldInstance.dispose();
            }
            instances.put(project, window);
        }
    }

    static void unregisterWindow(Project project, ClaudeChatWindow window) {
        synchronized (instances) {
            if (instances.get(project) == window) {
                instances.remove(project);
            }
        }
    }

    static void registerContentMapping(Content content, ClaudeChatWindow window) {
        contentToWindowMap.put(content, window);
    }

    static void unregisterContentMapping(Content content) {
        contentToWindowMap.remove(content);
    }

    private static Set<ClaudeChatWindow> collectProjectChatWindows(@NotNull Project project) {
        Set<ClaudeChatWindow> windows = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        ClaudeChatWindow mainWindow = instances.get(project);
        if (mainWindow != null) {
            windows.add(mainWindow);
        }
        for (ClaudeChatWindow window : contentToWindowMap.values()) {
            if (window != null && project.equals(window.getProject())) {
                windows.add(window);
            }
        }
        windows.addAll(DetachedWindowManager.getAllDetachedChatWindows(project));
        return windows;
    }

    /**
     * Public version of {@link #collectProjectChatWindows(Project)} for NodeProcessRegistry.
     * Returns all chat windows belonging to the given project, including tab windows
     * and detached floating windows.
     */
    public static Set<ClaudeChatWindow> getAllChatWindowsForProject(@NotNull Project project) {
        return collectProjectChatWindows(project);
    }

    private static Set<ClaudeChatWindow> collectAllChatWindows() {
        Set<ClaudeChatWindow> windows = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        windows.addAll(instances.values());
        windows.addAll(contentToWindowMap.values());
        windows.addAll(DetachedWindowManager.getAllDetachedChatWindows());
        return windows;
    }

    private static void cleanupWindowProcesses(@NotNull ClaudeChatWindow window) {
        try {
            if (window.getOpenCodeSDKBridge() != null) {
                window.getOpenCodeSDKBridge().stopDaemon();
            }
        } catch (Exception e) {
            LOG.error("[ShutdownHook] Error cleaning up processes: " + e.getMessage(), e);
        }
    }

    private static void disposeProjectChatWindows(@NotNull Project project) {
        Set<ClaudeChatWindow> windows = collectProjectChatWindows(project);
        if (windows.isEmpty()) {
            return;
        }
        LOG.info("[ToolWindow] Disposing " + windows.size() + " chat window(s) for project: " + project.getName());
        for (ClaudeChatWindow window : new HashSet<>(windows)) {
            if (window != null && !window.isDisposed()) {
                try {
                    window.dispose();
                } catch (Exception e) {
                    LOG.error("[ToolWindow] Failed to dispose chat window for project: " + project.getName(), e);
                }
            }
        }
    }

    /**
     * Mark a Content as being detached (moving to a floating window).
     * This prevents the contentRemoved listener from disposing the associated ClaudeChatWindow.
     */
    public static void markContentAsDetaching(Content content) {
        detachingContents.add(content);
    }

    public static void unmarkContentAsDetaching(Content content) {
        detachingContents.remove(content);
    }

    static boolean isContentDetaching(Content content) {
        return detachingContents.contains(content);
    }

    public static ClaudeChatWindow getChatWindowForContent(Content content) {
        return content != null ? contentToWindowMap.get(content) : null;
    }

    @Override
    public void init(@NotNull ToolWindow toolWindow) {
        toolWindow.setTitle(TOOL_WINDOW_DISPLAY_NAME);
        toolWindow.setStripeTitle(TOOL_WINDOW_DISPLAY_NAME);
    }

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        registerShutdownHook();

        // 开屏一定是新会话：先丢掉上次的标签布局与每标签会话绑定，避免任何残留
        // sessionId 被重新挂上。旧会话仍在 opencode 服务端，历史列表随时可打开。
        resetPersistedTabState(project);

        ContentFactory contentFactory = ContentFactory.getInstance();
        ContentManager contentManager = toolWindow.getContentManager();

        if (BridgePreloader.isBridgeReady()) {
            LOG.info("[ToolWindow] ai-bridge ready, creating chat window directly");
            createChatWindowContent(project, contentFactory, contentManager);
        } else {
            LOG.info("[ToolWindow] ai-bridge not ready, showing loading panel");
            JPanel loadingPanel = createLoadingPanel();
            Content loadingContent = contentFactory.createContent(loadingPanel, TAB_NAME_PREFIX + "1", false);
            contentManager.addContent(loadingContent);

            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try {
                    BridgePreloader.getSharedResolver().findSdkDir();
                    CompletableFuture<Boolean> future = BridgePreloader.waitForBridgeAsync();
                    Boolean ready = future.get(60, TimeUnit.SECONDS);

                    if (project.isDisposed()) { return; }

                    ToolWindowManager.getInstance(project).invokeLater(() -> {
                        if (project.isDisposed()) { return; }

                        if (ready != null && ready) {
                            LOG.info("[ToolWindow] ai-bridge ready, replacing loading panel with chat window");
                            replaceLoadingPanelWithChatWindow(project, contentManager, loadingContent);
                        } else {
                            LOG.error("[ToolWindow] ai-bridge preparation failed");
                            updateLoadingPanelWithError(loadingPanel, "AI Bridge preparation failed. Please restart IDE.");
                        }
                    });
                } catch (TimeoutException e) {
                    LOG.error("[ToolWindow] ai-bridge preparation timeout");
                    ToolWindowManager.getInstance(project).invokeLater(() -> {
                        if (!project.isDisposed()) {
                            updateLoadingPanelWithError(loadingPanel, "AI Bridge preparation timeout. Please restart IDE.");
                        }
                    });
                } catch (Exception e) {
                    LOG.error("[ToolWindow] ai-bridge preparation error: " + e.getMessage());
                    ToolWindowManager.getInstance(project).invokeLater(() -> {
                        if (!project.isDisposed()) {
                            updateLoadingPanelWithError(loadingPanel, "Error: " + e.getMessage());
                        }
                    });
                }
            });
        }

        registerProjectCloseListener(project);

        contentManager.addContentManagerListener(new ContentManagerListener() {
            @Override
            public void contentAdded(@NotNull ContentManagerEvent event) {
                updateTabCloseableState(contentManager);
                TabStateService tabStateService = TabStateService.getInstance(project);
                tabStateService.saveTabCount(contentManager.getContentCount());
            }

            @Override
            public void selectionChanged(@NotNull ContentManagerEvent event) {
                if (contentManager.getSelectedContent() != event.getContent()) {
                    return;
                }
                ClaudeChatWindow window = contentToWindowMap.get(event.getContent());
                if (window != null) {
                    window.onTabActivated();
                    window.resyncSessionFromServerIfNeeded();
                }
            }

            @Override
            public void contentRemoved(@NotNull ContentManagerEvent event) {
                updateTabCloseableState(contentManager);

                Content removedContent = event.getContent();
                if (isContentDetaching(removedContent)) {
                    LOG.info("[TabManager] Tab detaching to floating window, skipping dispose: "
                        + removedContent.getDisplayName());
                    return;
                }

                int removedIndex = event.getIndex();
                TabStateService tabStateService = TabStateService.getInstance(project);
                tabStateService.onTabRemoved(removedIndex);

                ClaudeChatWindow window = contentToWindowMap.get(removedContent);
                if (window != null) {
                    LOG.info("[TabManager] Disposing ClaudeChatWindow for removed tab: "
                        + removedContent.getDisplayName());
                    window.dispose();
                }
            }

            @Override
            public void contentRemoveQuery(@NotNull ContentManagerEvent event) {
                Content content = event.getContent();
                if (isContentDetaching(content)) {
                    return;
                }

                String tabName = content.getDisplayName();
                int result = com.intellij.openapi.ui.Messages.showYesNoDialog(
                    project,
                    OpenCodeBuddyBundle.message("tab.close.confirm.message", tabName),
                    OpenCodeBuddyBundle.message("tab.close.confirm.title"),
                    OpenCodeBuddyBundle.message("tab.close.confirm.yes"),
                    OpenCodeBuddyBundle.message("tab.close.confirm.no"),
                    com.intellij.openapi.ui.Messages.getQuestionIcon()
                );

                if (result != com.intellij.openapi.ui.Messages.YES) {
                    event.consume();
                }
            }
        });

        updateTabCloseableState(contentManager);
    }

    private void updateTabCloseableState(ContentManager contentManager) {
        int tabCount = contentManager.getContentCount();
        boolean closeable = tabCount > 1;

        for (Content tab : contentManager.getContents()) {
            tab.setCloseable(closeable);
        }

        LOG.debug("[TabManager] Updated tab closeable state: count=" + tabCount + ", closeable=" + closeable);
    }

    private JPanel createLoadingPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(com.opencodebuddy.util.ThemeConfigService.getBackgroundColor());

        JPanel centerPanel = new JPanel();
        centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.Y_AXIS));
        centerPanel.setOpaque(false);

        JLabel iconLabel = new JLabel("⚙");
        iconLabel.setFont(iconLabel.getFont().deriveFont(48f));
        iconLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        centerPanel.add(iconLabel);

        centerPanel.add(Box.createVerticalStrut(16));

        JLabel textLabel = new JLabel(OpenCodeBuddyBundle.message("toolwindow.preparingBridge"));
        textLabel.setFont(textLabel.getFont().deriveFont(14f));
        textLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        centerPanel.add(textLabel);

        panel.add(centerPanel);
        return panel;
    }

    private void updateLoadingPanelWithError(JPanel loadingPanel, String errorMessage) {
        loadingPanel.removeAll();

        JPanel centerPanel = new JPanel();
        centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.Y_AXIS));
        centerPanel.setOpaque(false);

        JLabel iconLabel = new JLabel("⚠");
        iconLabel.setFont(iconLabel.getFont().deriveFont(48f));
        iconLabel.setForeground(JBColor.ORANGE);
        iconLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        centerPanel.add(iconLabel);

        centerPanel.add(Box.createVerticalStrut(16));

        JLabel textLabel = new JLabel(errorMessage);
        textLabel.setFont(textLabel.getFont().deriveFont(14f));
        textLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        centerPanel.add(textLabel);

        loadingPanel.add(centerPanel);
        loadingPanel.revalidate();
        loadingPanel.repaint();
    }

    /**
     * 用聊天窗口替换启动占位面板。与 {@link #createChatWindowContent} 同一条约定：
     * 一个全新会话，不恢复任何标签或会话绑定。
     */
    private void replaceLoadingPanelWithChatWindow(
            @NotNull Project project,
            ContentManager contentManager,
            Content loadingContent
    ) {
        ClaudeChatWindow firstChatWindow = new ClaudeChatWindow(project, false);
        String firstTabName = TAB_NAME_PREFIX + "1";

        loadingContent.setComponent(firstChatWindow.getContent());
        loadingContent.setDisplayName(firstTabName);
        firstChatWindow.setParentContent(loadingContent);
        loadingContent.setDisposer(firstChatWindow::dispose);

        updateTabCloseableState(contentManager);
    }

    /**
     * 打开工具窗口时创建的唯一一个对话标签。
     *
     * 刻意不读任何持久化的标签状态：窗口每次打开都是一个全新会话，与 VS Code 插件
     * 一致。此前的实现会按落盘的 tabCount 重建多个标签，并把每个标签的 sessionId
     * 重新绑定后拉取历史消息，于是"打开插件"就等于"回到上次那段对话"。
     *
     * 旧会话仍然完整保存在 opencode 服务端，可从历史列表随时重新打开，不会丢。
     */
    private void createChatWindowContent(
            @NotNull Project project,
            ContentFactory contentFactory,
            ContentManager contentManager
    ) {
        ClaudeChatWindow chatWindow = new ClaudeChatWindow(project, false);
        String tabName = TAB_NAME_PREFIX + "1";

        Content content = contentFactory.createContent(chatWindow.getContent(), tabName, false);
        chatWindow.setParentContent(content);
        chatWindow.setOriginalTabName(tabName);
        content.setDisposer(chatWindow::dispose);
        contentManager.addContent(content);

        updateTabCloseableState(contentManager);
    }

    /**
     * 丢弃上次的标签布局与每标签会话绑定。
     *
     * 开屏既然固定为新会话，这些数据就没有读者了；留着只会在排障时误导（一个
     * 「绑着旧 sessionId」的持久化状态看起来像是应该被恢复的）。{@link TabHandler}
     * 新增标签时读的 tab 名也一并清掉，避免旧名字被翻出来复用。
     */
    private static void resetPersistedTabState(@NotNull Project project) {
        try {
            TabStateService tabStateService = TabStateService.getInstance(project);
            tabStateService.clearAllTabNames();
            tabStateService.saveTabCount(1);
            LOG.info("[TabManager] Cleared persisted tab state for a fresh start");
        } catch (Exception e) {
            LOG.warn("[TabManager] Failed to reset persisted tab state: " + e.getMessage());
        }
    }

    private static synchronized void registerShutdownHook() {
        if (shutdownHookRegistered) {
            return;
        }
        shutdownHookRegistered = true;

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("[ShutdownHook] IDEA is shutting down, cleaning up all Node.js processes...");

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<?> future = executor.submit(() -> {
                    for (ClaudeChatWindow window : collectAllChatWindows()) {
                        if (window != null) {
                            cleanupWindowProcesses(window);
                        }
                    }
                });

                future.get(3, TimeUnit.SECONDS);
                LOG.info("[ShutdownHook] Node.js process cleanup completed");
            } catch (TimeoutException e) {
                LOG.warn("[ShutdownHook] Process cleanup timed out (3s), forcing exit");
            } catch (Exception e) {
                LOG.error("[ShutdownHook] Process cleanup failed: " + e.getMessage());
            } finally {
                executor.shutdownNow();
            }
        }, "Claude-Process-Cleanup-Hook"));

        LOG.info("[ShutdownHook] JVM Shutdown Hook registered");
    }

    private static final CodeSnippetManager codeSnippetManager = new CodeSnippetManager(instances, contentToWindowMap);

    public static void addSelectionFromExternal(Project project, String selectionInfo) {
        codeSnippetManager.addSelectionFromExternal(project, selectionInfo);
    }

    /**
     * Send project-tree file references to the selected chat tab as structured
     * paths, keeping spaces inside a path separate from multi-file routing.
     */
    public static void addFileReferencesFromExternal(Project project, List<String> filePaths) {
        codeSnippetManager.addFileReferencesFromExternal(project, filePaths);
    }

    /**
     * Register project closing listener to dispose all chat windows for the project.
     * This ensures proper cleanup when a project is closed.
     *
     * @param project The project to listen to
     */
    private void registerProjectCloseListener(@NotNull Project project) {
        ToolWindowLifecycleDisposableService lifecycleDisposable = ToolWindowLifecycleDisposableService.getInstance(project);
        if (!lifecycleDisposable.markProjectCloseListenerRegistered()) {
            return;
        }
        project.getMessageBus().connect(lifecycleDisposable).subscribe(
                com.intellij.openapi.project.ProjectManager.TOPIC,
                new com.intellij.openapi.project.ProjectManagerListener() {
                    @Override
                    public void projectClosing(@NotNull Project closingProject) {
                        if (closingProject.equals(project)) {
                            LOG.info("[ToolWindow] Project closing, disposing chat windows for: " + project.getName());
                            disposeProjectChatWindows(project);
                        }
                    }
                }
        );
    }
}
