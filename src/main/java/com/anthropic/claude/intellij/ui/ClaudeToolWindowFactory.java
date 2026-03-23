package com.anthropic.claude.intellij.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public class ClaudeToolWindowFactory implements ToolWindowFactory {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        ClaudeChatPanel chatPanel = new ClaudeChatPanel(project);
        Content content = ContentFactory.getInstance().createContent(chatPanel.getComponent(), "", false);
        toolWindow.getContentManager().addContent(content);
    }
}
