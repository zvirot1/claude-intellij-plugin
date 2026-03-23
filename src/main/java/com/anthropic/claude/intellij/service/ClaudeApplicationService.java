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
     * Returns the application-wide singleton instance.
     */
    public static ClaudeApplicationService getInstance() {
        return ApplicationManager.getApplication().getService(ClaudeApplicationService.class);
    }

    public ClaudeApplicationService() {
        LOG.info("ClaudeApplicationService initialized");
    }
}
