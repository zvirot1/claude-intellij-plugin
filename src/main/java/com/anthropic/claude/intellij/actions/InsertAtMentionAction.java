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

import javax.swing.*;
import java.awt.*;

/**
 * Inserts an @-mention of the current file (with optional line range from the
 * selection) into the Claude chat input field.
 * <p>
 * Format examples:
 * <ul>
 *     <li>{@code @MyFile.java} &mdash; no selection</li>
 *     <li>{@code @MyFile.java:10-25} &mdash; lines 10 through 25 selected</li>
 * </ul>
 */
public class InsertAtMentionAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }

        Editor editor = e.getData(CommonDataKeys.EDITOR);
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (file == null) {
            return;
        }

        // Build the @-mention string
        StringBuilder mention = new StringBuilder("@").append(file.getName());

        if (editor != null && editor.getSelectionModel().hasSelection()) {
            int startLine = editor.getDocument().getLineNumber(editor.getSelectionModel().getSelectionStart()) + 1;
            int endLine = editor.getDocument().getLineNumber(editor.getSelectionModel().getSelectionEnd()) + 1;
            if (startLine == endLine) {
                mention.append(":").append(startLine);
            } else {
                mention.append(":").append(startLine).append("-").append(endLine);
            }
        }

        String mentionText = mention.toString();

        // Activate the Claude tool window and insert the mention into the chat input
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Claude");
        if (toolWindow == null) {
            return;
        }

        toolWindow.activate(() -> {
            JComponent content = toolWindow.getComponent();
            if (content != null) {
                insertIntoFirstTextInput(content, mentionText);
            }
        });
    }

    /**
     * Recursively finds the first text input component (JTextArea or JTextField)
     * in the container hierarchy and appends the mention text to it.
     */
    private boolean insertIntoFirstTextInput(Container container, String text) {
        for (Component child : container.getComponents()) {
            if (child instanceof JTextArea) {
                JTextArea textArea = (JTextArea) child;
                int caretPos = textArea.getCaretPosition();
                String existing = textArea.getText();
                // Insert a space before the mention if there is existing text that doesn't end with whitespace
                String prefix = existing.isEmpty() || Character.isWhitespace(existing.charAt(existing.length() - 1)) ? "" : " ";
                textArea.setText(existing + prefix + text + " ");
                textArea.requestFocusInWindow();
                return true;
            }
            if (child instanceof JTextField) {
                JTextField textField = (JTextField) child;
                String existing = textField.getText();
                String prefix = existing.isEmpty() || Character.isWhitespace(existing.charAt(existing.length() - 1)) ? "" : " ";
                textField.setText(existing + prefix + text + " ");
                textField.requestFocusInWindow();
                return true;
            }
            if (child instanceof Container) {
                if (insertIntoFirstTextInput((Container) child, text)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        // Enabled whenever a file is open, regardless of selection
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        e.getPresentation().setEnabledAndVisible(file != null && e.getProject() != null);
    }
}
