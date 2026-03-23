package com.anthropic.claude.intellij.actions;

import com.anthropic.claude.intellij.service.ClaudeProjectService;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;

/**
 * Action that sends the selected code to Claude with a request to explain it.
 */
public class ExplainCodeAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (editor == null) {
            return;
        }

        String selectedText = editor.getSelectionModel().getSelectedText();
        if (selectedText == null || selectedText.isEmpty()) {
            return;
        }

        Project project = e.getProject();
        if (project == null) {
            return;
        }

        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        String fileName = file != null ? file.getName() : "unknown";

        String message = "Explain this code:\n\n```\n" + selectedText + "\n```";

        // Ensure the tool window is open
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Claude");
        if (toolWindow != null && !toolWindow.isVisible()) {
            toolWindow.activate(null);
        }

        ClaudeProjectService service = ClaudeProjectService.getInstance(project);
        service.getConversationModel().addUserMessage(message);
        service.getCliManager().sendMessage(message);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        boolean hasSelection = editor != null
                && editor.getSelectionModel().getSelectedText() != null
                && !editor.getSelectionModel().getSelectedText().isEmpty();
        e.getPresentation().setEnabledAndVisible(hasSelection && e.getProject() != null);
    }
}
