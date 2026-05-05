package com.anthropic.claude.intellij.session;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import com.anthropic.claude.intellij.model.ConversationModel;
import com.anthropic.claude.intellij.model.SessionInfo;
import com.intellij.openapi.diagnostic.Logger;

/**
 * Manages Claude conversation sessions.
 * Handles creating, resuming, and persisting sessions.
 */
public class ClaudeSessionManager {

    private static final Logger LOG = Logger.getInstance(ClaudeSessionManager.class);
    private static final int DEFAULT_MAX_STORED_SESSIONS = 50;

    private final SessionStore store;
    private SessionInfo currentSession;

    public ClaudeSessionManager() {
        Path baseDir = Paths.get(System.getProperty("user.home"), ".claude");
        this.store = new SessionStore(baseDir);
    }

    public ClaudeSessionManager(Path baseDir) {
        this.store = new SessionStore(baseDir);
    }

    /**
     * Start a new session.
     */
    public SessionInfo startNewSession(String workingDirectory) {
        currentSession = new SessionInfo(UUID.randomUUID().toString());
        currentSession.setWorkingDirectory(workingDirectory);
        store.saveSession(currentSession);
        store.cleanupOldSessions(DEFAULT_MAX_STORED_SESSIONS);
        return currentSession;
    }

    /**
     * Resume a previous session by ID.
     */
    public SessionInfo resumeSession(String sessionId) {
        SessionInfo info = store.loadSession(sessionId);
        if (info != null) {
            currentSession = info;
            currentSession.touch();
            store.saveSession(currentSession);
        }
        return info;
    }

    /**
     * Continue the most recent session.
     */
    public SessionInfo continueLastSession() {
        List<SessionInfo> sessions = store.listAllSessions();
        if (!sessions.isEmpty()) {
            return resumeSession(sessions.get(0).getSessionId());
        }
        return null;
    }

    /**
     * List available sessions.
     * <p>
     * Reads directly from Claude CLI's JSONL transcripts in
     * {@code ~/.claude/projects/} (matching VS Code extension behaviour) and
     * merges with the plugin's local {@link SessionStore} metadata. The JSONL
     * scan is the source of truth — the SessionStore is only used for fields
     * we can't cheaply read from JSONL (e.g. custom titles set via /rename).
     */
    public List<SessionInfo> listSessions() {
        return listSessions(null);
    }

    /**
     * List sessions, restricted to the given project directory if non-null.
     * Project matching is done against the encoded directory name used by the CLI.
     */
    public List<SessionInfo> listSessions(String projectDir) {
        // Start from JSONL (source of truth)
        List<SessionInfo> fromJsonl = JsonlSessionScanner.listSessions(projectDir);

        // Index by sessionId for fast merge
        java.util.Map<String, SessionInfo> byId = new java.util.LinkedHashMap<>();
        for (SessionInfo s : fromJsonl) {
            if (s.getSessionId() != null) byId.put(s.getSessionId(), s);
        }

        // Merge: overlay local SessionStore metadata only for cached fields
        try {
            for (SessionInfo local : store.listAllSessions()) {
                SessionInfo merged = byId.get(local.getSessionId());
                if (merged != null) {
                    // Prefer local summary if it was curated (e.g. renamed tab)
                    if (local.getSummary() != null && !local.getSummary().isEmpty()) {
                        merged.setSummary(local.getSummary());
                    }
                    if (local.getPermissionMode() != null && merged.getPermissionMode() == null) {
                        merged.setPermissionMode(local.getPermissionMode());
                    }
                }
                // Don't add local-only sessions — if there's no JSONL, it's
                // an empty/orphan session and we don't want to surface it.
            }
        } catch (Exception ignored) {}

        return new java.util.ArrayList<>(byId.values());
    }

    /**
     * Save current session state from a ConversationModel.
     */
    public void saveCurrentSession(ConversationModel model) {
        if (currentSession == null) return;

        SessionInfo modelSession = model.getSessionInfo();
        if (modelSession != null) {
            currentSession.setSessionId(modelSession.getSessionId());
            currentSession.setModel(modelSession.getModel());
            if (modelSession.getWorkingDirectory() != null) {
                currentSession.setWorkingDirectory(modelSession.getWorkingDirectory());
            }
        }
        currentSession.setMessageCount(model.getMessageCount());
        currentSession.touch();

        // Note: no auto-generated summary here. The Session History list reads
        // its title from JSONL (JsonlSessionScanner), preferring the CLI's own
        // {"type":"summary",…} entry — which is an LLM-written tight title —
        // and falling back to the first user message. The local SessionStore
        // summary is reserved for explicitly curated values (e.g. /rename).

        store.saveSession(currentSession);
    }

    /**
     * Get the current session.
     */
    public SessionInfo getCurrentSession() {
        return currentSession;
    }

    /**
     * Delete a stored session.
     */
    public void deleteSession(String sessionId) {
        store.deleteSession(sessionId);
    }
}
