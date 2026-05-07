package com.anthropic.claude.intellij.ui;

import com.anthropic.claude.intellij.settings.ClaudeSettings;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManagerEvent;
import com.intellij.ui.content.ContentManagerListener;
import org.jetbrains.annotations.NotNull;

public class ClaudeToolWindowFactory implements ToolWindowFactory, com.intellij.openapi.startup.StartupActivity {

    @Override
    public boolean shouldBeAvailable(@NotNull Project project) {
        return true;
    }

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        // Restore saved tabs from settings
        ClaudeSettings settingsInstance = ClaudeSettings.getInstance();
        String savedIds = (settingsInstance != null) ? settingsInstance.getState().openTabSessionIds : "";

        if (savedIds != null && !savedIds.isEmpty()) {
            // Local SessionStore (~/.claude/sessions/) lookup \u2014 fast, but
            // since the auto-summary generator was removed in 3233fd4,
            // most sessions have no summary there.
            com.anthropic.claude.intellij.session.ClaudeSessionManager sm =
                new com.anthropic.claude.intellij.session.ClaudeSessionManager();

            String[] sessionIds = savedIds.split(",");
            // Track <Content, sessionId> so we can update display names
            // asynchronously when the JSONL lookup finishes.
            final java.util.List<Object[]> pending = new java.util.ArrayList<>();
            for (int i = 0; i < sessionIds.length; i++) {
                String sid = sessionIds[i].trim();
                if (sid.isEmpty()) continue;

                // Try fast local store first.
                String tabName = "Chat " + (i + 1);
                boolean hasCustomName = false;
                try {
                    com.anthropic.claude.intellij.model.SessionInfo info = sm.resumeSession(sid);
                    if (info != null && info.getSummary() != null && !info.getSummary().isEmpty()) {
                        tabName = info.getSummary();
                        if (tabName.length() > 30) tabName = tabName.substring(0, 30) + "\u2026";
                        hasCustomName = true;
                    }
                } catch (Exception ignored) {}

                ClaudeChatPanel chatPanel = new ClaudeChatPanel(project);
                chatPanel.setResumeSessionId(sid);
                if (hasCustomName) {
                    chatPanel.markTabNameAsCustom();
                }
                Content content = ContentFactory.getInstance()
                    .createContent(chatPanel.getComponent(), tabName, true);
                content.setCloseable(true);
                content.setDisposer(chatPanel);
                content.putUserData(SESSION_ID_KEY, sid);
                toolWindow.getContentManager().addContent(content);

                if (!hasCustomName) {
                    pending.add(new Object[]{content, sid});
                }
            }

            // Background pass: for tabs that started as "Chat N" because the
            // local store had no summary, look up the canonical title from
            // JSONL (CLI auto-summary or first user message) and update the
            // tab name on EDT. Done off-thread so startup stays snappy.
            if (!pending.isEmpty()) {
                Thread t = new Thread(() -> {
                    for (Object[] pair : pending) {
                        final Content c = (Content) pair[0];
                        final String sid = (String) pair[1];
                        try {
                            com.anthropic.claude.intellij.model.SessionInfo full =
                                com.anthropic.claude.intellij.session.JsonlSessionScanner
                                        .findSessionById(sid);
                            if (full == null) continue;
                            String summary = full.getSummary();
                            if (summary == null || summary.isEmpty()) continue;
                            final String name = summary.length() > 30
                                ? summary.substring(0, 30) + "\u2026" : summary;
                            com.intellij.openapi.application.ApplicationManager.getApplication()
                                .invokeLater(() -> c.setDisplayName(name));
                        } catch (Exception ignored) {}
                    }
                }, "Claude-TabTitle-Restore");
                t.setDaemon(true);
                t.start();
            }
        }

        // If no saved tabs, create one default tab
        if (toolWindow.getContentManager().getContentCount() == 0) {
            ClaudeChatPanel chatPanel = new ClaudeChatPanel(project);
            Content content = ContentFactory.getInstance()
                .createContent(chatPanel.getComponent(), "Chat", true);
            content.setCloseable(true);
            content.setDisposer(chatPanel);
            toolWindow.getContentManager().addContent(content);
        }

        // Listen for tab add/remove to persist open tab list
        toolWindow.getContentManager().addContentManagerListener(new ContentManagerListener() {
            @Override
            public void contentAdded(@NotNull ContentManagerEvent event) {
                saveOpenTabIds(toolWindow);
            }

            @Override
            public void contentRemoved(@NotNull ContentManagerEvent event) {
                saveOpenTabIds(toolWindow);
            }
        });
    }

    /**
     * Saves the session IDs of all open tabs to settings.
     */
    public static void saveOpenTabIds(ToolWindow toolWindow) {
        ClaudeSettings settingsInstance = ClaudeSettings.getInstance();
        if (settingsInstance == null) return;

        StringBuilder ids = new StringBuilder();
        for (Content c : toolWindow.getContentManager().getContents()) {
            // Find the ClaudeChatPanel in this content
            if (c.getComponent() instanceof javax.swing.JPanel) {
                // Try to get session ID from the panel's conversation model
                // We store a data key on the content for this purpose
                String sid = (String) c.getUserData(SESSION_ID_KEY);
                if (sid != null && !sid.isEmpty()) {
                    if (ids.length() > 0) ids.append(",");
                    ids.append(sid);
                }
            }
        }
        settingsInstance.getState().openTabSessionIds = ids.toString();
    }

    /** Key for storing session ID on Content objects. */
    public static final com.intellij.openapi.util.Key<String> SESSION_ID_KEY =
        com.intellij.openapi.util.Key.create("claude.session.id");

    /**
     * Called after the project is fully opened — auto-show the Claude tool window.
     */
    @Override
    public void runActivity(@NotNull Project project) {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
            ToolWindow tw = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("Claude");
            if (tw != null && !tw.isVisible()) {
                tw.show();
            }
        });
    }
}
