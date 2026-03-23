package com.anthropic.claude.intellij.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;

/**
 * Toggles focus between the editor and the Claude chat input.
 * <p>
 * If focus is currently inside the Claude tool window, focus is returned
 * to the active editor. Otherwise, the Claude tool window is activated
 * and its first focusable component (the chat input field) receives focus.
 */
public class FocusToggleAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }

        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Claude");
        if (toolWindow == null) {
            return;
        }

        // Determine whether focus is currently inside the Claude tool window
        Component focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        boolean focusInClaude = false;

        if (focusOwner != null && toolWindow.getComponent() != null) {
            focusInClaude = SwingUtilities.isDescendingFrom(focusOwner, toolWindow.getComponent());
        }

        if (focusInClaude) {
            // Move focus back to the active editor
            FileEditorManager editorManager = FileEditorManager.getInstance(project);
            if (editorManager.getSelectedTextEditor() != null) {
                editorManager.getSelectedTextEditor().getContentComponent().requestFocusInWindow();
            }
        } else {
            // Activate the Claude tool window and focus the chat input
            toolWindow.activate(() -> {
                JComponent content = toolWindow.getComponent();
                if (content != null) {
                    focusChatInput(content);
                }
            });
        }
    }

    /**
     * Recursively searches for the first focusable text input component
     * inside the Claude tool window content and requests focus on it.
     */
    private void focusChatInput(Container container) {
        for (Component child : container.getComponents()) {
            if (child instanceof JTextArea || child instanceof JTextField) {
                child.requestFocusInWindow();
                return;
            }
            if (child instanceof Container) {
                focusChatInput((Container) child);
                // Check if we successfully focused something
                Component focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                if (focusOwner != null && SwingUtilities.isDescendingFrom(focusOwner, container)) {
                    return;
                }
            }
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }
}
