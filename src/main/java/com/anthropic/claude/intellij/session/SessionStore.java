package com.anthropic.claude.intellij.session;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import com.anthropic.claude.intellij.model.SessionInfo;
import com.anthropic.claude.intellij.util.JsonParser;
import com.intellij.openapi.diagnostic.Logger;

/**
 * File-based session storage.
 * Sessions are stored as JSON files in the IntelliJ system directory
 * under {@code claude-sessions/}.
 */
public class SessionStore {

    private static final Logger LOG = Logger.getInstance(SessionStore.class);
    private static final String SESSIONS_DIR = "claude-sessions";

    private Path sessionsDir;

    public SessionStore(Path baseDir) {
        this.sessionsDir = baseDir.resolve(SESSIONS_DIR);
        try {
            Files.createDirectories(sessionsDir);
        } catch (IOException e) {
            LOG.warn("Failed to create sessions directory: " + sessionsDir, e);
            // Fallback to temp directory
            sessionsDir = Paths.get(System.getProperty("java.io.tmpdir"), "claude-intellij-sessions");
            try {
                Files.createDirectories(sessionsDir);
            } catch (IOException ignored) {}
        }
    }

    /**
     * Save session info to disk.
     */
    public void saveSession(SessionInfo info) {
        if (info == null || info.getSessionId() == null) return;

        try {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("sessionId", info.getSessionId());
            json.put("model", info.getModel());
            json.put("workingDirectory", info.getWorkingDirectory());
            json.put("startTime", info.getStartTime());
            json.put("lastActiveTime", info.getLastActiveTime());
            json.put("permissionMode", info.getPermissionMode());
            json.put("messageCount", info.getMessageCount());
            json.put("summary", info.getSummary());

            String jsonStr = JsonParser.toJson(json);
            Path filePath = sessionsDir.resolve(info.getSessionId() + ".json");
            Files.writeString(filePath, jsonStr, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.error("Failed to save session: " + info.getSessionId(), e);
        }
    }

    /**
     * Load session info from disk.
     */
    public SessionInfo loadSession(String sessionId) {
        try {
            Path filePath = sessionsDir.resolve(sessionId + ".json");
            if (!Files.exists(filePath)) return null;

            String content = Files.readString(filePath, StandardCharsets.UTF_8);
            Map<String, Object> json = JsonParser.parseObject(content);

            SessionInfo info = new SessionInfo();
            info.setSessionId(JsonParser.getString(json, "sessionId"));
            info.setModel(JsonParser.getString(json, "model"));
            info.setWorkingDirectory(JsonParser.getString(json, "workingDirectory"));
            info.setStartTime(JsonParser.getLong(json, "startTime", 0));
            info.setLastActiveTime(JsonParser.getLong(json, "lastActiveTime", 0));
            info.setPermissionMode(JsonParser.getString(json, "permissionMode"));
            info.setMessageCount(JsonParser.getInt(json, "messageCount", 0));
            info.setSummary(JsonParser.getString(json, "summary"));

            return info;
        } catch (Exception e) {
            LOG.error("Failed to load session: " + sessionId, e);
            return null;
        }
    }

    /**
     * List all saved sessions, sorted by last active time (newest first).
     */
    public List<SessionInfo> listAllSessions() {
        List<SessionInfo> sessions = new ArrayList<>();
        try {
            if (!Files.exists(sessionsDir)) return sessions;

            try (var stream = Files.list(sessionsDir)) {
                stream.filter(p -> p.toString().endsWith(".json"))
                    .forEach(p -> {
                        String filename = p.getFileName().toString();
                        String sessionId = filename.substring(0, filename.length() - 5);
                        SessionInfo info = loadSession(sessionId);
                        if (info != null) {
                            sessions.add(info);
                        }
                    });
            }

            // Sort by last active time, newest first
            sessions.sort((a, b) -> Long.compare(b.getLastActiveTime(), a.getLastActiveTime()));

        } catch (IOException e) {
            LOG.error("Failed to list sessions", e);
        }
        return sessions;
    }

    /**
     * Delete a session file.
     */
    public void deleteSession(String sessionId) {
        try {
            Path filePath = sessionsDir.resolve(sessionId + ".json");
            Files.deleteIfExists(filePath);
        } catch (IOException e) {
            LOG.error("Failed to delete session: " + sessionId, e);
        }
    }

    /**
     * Clean up old sessions, keeping only the most recent N.
     */
    public void cleanupOldSessions(int keepCount) {
        List<SessionInfo> sessions = listAllSessions();
        if (sessions.size() > keepCount) {
            for (int i = keepCount; i < sessions.size(); i++) {
                deleteSession(sessions.get(i).getSessionId());
            }
        }
    }
}
