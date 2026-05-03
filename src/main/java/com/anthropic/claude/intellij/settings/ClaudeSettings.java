package com.anthropic.claude.intellij.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Persistent application-level settings for the Claude Code plugin.
 * Stored in {@code ClaudeCodeSettings.xml} inside the IDE config directory.
 */
@State(name = "ClaudeCodeSettings", storages = @Storage("ClaudeCodeSettings.xml"))
public final class ClaudeSettings implements PersistentStateComponent<ClaudeSettings.State> {

    /**
     * Serializable state holder. Public fields are persisted by the platform.
     */
    public static class State {
        /** Absolute path to the Claude CLI binary. Empty means auto-detect. */
        public String cliPath = "";

        /** Model identifier: "default", "sonnet", "opus". */
        public String selectedModel = "default";

        /**
         * Initial permission mode for the CLI process.
         * One of: "default", "plan", "acceptEdits", "bypassPermissions".
         */
        public String initialPermissionMode = "default";

        /** Effort level: "" (auto), "low", "medium", "high", "max". */
        public String effortLevel = "medium";

        /** Comma-separated list of session IDs for open tabs (restored on startup). */
        public String openTabSessionIds = "";

        /** When true, the plugin emits verbose [DIAG-*] log lines (only enable for bug investigation). */
        public boolean diagnosticLogging = false;

        /**
         * When true, every outgoing message automatically includes the contents of the
         * currently active editor file as context — Amazon Q-style "Active file" pin.
         * Default is OFF: users opt in per-tab via the toggle next to the input.
         */
        public boolean attachActiveFile = false;

        /** Whether to auto-save files after Claude edits them. */
        public boolean autosave = true;

        /** Whether Ctrl+Enter (Cmd+Enter on macOS) sends the message instead of plain Enter. */
        public boolean useCtrlEnterToSend = false;

        /** Whether to exclude files matched by .gitignore when sending context. */
        public boolean respectGitIgnore = true;

        /** Maximum tokens for CLI responses (0 = CLI default). */
        public int maxTokens = 0;

        /** Custom system prompt appended to the default. Empty = none. */
        public String systemPrompt = "";

        /** Whether to show the cost display in the status bar. */
        public boolean showCost = true;

        /** Whether to show streaming output in real time. */
        public boolean showStreaming = true;

        /** Maximum number of sessions to keep in history. */
        public int sessionHistoryLimit = 100;

        /** Whether to auto-save dirty editors before tools run. */
        public boolean autoSaveBeforeTools = false;

        /** User-added custom model names, comma-separated. Persisted across sessions. */
        public String customModels = "";
    }

    private State myState = new State();

    /**
     * Returns the application-wide singleton instance.
     */
    public static ClaudeSettings getInstance() {
        return ApplicationManager.getApplication().getService(ClaudeSettings.class);
    }

    @Override
    public @Nullable State getState() {
        return myState;
    }

    @Override
    public void loadState(@NotNull State state) {
        myState = state;
    }
}
