package com.anthropic.claude.intellij.service;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

/**
 * Application-level singleton service for the Claude Code plugin.
 * Holds shared state that is not project-specific, such as global
 * feature flags or cross-project coordination data.
 *
 * Registered as an {@code applicationService} in {@code plugin.xml}.
 */
public final class ClaudeApplicationService {

    private static final Logger LOG = Logger.getInstance(ClaudeApplicationService.class);

    /**
     * Diagnostic logging flag. When true, the plugin emits verbose [DIAG-*]
     * log lines that help investigate bugs without rebuilding.
     * <p>
     * Enable via:
     * <ul>
     *   <li>JVM option: {@code -Dclaude.diag=true}</li>
     *   <li>Settings → Claude Code → "Enable diagnostic logging"</li>
     * </ul>
     */
    public static volatile boolean DIAG_ENABLED = Boolean.getBoolean("claude.diag");

    /**
     * Logs a diagnostic line if {@link #DIAG_ENABLED} is true. Use [DIAG-*] tag prefixes
     * for filterability (e.g. {@code [DIAG-MSG]}, {@code [DIAG-LISTENER]}, {@code [DIAG-STDERR]}).
     */
    public static void logDiag(String message) {
        if (DIAG_ENABLED) {
            LOG.info(message);
        }
    }

    /**
     * Returns the application-wide singleton instance.
     */
    public static ClaudeApplicationService getInstance() {
        return ApplicationManager.getApplication().getService(ClaudeApplicationService.class);
    }

    public ClaudeApplicationService() {
        // Pick up persisted preference on first access
        try {
            com.anthropic.claude.intellij.settings.ClaudeSettings settings =
                com.anthropic.claude.intellij.settings.ClaudeSettings.getInstance();
            if (settings != null) {
                DIAG_ENABLED = DIAG_ENABLED || settings.getState().diagnosticLogging;
            }
        } catch (Exception ignored) {
            // Settings may not be available during very early init; keep JVM-property value.
        }
        LOG.info("[DIAG-START] ClaudeApplicationService initialized — DIAG_ENABLED=" + DIAG_ENABLED);
    }
}
