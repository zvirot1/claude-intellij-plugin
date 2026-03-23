package com.anthropic.claude.intellij.actions;

import com.anthropic.claude.intellij.service.ClaudeProjectService;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;

/**
 * Action that starts a new Claude conversation session.
 * Stops the current CLI process, clears the conversation model,
 * and re-initializes the service so the next message starts fresh.
 */
public class NewSessionAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }

        ClaudeProjectService service = ClaudeProjectService.getInstance(project);

        // Stop the current CLI process
        service.getCliManager().stop();

        // Clear the conversation model so the UI resets
        service.getConversationModel().clear();

        // Re-initialize the service so it is ready for a new session
        service.initialize();

        // Ensure the tool window is visible
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Claude");
        if (toolWindow != null) {
            toolWindow.activate(null);
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }
}
