package com.anthropic.claude.intellij.actions;

import com.anthropic.claude.intellij.service.ClaudeProjectService;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;

/**
 * Action that reads the currently open file and sends it to Claude
 * with an "Analyze this file" prompt. Equivalent to Eclipse's RunCLIOnFileHandler.
 */
public class AnalyzeFileAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (file == null || file.isDirectory()) return;

        // Read file content
        String content;
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (editor != null) {
            content = editor.getDocument().getText();
        } else {
            try {
                content = new String(file.contentsToByteArray(), StandardCharsets.UTF_8);
            } catch (Exception ex) {
                return;
            }
        }

        // Truncate very large files
        if (content.length() > 100_000) {
            content = content.substring(0, 100_000) + "\n... (truncated)";
        }

        String fileName = file.getName();
        String message = "Analyze this file:\n\n<file path=\"" + fileName + "\">\n" + content + "\n</file>";

        // Ensure the tool window is open
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Claude");
        if (toolWindow != null && !toolWindow.isVisible()) {
            toolWindow.activate(null);
        }

        // Send to Claude
        ClaudeProjectService service = ClaudeProjectService.getInstance(project);
        service.getConversationModel().addUserMessage(message);
        service.getCliManager().sendMessage(message);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        boolean enabled = e.getProject() != null
            && file != null
            && !file.isDirectory()
            && !file.getFileType().isBinary();
        e.getPresentation().setEnabledAndVisible(enabled);
    }
}
