package com.anthropic.claude.intellij.handlers;

import java.util.*;

/**
 * Handles slash commands typed in the conversation input.
 * Some commands are handled locally by the plugin, others are forwarded to the CLI.
 */
public class SlashCommandHandler {

    /**
     * Info about a slash command.
     */
    public static class CommandInfo {
        private final String name;
        private final String description;
        private final boolean localOnly;
        private final boolean hasSubOptions;

        public CommandInfo(String name, String description, boolean localOnly) {
            this(name, description, localOnly, false);
        }

        public CommandInfo(String name, String description, boolean localOnly, boolean hasSubOptions) {
            this.name = name;
            this.description = description;
            this.localOnly = localOnly;
            this.hasSubOptions = hasSubOptions;
        }

        public String getName() { return name; }
        public String getDescription() { return description; }
        public boolean isLocalOnly() { return localOnly; }
        public boolean hasSubOptions() { return hasSubOptions; }
    }

    /**
     * Info about a sub-option for a slash command.
     */
    public static class SubOption {
        private final String value;
        private final String label;
        private final String description;

        public SubOption(String value, String label, String description) {
            this.value = value;
            this.label = label;
            this.description = description;
        }

        public String getValue() { return value; }
        public String getLabel() { return label; }
        public String getDescription() { return description; }
    }

    private static final List<CommandInfo> COMMANDS = new ArrayList<>();

    static {
        // Local commands (handled by the plugin)
        COMMANDS.add(new CommandInfo("/new", "Start a new conversation", true));
        COMMANDS.add(new CommandInfo("/clear", "Clear the conversation display", true));
        COMMANDS.add(new CommandInfo("/cost", "Show token usage and cost summary", true));
        COMMANDS.add(new CommandInfo("/help", "Show available commands", true));
        COMMANDS.add(new CommandInfo("/stop", "Stop the current query", true));
        COMMANDS.add(new CommandInfo("/model", "Switch to a different model", true, true));
        COMMANDS.add(new CommandInfo("/resume", "Resume a previous session", true));
        COMMANDS.add(new CommandInfo("/history", "Browse and search session history", true));
        COMMANDS.add(new CommandInfo("/compact", "Compact conversation context", true));
        COMMANDS.add(new CommandInfo("/rules", "Manage Claude Code rules", true));
        COMMANDS.add(new CommandInfo("/mcp", "Manage MCP servers", true));
        COMMANDS.add(new CommandInfo("/hooks", "Manage hooks", true));
        COMMANDS.add(new CommandInfo("/memory", "Edit project memory", true));
        COMMANDS.add(new CommandInfo("/skills", "Browse installed plugins and skills", true));

        // CLI-forwarded commands (sent to Claude as regular messages)
        COMMANDS.add(new CommandInfo("/commit", "Generate a git commit message", false));
        COMMANDS.add(new CommandInfo("/review-pr", "Review a pull request", false));
        COMMANDS.add(new CommandInfo("/explain", "Explain the current file or selection", false));
        COMMANDS.add(new CommandInfo("/fix", "Fix bugs in the current file", false));
        COMMANDS.add(new CommandInfo("/test", "Generate tests for the current code", false));
        COMMANDS.add(new CommandInfo("/refactor", "Refactor the current code", false));
    }

    /**
     * Check if a text input starts with a slash command.
     */
    public static boolean isSlashCommand(String input) {
        return input != null && input.startsWith("/") && !input.startsWith("//");
    }

    /**
     * Check if a command is a local command (handled by the plugin, not forwarded to CLI).
     */
    public static boolean isLocalCommand(String input) {
        if (input == null) return false;
        String cmd = input.split("\\s+")[0].toLowerCase();
        for (CommandInfo info : COMMANDS) {
            if (info.name.equals(cmd) && info.localOnly) {
                return true;
            }
        }
        return false;
    }

    /**
     * Extract the command name from a slash command input (e.g., "/model opus" -> "/model").
     */
    public static String getCommandName(String input) {
        if (input == null) return "";
        return input.split("\\s+")[0].toLowerCase();
    }

    /**
     * Extract the argument part from a slash command input (e.g., "/model opus" -> "opus").
     */
    public static String getCommandArgs(String input) {
        if (input == null) return "";
        String trimmed = input.trim();
        int spaceIdx = trimmed.indexOf(' ');
        if (spaceIdx < 0) return "";
        return trimmed.substring(spaceIdx + 1).trim();
    }

    /**
     * Get all available commands.
     */
    public static List<CommandInfo> getAllCommands() {
        return Collections.unmodifiableList(COMMANDS);
    }

    /**
     * Get auto-complete suggestions for a prefix.
     * Returns CommandInfo objects whose names start with the given prefix.
     */
    public static List<CommandInfo> getSuggestions(String prefix) {
        List<CommandInfo> suggestions = new ArrayList<>();
        if (prefix == null || prefix.isEmpty()) {
            return suggestions;
        }
        String lowerPrefix = prefix.toLowerCase();
        for (CommandInfo cmd : COMMANDS) {
            if (cmd.name.startsWith(lowerPrefix)) {
                suggestions.add(cmd);
            }
        }
        return suggestions;
    }

    /**
     * Get sub-options for commands that support them (e.g. /model).
     * Returns empty list if command has no sub-options.
     */
    public static List<SubOption> getSubOptions(String commandName) {
        List<SubOption> options = new ArrayList<>();
        switch (commandName) {
            case "/model":
                options.add(new SubOption("sonnet", "Sonnet", "Claude Sonnet — fast and capable"));
                options.add(new SubOption("opus", "Opus", "Claude Opus — most powerful"));
                options.add(new SubOption("haiku", "Haiku", "Claude Haiku — fastest and lightest"));
                options.add(new SubOption("claude-sonnet-4-20250514", "Sonnet 4", "Claude Sonnet 4 specific version"));
                options.add(new SubOption("claude-opus-4-20250514", "Opus 4", "Claude Opus 4 specific version"));
                break;
            default:
                break;
        }
        return options;
    }

    /**
     * Format a help message with all available commands as markdown.
     */
    public static String formatHelp() {
        StringBuilder sb = new StringBuilder();
        sb.append("## Available Commands\n\n");
        sb.append("**Plugin Commands:**\n\n");
        for (CommandInfo cmd : COMMANDS) {
            if (cmd.localOnly) {
                sb.append("- `").append(cmd.name).append("` — ").append(cmd.description).append("\n");
            }
        }
        sb.append("\n**Claude Commands** (forwarded to CLI):\n\n");
        for (CommandInfo cmd : COMMANDS) {
            if (!cmd.localOnly) {
                sb.append("- `").append(cmd.name).append("` — ").append(cmd.description).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * Format cost information as markdown.
     */
    public static String formatCost(String formattedTokens, String formattedCost, String formattedDuration, int turns) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Session Cost Summary\n\n");
        if (formattedTokens != null && !formattedTokens.isEmpty()) {
            sb.append("- **Tokens:** ").append(formattedTokens).append("\n");
        }
        if (formattedCost != null && !formattedCost.isEmpty()) {
            sb.append("- **Cost:** ").append(formattedCost).append("\n");
        }
        if (formattedDuration != null && !formattedDuration.isEmpty()) {
            sb.append("- **Duration:** ").append(formattedDuration).append("\n");
        }
        if (turns > 0) {
            sb.append("- **Turns:** ").append(turns).append("\n");
        }
        return sb.toString();
    }
}
