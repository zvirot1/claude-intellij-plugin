package com.anthropic.claude.intellij.statusbar;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetFactory;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

/**
 * Factory that creates {@link ClaudeStatusBarWidget} instances for each project.
 * Registered in {@code plugin.xml} as a {@code statusBarWidgetFactory}.
 */
public class ClaudeStatusBarFactory implements StatusBarWidgetFactory {

    @Override
    public @NotNull String getId() {
        return "ClaudeStatusBar";
    }

    @Override
    public @Nls @NotNull String getDisplayName() {
        return "Claude Code";
    }

    @Override
    public boolean isAvailable(@NotNull Project project) {
        return true;
    }

    @Override
    public @NotNull StatusBarWidget createWidget(@NotNull Project project) {
        return new ClaudeStatusBarWidget(project);
    }

    @Override
    public void disposeWidget(@NotNull StatusBarWidget widget) {
        widget.dispose();
    }

    @Override
    public boolean canBeEnabledOn(@NotNull StatusBar statusBar) {
        return true;
    }
}
