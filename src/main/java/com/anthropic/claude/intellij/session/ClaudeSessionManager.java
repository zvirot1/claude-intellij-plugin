package com.anthropic.claude.intellij.session;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import com.anthropic.claude.intellij.model.ConversationModel;
import com.anthropic.claude.intellij.model.MessageBlock;
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
     */
    public List<SessionInfo> listSessions() {
        return store.listAllSessions();
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

        // Generate summary from first user message
        if (currentSession.getSummary() == null && !model.getMessages().isEmpty()) {
            for (MessageBlock msg : model.getMessages()) {
                if (msg.getRole() == MessageBlock.Role.USER) {
                    String text = msg.getFullText();
                    if (text.length() > 60) {
                        text = text.substring(0, 57) + "...";
                    }
                    currentSession.setSummary(text);
                    break;
                }
            }
        }

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
