package com.opencodebuddy.action.vcs;

import com.opencodebuddy.i18n.OpenCodeBuddyBundle;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.ui.CommitMessage;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Generates a commit message from the staged diff using opencode.
 *
 * <p>Chain: build `git diff --staged` → one-shot opencode session via the
 * daemon (`opencode.send` with a conventional-commit prompt) → collect the
 * assistant reply from the marker stream → write it into the VCS commit
 * message field. The temp session is deleted afterwards.</p>
 */
public class GenerateCommitMessageAction extends com.intellij.openapi.actionSystem.AnAction {

    private static final Logger LOG = Logger.getInstance(GenerateCommitMessageAction.class);

    @Override
    public void actionPerformed(com.intellij.openapi.actionSystem.AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }
        CommitMessage commitMessage = getCommitMessagePanel(e);
        if (commitMessage == null) {
            return;
        }

        commitMessage.setCommitMessage(OpenCodeBuddyBundle.message("commit.generating"));
        CompletableFuture.supplyAsync(() -> {
                    try {
                        String diff = runGit(project, "diff", "--staged");
                        if (diff == null || diff.isBlank()) {
                            diff = runGit(project, "diff", "--cached");
                        }
                        return diff == null ? "" : diff;
                    } catch (Exception ex) {
                        LOG.warn("[CommitAI] git diff failed: " + ex.getMessage());
                        return "";
                    }
                })
                .thenCompose(diff -> generateViaOpencode(project, diff))
                .whenComplete((message, err) -> ApplicationManager.getApplication().invokeLater(() -> {
                    if (project.isDisposed()) {
                        return;
                    }
                    if (err != null || message == null || message.isBlank()) {
                        String reason = err != null ? String.valueOf(err.getMessage()) : "empty reply";
                        LOG.warn("[CommitAI] generation failed: " + reason);
                        commitMessage.setCommitMessage(OpenCodeBuddyBundle.message("commit.failed", reason));
                        return;
                    }
                    commitMessage.setCommitMessage(message.trim());
                }));
    }

    private CompletableFuture<String> generateViaOpencode(Project project, String diff) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                com.opencodebuddy.ui.toolwindow.ClaudeChatWindow window = firstChatWindow(project);
                if (window == null) {
                    return null;
                }
                com.opencodebuddy.provider.opencode.OpenCodeSDKBridge bridge = window.getOpenCodeSDKBridge();
                if (!bridge.ensureDaemonRunning()) {
                    return null;
                }
                String cwd = project.getBasePath();
                String prompt = "Write one conventional commit message (type(scope): subject) "
                        + "with an optional short body for the following staged diff. "
                        + "Reply with the commit message only, no commentary.\n\n"
                        + "```diff\n" + truncate(diff, 60_000) + "\n```";

                StringBuilder reply = new StringBuilder();
                java.util.concurrent.atomic.AtomicReference<String> sessionId =
                        new java.util.concurrent.atomic.AtomicReference<>(null);
                com.google.gson.JsonObject params = new com.google.gson.JsonObject();
                params.addProperty("message", prompt);
                params.addProperty("cwd", cwd != null ? cwd : "");
                params.addProperty("agent", "build");

                java.util.concurrent.CompletableFuture<Boolean> done = new java.util.concurrent.CompletableFuture<>();
                bridge.request("opencode.send", params, new com.opencodebuddy.provider.common.DaemonBridge.DaemonOutputCallback() {
                    @Override
                    public void onLine(String line) {
                        if (line.startsWith("[SESSION_ID]")) {
                            sessionId.set(line.substring("[SESSION_ID]".length()).trim());
                        } else if (line.startsWith("[CONTENT_DELTA]")) {
                            String payload = line.substring("[CONTENT_DELTA]".length()).trim();
                            try {
                                String delta = com.google.gson.JsonParser.parseString(payload).getAsString();
                                reply.append(delta);
                            } catch (Exception ignored) {
                            }
                        }
                    }

                    @Override
                    public void onStderr(String text) {
                    }

                    @Override
                    public void onError(String error) {
                        done.complete(false);
                    }

                    @Override
                    public void onComplete(boolean success) {
                        done.complete(success);
                    }
                });
                try {
                    done.get(120, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ex) {
                    LOG.warn("[CommitAI] wait failed: " + ex.getMessage());
                }

                String message = reply.toString().trim();
                String sid = sessionId.get();
                if (sid != null && !sid.isEmpty()) {
                    cleanupSession(bridge, sid, cwd);
                }
                return message.isEmpty() ? null : message;
            } catch (Exception ex) {
                LOG.warn("[CommitAI] opencode generation failed: " + ex.getMessage());
                return null;
            }
        }, AppExecutorUtil.getAppExecutorService());
    }

    private void cleanupSession(com.opencodebuddy.provider.opencode.OpenCodeSDKBridge bridge,
                                String sessionId, String cwd) {
        try {
            com.google.gson.JsonObject params = new com.google.gson.JsonObject();
            params.addProperty("sessionId", sessionId);
            if (cwd != null) {
                params.addProperty("cwd", cwd);
            }
            bridge.request("opencode.deleteSession", params,
                    new com.opencodebuddy.provider.common.DaemonBridge.DaemonOutputCallback() {
                @Override public void onLine(String line) { }
                @Override public void onStderr(String text) { }
                @Override public void onError(String error) { }
                @Override public void onComplete(boolean success) { }
            });
        } catch (Exception ignored) {
        }
    }

    private com.opencodebuddy.ui.toolwindow.ClaudeChatWindow firstChatWindow(Project project) {
        java.util.Set<com.opencodebuddy.ui.toolwindow.ClaudeChatWindow> windows =
                com.opencodebuddy.ui.toolwindow.ClaudeSDKToolWindow.getAllChatWindowsForProject(project);
        return windows.isEmpty() ? null : windows.iterator().next();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "\n... (truncated)";
    }

    private CommitMessage getCommitMessagePanel(com.intellij.openapi.actionSystem.AnActionEvent e) {
        return (CommitMessage) e.getDataContext().getData("CommitMessage");
    }

    private String runGit(Project project, String... args)
            throws VcsException, com.intellij.execution.ExecutionException, InterruptedException {
        List<String> cmd = new java.util.ArrayList<>();
        cmd.add("git");
        cmd.addAll(java.util.Arrays.asList(args));
        com.intellij.execution.process.ProcessOutput output =
                com.intellij.execution.util.ExecUtil.execAndGetOutput(
                        new com.intellij.execution.configurations.GeneralCommandLine(cmd)
                                .withWorkDirectory(project.getBasePath())
                                .withCharset(java.nio.charset.StandardCharsets.UTF_8));
        return output.getStdout();
    }

    @Override
    public void update(com.intellij.openapi.actionSystem.AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }
}
