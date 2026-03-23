package com.anthropic.claude.intellij.service;

import com.anthropic.claude.intellij.cli.ClaudeCliManager;
import com.anthropic.claude.intellij.diff.CheckpointManager;
import com.anthropic.claude.intellij.diff.EditDecisionManager;
import com.anthropic.claude.intellij.model.ConversationModel;
import com.anthropic.claude.intellij.session.ClaudeSessionManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Per-project service that owns the core subsystems for a single Claude session:
 * <ul>
 *     <li>{@link ClaudeCliManager} &mdash; manages the Claude CLI process lifecycle</li>
 *     <li>{@link ConversationModel} &mdash; maintains the conversation state and notifies the UI</li>
 *     <li>{@link CheckpointManager} &mdash; snapshots files before Claude edits them</li>
 * </ul>
 *
 * Registered as a {@code projectService} in {@code plugin.xml}.
 * The platform creates one instance per open project and calls {@link #dispose()}
 * when the project closes.
 */
public final class ClaudeProjectService implements Disposable {

    private static final Logger LOG = Logger.getInstance(ClaudeProjectService.class);

    private final Project project;
    private final ClaudeCliManager cliManager;
    private final ConversationModel conversationModel;
    private final CheckpointManager checkpointManager;
    private final EditDecisionManager editDecisionManager;
    private final ClaudeSessionManager sessionManager;

    // ConversationModel implements ICliMessageListener directly via onMessage(CliMessage),
    // so no adapter is needed — it is registered directly as a message listener.

    public ClaudeProjectService(@NotNull Project project) {
        this.project = project;
        this.cliManager = new ClaudeCliManager();
        this.conversationModel = new ConversationModel();
        this.checkpointManager = new CheckpointManager();
        this.editDecisionManager = new EditDecisionManager(checkpointManager);
        this.sessionManager = new ClaudeSessionManager();
    }

    /**
     * Returns the per-project singleton instance.
     */
    public static ClaudeProjectService getInstance(@NotNull Project project) {
        return project.getService(ClaudeProjectService.class);
    }

    /**
     * Returns the CLI process manager for this project.
     */
    public ClaudeCliManager getCliManager() {
        return cliManager;
    }

    /**
     * Returns the conversation model for this project.
     */
    public ConversationModel getConversationModel() {
        return conversationModel;
    }

    /**
     * Returns the checkpoint (file-snapshot) manager for this project.
     */
    public CheckpointManager getCheckpointManager() {
        return checkpointManager;
    }

    /**
     * Returns the edit decision manager for accept/reject workflow.
     */
    public EditDecisionManager getEditDecisionManager() {
        return editDecisionManager;
    }

    /**
     * Returns the session manager for this project.
     */
    public ClaudeSessionManager getSessionManager() {
        return sessionManager;
    }

    /**
     * Returns the IntelliJ project associated with this service instance.
     */
    public Project getProject() {
        return project;
    }

    /**
     * Performs initial setup: auto-detects the CLI binary and wires the
     * conversation model as a message listener on the CLI manager.
     * <p>
     * Call this once after the tool window or any consumer is ready to
     * receive events. It is safe to call multiple times; the listener is
     * registered only on the first invocation.
     */
    public void initialize() {
        // Wire model as CLI message listener (ConversationModel implements ICliMessageListener)
        cliManager.addMessageListener(conversationModel);

        String detectedPath = ClaudeCliManager.getCliPath();
        if (detectedPath != null) {
            LOG.info("Auto-detected Claude CLI at: " + detectedPath);
        } else {
            LOG.warn("Claude CLI not found. Please configure the path in Settings > Tools > Claude Code.");
        }
    }

    /**
     * Stops the CLI process and releases resources.
     * Called by the platform when the project is closed.
     */
    @Override
    public void dispose() {
        try {
            cliManager.removeMessageListener(conversationModel);
            cliManager.stop();
        } catch (Exception e) {
            LOG.error("Error disposing ClaudeProjectService", e);
        }
        checkpointManager.clearCheckpoints();
        conversationModel.clear();
        LOG.info("ClaudeProjectService disposed for project: " + project.getName());
    }
}
