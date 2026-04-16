package com.anthropic.claude.intellij.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public class ClaudeToolWindowFactory implements ToolWindowFactory, com.intellij.openapi.startup.StartupActivity {

    @Override
    public boolean shouldBeAvailable(@NotNull Project project) {
        return true;
    }

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        ClaudeChatPanel chatPanel = new ClaudeChatPanel(project);
        Content content = ContentFactory.getInstance().createContent(chatPanel.getComponent(), "Chat", true);
        content.setCloseable(true);
        content.setDisposer(chatPanel);
        toolWindow.getContentManager().addContent(content);
    }

    /**
     * Called after the project is fully opened — auto-show the Claude tool window.
     */
    @Override
    public void runActivity(@NotNull Project project) {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
            ToolWindow tw = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("Claude");
            if (tw != null && !tw.isVisible()) {
                tw.show();
            }
        });
    }
}
