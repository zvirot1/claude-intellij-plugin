package com.anthropic.claude.intellij.statusbar;

import com.anthropic.claude.intellij.cli.ClaudeCliManager;
import com.anthropic.claude.intellij.cli.ICliStateListener;
import com.anthropic.claude.intellij.model.IConversationListener;
import com.anthropic.claude.intellij.model.MessageBlock;
import com.anthropic.claude.intellij.model.SessionInfo;
import com.anthropic.claude.intellij.service.ClaudeProjectService;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.intellij.openapi.diagnostic.Logger;

import javax.swing.*;
import java.awt.event.MouseEvent;

/**
 * Status bar widget that displays the current Claude CLI state.
 * <p>
 * Uses colored Unicode dots to indicate state:
 * <ul>
 *     <li>🟢 Ready — CLI process is running and idle</li>
 *     <li>🔵 Thinking — CLI is processing a request</li>
 *     <li>⚪ Disconnected — CLI process is not running</li>
 *     <li>🔴 Error — an error occurred with the CLI process</li>
 * </ul>
 * Also shows the current model name when available.
 * Clicking the widget opens the Claude tool window.
 */
public class ClaudeStatusBarWidget implements StatusBarWidget, StatusBarWidget.TextPresentation {

    private static final Logger LOG = Logger.getInstance(ClaudeStatusBarWidget.class);
    private static final String WIDGET_ID = "ClaudeStatusBar";

    private final Project project;
    private StatusBar statusBar;
    private volatile CliState cliState = CliState.DISCONNECTED;
    private volatile String modelName = null;
    private final ICliStateListener stateListener;
    private final IConversationListener conversationListener;

    private enum CliState {
        READY("\uD83D\uDFE2", "Ready"),        // 🟢
        THINKING("\uD83D\uDD35", "Thinking"),   // 🔵
        DISCONNECTED("\u26AA", "Disconnected"),  // ⚪
        ERROR("\uD83D\uDD34", "Error");          // 🔴

        private final String dot;
        private final String label;

        CliState(String dot, String label) {
            this.dot = dot;
            this.label = label;
        }
    }

    public ClaudeStatusBarWidget(@NotNull Project project) {
        this.project = project;

        this.stateListener = new ICliStateListener() {
            @Override
            public void onStateChanged(ClaudeCliManager.ProcessState oldState,
                                       ClaudeCliManager.ProcessState newState) {
                switch (newState) {
                    case RUNNING:
                        updateState(CliState.READY);
                        break;
                    case ERROR:
                        updateState(CliState.ERROR);
                        break;
                    case STOPPED:
                    case NOT_STARTED:
                        updateState(CliState.DISCONNECTED);
                        modelName = null;
                        break;
                    default:
                        break;
                }
            }
        };

        // Listen for session init (to get model name) and thinking state
        this.conversationListener = new IConversationListener() {
            @Override
            public void onSessionInitialized(SessionInfo info) {
                if (info != null && info.getModel() != null) {
                    modelName = info.getModel();
                    refreshWidget();
                }
            }

            @Override
            public void onAssistantMessageStarted(MessageBlock block) {
                updateState(CliState.THINKING);
            }

            @Override
            public void onAssistantMessageCompleted(MessageBlock block) {
                // Don't switch to READY here — multi-turn conversations have
                // multiple assistant messages. Wait for onResultReceived instead.
            }

            @Override
            public void onResultReceived(com.anthropic.claude.intellij.model.UsageInfo usage) {
                updateState(CliState.READY);
            }

            @Override
            public void onConversationCleared() {
                modelName = null;
                updateState(CliState.DISCONNECTED);
            }
        };

        // Register listeners
        ClaudeProjectService service = ClaudeProjectService.getInstance(project);
        service.getCliManager().addStateListener(stateListener);
        service.getConversationModel().addListener(conversationListener);

        // Set initial state based on current CLI status
        if (service.getCliManager().isRunning()) {
            cliState = service.getCliManager().isBusy() ? CliState.THINKING : CliState.READY;
        }
    }

    @Override
    public @NotNull String ID() {
        return WIDGET_ID;
    }

    @Override
    public void install(@NotNull StatusBar statusBar) {
        this.statusBar = statusBar;
    }

    @Override
    public void dispose() {
        if (!project.isDisposed()) {
            ClaudeProjectService service = ClaudeProjectService.getInstance(project);
            service.getCliManager().removeStateListener(stateListener);
            service.getConversationModel().removeListener(conversationListener);
        }
    }

    // --- TextPresentation ---

    @Override
    public @NotNull String getText() {
        StringBuilder text = new StringBuilder();
        text.append(cliState.dot).append(" Claude: ").append(cliState.label);
        if (modelName != null && !modelName.isEmpty()) {
            text.append(" (").append(shortenModelName(modelName)).append(")");
        }
        return text.toString();
    }

    @Override
    public float getAlignment() {
        return SwingConstants.CENTER;
    }

    @Override
    public @Nullable String getTooltipText() {
        String tooltip = "Claude Code - " + cliState.label;
        if (modelName != null) {
            tooltip += " | Model: " + modelName;
        }
        tooltip += ". Click to open.";
        return tooltip;
    }

    @Override
    public @Nullable Consumer<MouseEvent> getClickConsumer() {
        return mouseEvent -> {
            if (project.isDisposed()) return;
            ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Claude");
            if (toolWindow != null) {
                toolWindow.activate(null);
            }
        };
    }

    @Override
    public @NotNull WidgetPresentation getPresentation() {
        return this;
    }

    // --- Internal ---

    private void updateState(CliState newState) {
        cliState = newState;
        refreshWidget();
    }

    private void refreshWidget() {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (statusBar != null) {
                statusBar.updateWidget(WIDGET_ID);
            }
        });
    }

    /**
     * Shortens a model name for display.
     * E.g., "claude-sonnet-4-20250514" → "sonnet-4"
     *        "claude-3-5-sonnet-20241022" → "3-5-sonnet"
     */
    static String shortenModelName(String model) {
        if (model == null) return "";
        String name = model.replaceFirst("^claude-", "");
        name = name.replaceAll("-\\d{8}$", "");
        name = name.replaceAll("-latest$", "");
        return name;
    }
}
