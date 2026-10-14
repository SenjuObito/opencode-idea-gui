package com.opencodebuddy.action.tab;

import com.opencodebuddy.i18n.OpenCodeBuddyBundle;
import com.opencodebuddy.ui.toolwindow.OpencodeBuddyToolWindow;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Action to create a new chat tab in the OpenCode Buddy tool window.
 */
public class CreateNewTabAction extends AnAction {

    private static final Logger LOG = Logger.getInstance(CreateNewTabAction.class);

    public CreateNewTabAction() {
        super(
            OpenCodeBuddyBundle.message("action.createNewTab.text"),
            OpenCodeBuddyBundle.message("action.createNewTab.description"),
            com.intellij.icons.AllIcons.General.Add
        );
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            LOG.error("[CreateNewTabAction] Project is null");
            return;
        }

        OpencodeBuddyToolWindow.createNewTab(project);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabled(e.getProject() != null);
    }
}
