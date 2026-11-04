package com.opencodebuddy.util;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Editor context information collector.
 *
 * This utility collects the user's current working environment in the IDEA editor.
 * The gathered information is sent to the AI as context, helping it better understand
 * the user's intent and the current code environment.
 *
 * The collected context includes:
 * 1. The currently active (viewed) file - serves as the AI's primary focus when answering questions
 * 2. The user's selected code snippet - the core subject of the user's question
 * 3. The list of all open files - potential contextual references
 *
 * This information is built into JSON format and appended to the system prompt sent to the AI:
 * {
 *   "active": "path/to/active/file",              // Highest priority, AI's primary focus
 *   "selection": {                                 // If code is selected, the AI should analyze this first
 *     "startLine": startLineNumber,
 *     "endLine": endLineNumber
 *   },
 *   "others": ["path/to/other/file1", "path/to/other/file2"]  // Potentially related context, lowest priority
 * }
 */
public class EditorFileUtils {

    private static final Logger LOG = Logger.getInstance(EditorFileUtils.class);

    /**
     * Get all open file paths in the current project.
     *
     * Collects all files the user has open in IDEA, which may be related to their current task.
     * The returned file list is sent to the AI as potential context references, helping it understand:
     * - Which related files the user might be working on
     * - The user's working scope and the code modules they are focused on
     * - Cross-file code relationships and dependencies
     *
     * Note: This list includes all open files, but the active file (the one currently being viewed)
     *       is marked separately since it is typically the primary subject of the user's question.
     *
     * @param project the IDEA project
     * @return list of open file paths (absolute paths)
     */
    public static List<String> getOpenedFiles(Project project) {
        List<String> openedFiles = new ArrayList<>();

        if (project == null) {
            return openedFiles;
        }

        try {
            // Get the file editor manager
            FileEditorManager fileEditorManager = FileEditorManager.getInstance(project);

            // Get all open files
            VirtualFile[] openFiles = fileEditorManager.getOpenFiles();

            // Extract file paths
            for (VirtualFile file : openFiles) {
                if (file != null && file.getPath() != null) {
                    openedFiles.add(file.getPath());
                }
            }
        } catch (Exception e) {
            // Catch exceptions to avoid disrupting the main flow
            LOG.error("[EditorFileUtils] Error getting opened files: " + e.getMessage());
        }

        return openedFiles;
    }

    /**
     * Asynchronously refresh and find a virtual file.
     * This method prevents deadlocks by performing refresh operations outside of read locks.
     *
     * @param file      the file to refresh and find
     * @param onSuccess callback invoked on UI thread with the VirtualFile if found
     * @param onFailure callback invoked on UI thread if file cannot be found (optional)
     */
    public static void refreshAndFindFileAsync(File file, Consumer<VirtualFile> onSuccess, Runnable onFailure) {
        try {
            final String canonicalPath = file.getCanonicalPath();

            // Step 1: Refresh file system on UI thread (not under read lock)
            ApplicationManager.getApplication().invokeLater(() -> {
                try {
                    // Async refresh - this doesn't block and doesn't require read lock
                    LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file);

                    // Step 2: Find the file in a non-blocking read action
                    ReadAction
                            .nonBlocking(() -> {
                                // Only perform find operations, no refresh
                                VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(canonicalPath.replace('\\', '/'));
                                if (vf == null) {
                                    // Fallback to finding by File object
                                    vf = LocalFileSystem.getInstance().findFileByIoFile(file);
                                }
                                return vf;
                            })
                            .finishOnUiThread(ModalityState.nonModal(), virtualFile -> {
                                // Step 3: Handle the result on UI thread
                                if (virtualFile == null) {
                                    LOG.warn("Could not find virtual file: " + file.getAbsolutePath() + ", retrying with sync refresh...");
                                    // Retry: sync refresh and find (already on UI thread, not under read lock)
                                    VirtualFile retryVf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file);
                                    if (retryVf != null) {
                                        onSuccess.accept(retryVf);
                                    } else {
                                        LOG.error("Failed to find virtual file after retry: " + file.getAbsolutePath());
                                        if (onFailure != null) {
                                            onFailure.run();
                                        }
                                    }
                                    return;
                                }

                                onSuccess.accept(virtualFile);
                            })
                            .submit(AppExecutorUtil.getAppExecutorService());
                } catch (Exception e) {
                    LOG.error("Failed to refresh file system: " + file.getAbsolutePath(), e);
                    if (onFailure != null) {
                        onFailure.run();
                    }
                }
            }, ModalityState.nonModal());

        } catch (Exception e) {
            LOG.error("Failed to get canonical path: " + file.getAbsolutePath(), e);
            if (onFailure != null) {
                ApplicationManager.getApplication().invokeLater(onFailure, ModalityState.nonModal());
            }
        }
    }

    /**
     * Gets the currently selected editor file path.
     * Shared utility to avoid duplication across handlers and lifecycle managers.
     *
     * @param project the IDEA project
     * @return selected file path, or null if no active file
     */
    public static String getCurrentEditorFilePath(Project project) {
        if (project == null || project.isDisposed()) {
            return null;
        }
        try {
            return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
                VirtualFile[] files = FileEditorManager.getInstance(project).getSelectedFiles();
                if (files.length == 0 || files[0] == null) {
                    return null;
                }
                return files[0].getPath();
            });
        } catch (Exception e) {
            LOG.debug("Failed to resolve current editor file path", e);
            return null;
        }
    }

    /**
     * Synchronously refresh and find a virtual file on UI thread.
     * WARNING: This should only be called when already on UI thread and not under read lock.
     *
     * @param file the file to refresh and find
     * @return the VirtualFile, or null if not found
     */
    public static VirtualFile refreshAndFindFileSync(File file) {
        try {
            String canonicalPath = file.getCanonicalPath();
            VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(canonicalPath.replace('\\', '/'));
            if (vf == null) {
                vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file);
            }
            return vf;
        } catch (Exception e) {
            LOG.error("Failed to refresh and find file: " + file.getAbsolutePath(), e);
            return null;
        }
    }
}
