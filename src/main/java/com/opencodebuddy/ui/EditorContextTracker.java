package com.opencodebuddy.ui;

import com.opencodebuddy.session.EditorContextService;
import com.opencodebuddy.session.EditorContextSnapshot;
import com.opencodebuddy.util.IgnoreRuleMatcher;
import com.opencodebuddy.util.JsUtils;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.SelectionModel;
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

            try {
                FileEditorManager editorManager = FileEditorManager.getInstance(project);
                Editor editor = editorManager.getSelectedTextEditor();

                // Get cached .gitignore matcher for filtering sensitive files
                IgnoreRuleMatcher gitIgnoreMatcher = IgnoreRuleMatcher.forProjectSafe(project.getBasePath());

                EditorContextSnapshot snapshot = resolveSnapshot(editorManager, editor, gitIgnoreMatcher);

                // Publish before notifying the frontend: the chip and the prompt
                // are both projections of this one value.
                EditorContextService contextService = EditorContextService.getInstance(project);
                if (contextService != null) {
                    contextService.update(snapshot);
                }

                if (snapshot.hasFile()) {
                    callback.addSelectionInfo(JsUtils.escapeJs(formatInfo(snapshot)));
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
     * Resolve the single editor-context value. The .gitignore filter and the
     * end-of-line rule are applied here only; every reader consumes the result
     * instead of re-deriving it.
     */
    private EditorContextSnapshot resolveSnapshot(
            FileEditorManager editorManager,
            Editor editor,
            IgnoreRuleMatcher gitIgnoreMatcher
    ) {
        if (editor != null) {
            VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
            if (file == null || isIgnored(gitIgnoreMatcher, file.getPath())) {
                return EditorContextSnapshot.EMPTY;
            }
            return readSelection(editor, file.getPath());
        }

        VirtualFile[] files = editorManager.getSelectedFiles();
        if (files.length == 0 || files[0] == null || isIgnored(gitIgnoreMatcher, files[0].getPath())) {
            return EditorContextSnapshot.EMPTY;
        }
        return EditorContextSnapshot.file(files[0].getPath());
    }

    private static boolean isIgnored(IgnoreRuleMatcher matcher, String path) {
        return matcher != null && path != null && matcher.isFileIgnored(path);
    }

    private static EditorContextSnapshot readSelection(Editor editor, String file) {
        SelectionModel selectionModel = editor.getSelectionModel();
        if (!selectionModel.hasSelection()) {
            return EditorContextSnapshot.file(file);
        }

        int startLine = editor.getDocument().getLineNumber(selectionModel.getSelectionStart()) + 1;
        int endLine = editor.getDocument().getLineNumber(selectionModel.getSelectionEnd()) + 1;

        // A selection that ends at column 0 does not cover that line.
        if (endLine > startLine
                && editor.offsetToLogicalPosition(selectionModel.getSelectionEnd()).column == 0) {
            endLine--;
        }
        return EditorContextSnapshot.selection(file, startLine, endLine);
    }

    private static String formatInfo(EditorContextSnapshot snapshot) {
        StringBuilder info = new StringBuilder("@").append(snapshot.file());
        if (snapshot.hasSelection()) {
            info.append("#L").append(snapshot.startLine()).append("-").append(snapshot.endLine());
        }
        return info.toString();
    }

    public void refreshCurrentEditorContext() {
        if (disposed) {
            return;
        }
        updateContextInfo();
    }

    public void clearSelectionInfo() {
        if (disposed) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(callback::clearSelectionInfo);
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
