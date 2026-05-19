package com.anthropic.claude.intellij.cli;

import java.io.IOException;

/**
 * Listener for NDJSON messages received from the Claude CLI process.
 */
public interface ICliMessageListener {

    /**
     * Called when a complete NDJSON message is parsed from CLI stdout.
     * @param message The parsed message (SystemInit, AssistantMessage, StreamEvent, etc.)
     */
    void onMessage(CliMessage message);

    /**
     * Called when a line from stdout could not be parsed as valid NDJSON.
     * @param rawLine The raw line that failed to parse
     * @param error The parse exception
     */
    default void onParseError(String rawLine, Exception error) {
        // Default: ignore parse errors
    }

    /**
     * Called when the connection to the CLI process is lost.
     * @param error The IOException that caused the disconnection
     */
    default void onConnectionError(IOException error) {
        // Default: ignore connection errors
    }

    /**
     * Called when {@code ClaudeCliManager.readProcessErrors} pattern-matches
     * a stderr line that indicates a corporate hook (AIM proxy, AWS auth
     * refresh, etc.) blocked the request and no {@code hook_response}
     * stream-json event is going to arrive. Allows listeners to short-circuit
     * "Running" tool calls and surface a visible error to the user.
     *
     * @param detail human-readable stderr fragment that triggered the match
     */
    default void onHookBlocked(String detail) {
        // Default: ignore — non-recovery listeners don't care
    }
}
