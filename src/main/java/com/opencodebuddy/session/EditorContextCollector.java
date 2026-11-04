package com.opencodebuddy.session;

import com.opencodebuddy.handler.context.ContextCollector;
import com.opencodebuddy.handler.context.WorkspaceContextCollector;
import com.opencodebuddy.util.EditorFileUtils;
import com.opencodebuddy.util.IgnoreRuleMatcher;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Editor context collector.
 * Collects context information from the IDE editor (open files, selected code, etc.).
 * Also collects workspace/multi-project information when the workspace plugin is available.
 */
public class EditorContextCollector {
    private static final Logger LOG = Logger.getInstance(EditorContextCollector.class);

    private final Project project;
    private boolean psiContextEnabled = true;
    private boolean autoOpenFileEnabled = true;
    private boolean workspaceContextEnabled = true;

    public EditorContextCollector(Project project) {
        this.project = project;
    }

    public void setPsiContextEnabled(boolean enabled) {
        this.psiContextEnabled = enabled;
    }

    /**
     * Set whether auto open file (editor context collection) is enabled.
     * When disabled, collectContext() returns an empty object and AI receives no editor context.
     */
    public void setAutoOpenFileEnabled(boolean enabled) {
        this.autoOpenFileEnabled = enabled;
    }

    /**
     * Set whether workspace context collection is enabled.
     * When enabled, multi-project workspace information is collected and added to context.
     */
    public void setWorkspaceContextEnabled(boolean enabled) {
        this.workspaceContextEnabled = enabled;
    }

    /**
     * Asynchronously collect editor context information.
     * Returns an empty object if autoOpenFileEnabled is false.
     */
    public CompletableFuture<JsonObject> collectContext() {
        CompletableFuture<JsonObject> future = new CompletableFuture<>();

        // If the user has disabled the auto open file toggle, skip all editor context collection
        if (!autoOpenFileEnabled) {
            LOG.info("Auto open file disabled, skipping editor context collection");
            future.complete(new JsonObject());
            return future;
        }

        // Read the editor and the published snapshot on EDT, then build the
        // context in a background read action. The snapshot is the single
        // source: it is the same value the context chip shows.
        AtomicReference<Editor> editorRef = new AtomicReference<>();
        AtomicReference<EditorContextSnapshot> snapshotRef = new AtomicReference<>(EditorContextSnapshot.EMPTY);

        Runnable fetchEditor = () -> {
            try {
                editorRef.set(FileEditorManager.getInstance(project).getSelectedTextEditor());
                EditorContextService contextService = EditorContextService.getInstance(project);
                if (contextService != null) {
                    snapshotRef.set(contextService.getSnapshot());
                }
            } catch (Exception e) {
                LOG.warn("Failed to get editor on EDT: " + e.getMessage());
            }
        };

        if (ApplicationManager.getApplication().isDispatchThread()) {
            fetchEditor.run();
        } else {
            ApplicationManager.getApplication().invokeAndWait(fetchEditor);
        }

        // Then collect context in background read action
        ReadAction
            .nonBlocking(() -> {
                try {
                    return buildContextJson(editorRef.get(), snapshotRef.get());
                } catch (Exception e) {
                    LOG.warn("Failed to get file info: " + e.getMessage());
                    return new JsonObject();
                }
            })
            .finishOnUiThread(com.intellij.openapi.application.ModalityState.defaultModalityState(), future::complete)
            .submit(AppExecutorUtil.getAppExecutorService());

        return future;
    }

    /**
     * Build the context JSON object from the published editor snapshot.
     */
    private JsonObject buildContextJson(Editor editor, EditorContextSnapshot snapshot) {
        /*
         * ========== Editor Context Information Collection ==========
         *
         * Collects the user's working environment in the IDEA editor to help AI
         * understand the current code context. This information is built into JSON
         * format and appended to the system prompt sent to AI.
         *
         * Collected information is organized in four priority layers:
         * 1. active (currently active file) - Highest priority, AI's primary focus
         * 2. selection (user-selected code) - If present, the core target for AI analysis
         * 3. semantic (PSI semantic info) - Code structure, references, inheritance, etc.
         * 4. others (other open files) - Lowest priority, potential context references
         */

        String activeFile = snapshot != null && snapshot.hasFile() ? snapshot.file() : null;
        List<String> allOpenedFiles = EditorFileUtils.getOpenedFiles(project);

        // Get cached .gitignore matcher for filtering sensitive files
        IgnoreRuleMatcher gitIgnoreMatcher = IgnoreRuleMatcher.forProjectSafe(project.getBasePath());

        JsonObject openedFilesJson = new JsonObject();

        if (activeFile != null) {
            // Add the currently active file path
            openedFilesJson.addProperty("active", activeFile);
            LOG.debug("Current active file: " + activeFile);

            // If the user has selected code, add the line range. The selected
            // text itself is never inlined: the agent reads the file.
            if (snapshot.hasSelection()) {
                JsonObject selectionJson = new JsonObject();
                selectionJson.addProperty("startLine", snapshot.startLine());
                selectionJson.addProperty("endLine", snapshot.endLine());
                openedFilesJson.add("selection", selectionJson);
                LOG.debug("Code selection detected: lines "
                        + snapshot.startLine() + "-" + snapshot.endLine());
            }

            // Collect PSI semantic context (all files)
            if (psiContextEnabled && editor != null) {
                try {
                    ContextCollector semanticCollector = new ContextCollector();
                    JsonObject semanticContext = semanticCollector.collectSemanticContext(editor, project);
                    if (semanticContext != null && semanticContext.size() > 0) {
                        // Merge semantic context properties directly into main object
                        for (String key : semanticContext.keySet()) {
                            openedFilesJson.add(key, semanticContext.get(key));
                        }
                        LOG.debug("PSI semantic context merged for: " + activeFile);
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to collect PSI semantic context: " + e.getMessage());
                }
            }
        }

        // Add other open files (excluding the active file and .gitignore'd files)
        JsonArray othersArray = new JsonArray();
        for (String file : allOpenedFiles) {
            if (!file.equals(activeFile)
                    && (gitIgnoreMatcher == null || !gitIgnoreMatcher.isFileIgnored(file))) {
                othersArray.add(file);
            }
        }
        if (othersArray.size() > 0) {
            openedFilesJson.add("others", othersArray);
            LOG.debug("Other opened files count: " + othersArray.size());
        }

        // Collect workspace/multi-project context
        if (workspaceContextEnabled) {
            try {
                JsonObject workspaceContext = WorkspaceContextCollector.collectWorkspaceContext(project);
                if (workspaceContext != null && workspaceContext.size() > 0) {
                    // Merge workspace context into main object
                    for (String key : workspaceContext.keySet()) {
                        openedFilesJson.add(key, workspaceContext.get(key));
                    }
                    LOG.debug("Workspace context merged: isWorkspace="
                        + (workspaceContext.has("isWorkspace")
                            ? workspaceContext.get("isWorkspace").getAsBoolean() : false));

                    // Determine which subproject the active file belongs to
                    if (activeFile != null && workspaceContext.has("isWorkspace")
                            && workspaceContext.get("isWorkspace").getAsBoolean()) {
                        String subproject = WorkspaceContextCollector.getSubprojectForFile(project, activeFile);
                        if (subproject != null) {
                            openedFilesJson.addProperty("activeSubproject", subproject);
                            LOG.debug("Active file belongs to subproject: " + subproject);
                        }
                    }
                }
            } catch (Exception e) {
                LOG.warn("Failed to collect workspace context: " + e.getMessage());
            }
        }

        return openedFilesJson;
    }

}
