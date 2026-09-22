package com.opencodebuddy.ui;

import com.opencodebuddy.settings.OpenCodeBuddySettingsService;
import com.opencodebuddy.util.IgnoreRuleMatcher;
import com.opencodebuddy.util.JsUtils;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.editor.event.EditorMouseEvent;
import com.intellij.openapi.editor.event.EditorMouseListener;
import com.intellij.openapi.editor.event.SelectionEvent;
import com.intellij.openapi.editor.event.SelectionListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Alarm;
import com.intellij.util.messages.MessageBusConnection;
import org.jetbrains.annotations.NotNull;

/**
 * Tracks editor context (active file, selection) and notifies the frontend.
 * Handles auto-open file setting, .gitignore filtering, and debounced updates.
 */
public class EditorContextTracker {

    private static final Logger LOG = Logger.getInstance(EditorContextTracker.class);

    /**
     * Callback for sending context updates to the frontend.
     */
    public interface ContextCallback {
        void addSelectionInfo(String info);
        void clearSelectionInfo();
    }

    private final Project project;
    private final ContextCallback callback;
    private Alarm contextUpdateAlarm;
    private MessageBusConnection connection;
    private volatile boolean disposed = false;

    public EditorContextTracker(Project project, ContextCallback callback) {
        this.project = project;
        this.callback = callback;
    }

    /**
     * Register editor event listeners for file switching, caret moving, and text selection.
     */
    public void registerListeners() {
        contextUpdateAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD);
        connection = project.getMessageBus().connect();

        // Monitor file switching, opening, and closing
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener() {
            @Override
            public void selectionChanged(@NotNull FileEditorManagerEvent event) {
                scheduleContextUpdate();
            }

            @Override
            public void fileOpened(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
                scheduleContextUpdate();
            }

            @Override
            public void fileClosed(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
                scheduleContextUpdate();
            }
        });

        // Monitor text selection
        SelectionListener selectionListener = new SelectionListener() {
            @Override
            public void selectionChanged(@NotNull SelectionEvent e) {
                if (e.getEditor().getProject() == project) {
                    scheduleContextUpdate();
                }
            }
        };
        EditorFactory.getInstance().getEventMulticaster().addSelectionListener(selectionListener, connection);

        // Monitor caret movements (cursor clicks without text selection)
        CaretListener caretListener = new CaretListener() {
            @Override
            public void caretPositionChanged(@NotNull CaretEvent e) {
                if (e.getEditor().getProject() == project) {
                    scheduleContextUpdate();
                }
            }

            @Override
            public void caretAdded(@NotNull CaretEvent e) {
                if (e.getEditor().getProject() == project) {
                    scheduleContextUpdate();
                }
            }

            @Override
            public void caretRemoved(@NotNull CaretEvent e) {
                if (e.getEditor().getProject() == project) {
                    scheduleContextUpdate();
                }
            }
        };
        EditorFactory.getInstance().getEventMulticaster().addCaretListener(caretListener, connection);

        // Monitor editor mouse clicks (focusing/clicking an editor tab)
        EditorMouseListener mouseListener = new EditorMouseListener() {
            @Override
            public void mousePressed(@NotNull EditorMouseEvent event) {
                if (event.getEditor().getProject() == project) {
                    scheduleContextUpdate();
                }
            }

            @Override
            public void mouseClicked(@NotNull EditorMouseEvent event) {
                if (event.getEditor().getProject() == project) {
                    scheduleContextUpdate();
                }
            }
        };
        EditorFactory.getInstance().getEventMulticaster().addEditorMouseListener(mouseListener, connection);

        // Initial context probe
        scheduleContextUpdate();
    }

    public void scheduleContextUpdate() {
        if (disposed || contextUpdateAlarm == null) { return; }
        contextUpdateAlarm.cancelAllRequests();
        contextUpdateAlarm.addRequest(this::updateContextInfo, 200);
    }

    private void updateContextInfo() {
        if (disposed) { return; }

        // Ensure we are on EDT (Alarm.ThreadToUse.SWING_THREAD guarantees this, but being safe)
        ApplicationManager.getApplication().invokeLater(() -> {
            if (disposed) { return; }

            // Check if auto-open file is enabled
            try {
                String projectPath = project.getBasePath();
                if (projectPath != null) {
                    OpenCodeBuddySettingsService settingsService =
                        new OpenCodeBuddySettingsService();
                    boolean autoOpenFileEnabled = settingsService.getAutoOpenFileEnabled(projectPath);
                    if (!autoOpenFileEnabled) {
                        // If auto-open file is disabled, clear the ContextBar display
                        callback.clearSelectionInfo();
                        return;
                    }
                }
            } catch (Exception e) {
                LOG.warn("Failed to check autoOpenFileEnabled: " + e.getMessage());
            }

            try {
                FileEditorManager editorManager = FileEditorManager.getInstance(project);
                Editor editor = editorManager.getSelectedTextEditor();

                // Get cached .gitignore matcher for filtering sensitive files
                IgnoreRuleMatcher gitIgnoreMatcher = IgnoreRuleMatcher.forProjectSafe(project.getBasePath());

                String selectionInfo = null;

                if (editor != null) {
                    VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
                    if (file != null) {
                        String path = file.getPath();

                        // Filter out .gitignore'd files to prevent sensitive files from being auto-opened
                        if (gitIgnoreMatcher != null && gitIgnoreMatcher.isFileIgnored(path)) {
                            callback.clearSelectionInfo();
                            return;
                        }

                        selectionInfo = "@" + path;

                        com.intellij.openapi.editor.SelectionModel selectionModel = editor.getSelectionModel();
                        if (selectionModel.hasSelection()) {
                            int startLine = editor.getDocument().getLineNumber(selectionModel.getSelectionStart()) + 1;
                            int endLine = editor.getDocument().getLineNumber(selectionModel.getSelectionEnd()) + 1;

                            if (endLine > startLine && editor.offsetToLogicalPosition(selectionModel.getSelectionEnd()).column == 0) {
                                endLine--;
                            }
                            selectionInfo += "#L" + startLine + "-" + endLine;
                        }
                    }
                } else {
                     VirtualFile[] files = editorManager.getSelectedFiles();
                     if (files.length > 0) {
                         String path = files[0].getPath();

                         // Filter out .gitignore'd files
                         if (gitIgnoreMatcher != null && gitIgnoreMatcher.isFileIgnored(path)) {
                             callback.clearSelectionInfo();
                             return;
                         }

                         selectionInfo = "@" + path;
                     }
                }

                if (selectionInfo != null) {
                    callback.addSelectionInfo(JsUtils.escapeJs(selectionInfo));
                } else {
                    // When no file is open, clear the frontend display
                    callback.clearSelectionInfo();
                }
            } catch (Exception e) {
                LOG.warn("Failed to update context info: " + e.getMessage());
            }
        });
    }

    /**
     * Dispose alarm and message bus connection.
     */
    public void dispose() {
        disposed = true;
        if (connection != null) {
            connection.disconnect();
        }
        if (contextUpdateAlarm != null) {
            contextUpdateAlarm.dispose();
        }
    }
}
