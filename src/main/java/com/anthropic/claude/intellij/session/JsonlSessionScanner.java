package com.anthropic.claude.intellij.session;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.anthropic.claude.intellij.model.SessionInfo;
import com.anthropic.claude.intellij.util.JsonParser;
import com.intellij.openapi.diagnostic.Logger;

/**
 * Scans Claude CLI's JSONL transcript files in {@code ~/.claude/projects/} to produce
 * a {@link SessionInfo} list — a direct analogue of VS Code extension's
 * {@code listSessions()} (which calls {@code @anthropic-ai/claude-agent-sdk}'s
 * {@code listSessions()}).
 *
 * <p>This is the single source of truth for conversation history — the plugin's
 * {@link SessionStore} is only a secondary cache of display summaries.
 *
 * <p>Project directory name convention mirrors the CLI:
 * {@code C:\dev\foo} → {@code C--dev-foo}.
 */
public class JsonlSessionScanner {

    private static final Logger LOG = Logger.getInstance(JsonlSessionScanner.class);

    /**
     * Returns all sessions across all projects, newest-first by file mtime.
     * If {@code projectDir} is non-null, restricts to sessions whose project
     * directory matches that path.
     */
    /**
     * Fast variant: returns SessionInfo for every JSONL with ONLY filename +
     * mtime — no file content read. Used to populate the Session History list
     * instantly even with many GB of transcripts on disk. Call
     * {@link #fillSessionDetails(SessionInfo)} on demand to fetch
     * summary/model/messageCount lazily for visible rows.
     */
    public static List<SessionInfo> listSessionsFast(String projectDir) {
        List<SessionInfo> result = new ArrayList<>();
        File projectsRoot = new File(System.getProperty("user.home") + "/.claude/projects");
        if (!projectsRoot.isDirectory()) return result;
        File[] projectDirs = projectsRoot.listFiles(File::isDirectory);
        if (projectDirs == null) return result;
        String matchPrefix = (projectDir != null && !projectDir.isEmpty())
                ? encodeProjectKey(projectDir).toLowerCase() : null;
        for (File dir : projectDirs) {
            if (matchPrefix != null) {
                String lower = dir.getName().toLowerCase();
                if (!lower.equals(matchPrefix) && !lower.startsWith(matchPrefix + "-")) continue;
            }
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (!f.isFile()) continue;
                String n = f.getName();
                if (!n.endsWith(".jsonl")) continue;
                String sessionId = n.substring(0, n.length() - 6);
                if (!isUuid(sessionId)) continue;
                SessionInfo info = new SessionInfo(sessionId);
                info.setLastActiveTime(f.lastModified());
                info.setStartTime(f.lastModified());
                info.setWorkingDirectory(dir.getName());
                info.setMessageCount(0); // unknown until detailed read
                result.add(info);
            }
        }
        result.sort(Comparator.comparingLong(SessionInfo::getLastActiveTime).reversed());
        return result;
    }

    /**
     * Lazily read summary / model / messageCount for a single SessionInfo by
     * locating its {@code .jsonl} on disk. Mutates the input — no-op if the
     * file is missing or unparseable.
     */
    public static void fillSessionDetails(SessionInfo info) {
        if (info == null || info.getSessionId() == null) return;
        SessionInfo full = findSessionById(info.getSessionId());
        if (full == null) return;
        if (full.getSummary() != null)  info.setSummary(full.getSummary());
        if (full.getModel() != null)    info.setModel(full.getModel());
        if (full.getMessageCount() > 0) info.setMessageCount(full.getMessageCount());
        if (full.getStartTime() > 0)    info.setStartTime(full.getStartTime());
    }

    /**
     * Look up a single session's full SessionInfo by id. Searches every
     * project under {@code ~/.claude/projects/} for the matching JSONL.
     * Returns null if not found.
     */
    public static SessionInfo findSessionById(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return null;
        File root = new File(System.getProperty("user.home") + "/.claude/projects");
        if (!root.isDirectory()) return null;
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null) return null;
        for (File dir : dirs) {
            File candidate = new File(dir, sessionId + ".jsonl");
            if (!candidate.isFile()) continue;
            try {
                return buildSessionInfo(candidate, sessionId, dir.getName());
            } catch (Exception ignored) {}
        }
        return null;
    }

    public static List<SessionInfo> listSessions(String projectDir) {
        List<SessionInfo> result = new ArrayList<>();
        File projectsRoot = new File(System.getProperty("user.home") + "/.claude/projects");
        if (!projectsRoot.isDirectory()) return result;

        File[] projectDirs = projectsRoot.listFiles(File::isDirectory);
        if (projectDirs == null) return result;

        String matchPrefix = null;
        if (projectDir != null && !projectDir.isEmpty()) {
            matchPrefix = encodeProjectKey(projectDir);
        }

        for (File dir : projectDirs) {
            if (matchPrefix != null) {
                String name = dir.getName();
                String lower = name.toLowerCase();
                String lowerPrefix = matchPrefix.toLowerCase();
                // Match either exact or with worktree suffix ("foo-bar")
                if (!lower.equals(lowerPrefix)
                        && !lower.startsWith(lowerPrefix + "-")) {
                    continue;
                }
            }
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (!f.isFile()) continue;
                String n = f.getName();
                if (!n.endsWith(".jsonl")) continue;
                String sessionId = n.substring(0, n.length() - 6);
                if (!isUuid(sessionId)) continue; // skip subagent-*.jsonl etc.
                try {
                    SessionInfo info = buildSessionInfo(f, sessionId, dir.getName());
                    if (info != null) result.add(info);
                } catch (Exception e) {
                    // Skip unreadable / malformed JSONL
                }
            }
        }

        // Newest first
        result.sort(Comparator.comparingLong(SessionInfo::getLastActiveTime).reversed());
        return result;
    }

    /**
     * Build a SessionInfo from a .jsonl file: read up to the first user message
     * to extract a summary, then use file mtime as lastActiveTime.
     */
    @SuppressWarnings("unchecked")
    private static SessionInfo buildSessionInfo(File jsonl, String sessionId, String projectKey) {
        SessionInfo info = new SessionInfo(sessionId);
        info.setLastActiveTime(jsonl.lastModified());
        info.setWorkingDirectory(projectKey);

        String firstUserSummary = null;
        String cliSummary = null; // last {"type":"summary",...} wins
        int messageCount = 0;
        long createdAt = 0L;
        String model = null;

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(jsonl), StandardCharsets.UTF_8))) {
            String line;
            int linesRead = 0;
            // Cap at 500 lines per file. With many GB of JSONL across
            // projects, full scans block the dialog for minutes. The first
            // 500 lines reliably contain the first user message and any
            // early CLI summary; messageCount becomes a lower bound.
            final int MAX_LINES_PER_FILE = 500;
            while ((line = br.readLine()) != null && linesRead++ < MAX_LINES_PER_FILE) {
                if (line.isEmpty()) continue;
                // Cheap pre-filter — we only care about user/assistant/summary
                // entries. Tool-use / tool-result lines can be megabytes;
                // parsing them just to discard the result was the hot-spot.
                // CLI 2.1.107+ omits top-level type for assistant turns —
                // detect them via "role":"assistant" inside the message obj.
                if (line.indexOf("\"type\":\"user\"")      < 0
                 && line.indexOf("\"type\":\"assistant\"") < 0
                 && line.indexOf("\"type\":\"summary\"")   < 0
                 && line.indexOf("\"role\":\"assistant\"") < 0) continue;
                try {
                    Map<String, Object> obj = JsonParser.parseObject(line);
                    String type = JsonParser.getString(obj, "type");
                    if (type == null) {
                        // CLI 2.1.107+: derive type from message.role for
                        // assistant turns that no longer carry top-level type.
                        Map<String, Object> mm = JsonParser.getMap(obj, "message");
                        if (mm != null) {
                            String r = JsonParser.getString(mm, "role");
                            if ("assistant".equals(r) || "user".equals(r)) type = r;
                        }
                        if (type == null) continue;
                    }
                    // CLI writes timestamp per entry as ISO-8601 string — use first one as createdAt
                    if (createdAt == 0L) {
                        String ts = JsonParser.getString(obj, "timestamp");
                        if (ts != null) {
                            try {
                                createdAt = java.time.Instant.parse(ts).toEpochMilli();
                            } catch (Exception ignored) {}
                        }
                    }
                    // CLI-generated summary entries: {"type":"summary","summary":"…","leafUuid":"…"}
                    // Prefer these over the first-user-message fallback. Last one wins.
                    if ("summary".equals(type)) {
                        String s = JsonParser.getString(obj, "summary");
                        if (s != null && !s.isEmpty()) {
                            cliSummary = s;
                        }
                        continue;
                    }
                    // First user message → fallback summary (cleaned of noise prefixes)
                    if (firstUserSummary == null && "user".equals(type)) {
                        Map<String, Object> msg = JsonParser.getMap(obj, "message");
                        if (msg != null) {
                            Object content = msg.get("content");
                            String text = cleanForSummary(extractUserText(content));
                            if (text != null && !text.isEmpty()) {
                                firstUserSummary = text.length() > 60 ? text.substring(0, 57) + "..." : text;
                            }
                        }
                    }
                    if ("user".equals(type) || "assistant".equals(type)) {
                        messageCount++;
                    }
                    if (model == null) {
                        Map<String, Object> msg = JsonParser.getMap(obj, "message");
                        if (msg != null) {
                            String m = JsonParser.getString(msg, "model");
                            if (m != null) model = m;
                        }
                    }
                } catch (Exception lineEx) {
                    // Skip malformed line
                }
            }
        } catch (Exception e) {
            return null;
        }

        if (messageCount == 0) return null; // not really a conversation

        // Prefer the CLI's auto-generated summary (a tight LLM-written title);
        // fall back to the first user message if the CLI hasn't written one yet.
        String summary = (cliSummary != null && !cliSummary.isEmpty())
                ? (cliSummary.length() > 60 ? cliSummary.substring(0, 57) + "..." : cliSummary)
                : firstUserSummary;
        info.setSummary(summary);
        info.setMessageCount(messageCount);
        info.setModel(model);
        if (createdAt > 0) info.setStartTime(createdAt);
        return info;
    }

    /**
     * Cleans noise off the start of a user message before it becomes a
     * Session History summary. Two prefixes are stripped:
     * <ul>
     *   <li>{@code <file path="…">…</file>} XML blocks the plugin prepends
     *       to the CLI text (active-file pin, @-mentions).</li>
     *   <li>{@code [Active editor context: …]} blocks the Claude CLI itself
     *       sometimes prepends to user messages when an IDE editor context
     *       is present.</li>
     * </ul>
     * Idempotent on already-clean text. Returns the trimmed remainder.
     */
    public static String cleanForSummary(String s) {
        if (s == null || s.isEmpty()) return s;
        // Strip leading <file …>…</file> blocks (one or more, with optional whitespace).
        s = s.replaceAll("(?is)^(?:\\s*<file\\s+path=\"[^\"]*\"\\s*>.*?</file>\\s*)+", "");
        // Strip leading [Active editor context: …] block from the CLI.
        s = s.replaceAll("(?is)^\\s*\\[Active editor context:[^\\]]*\\]\\s*", "");
        return s.trim();
    }

    @SuppressWarnings("unchecked")
    private static String extractUserText(Object content) {
        if (content instanceof String) return (String) content;
        if (content instanceof List) {
            StringBuilder sb = new StringBuilder();
            for (Object part : (List<Object>) content) {
                if (part instanceof Map) {
                    Map<String, Object> p = (Map<String, Object>) part;
                    Object t = p.get("type");
                    if ("text".equals(t)) {
                        Object txt = p.get("text");
                        if (txt != null) sb.append(txt.toString());
                    }
                }
            }
            return sb.toString();
        }
        return null;
    }

    /**
     * Convert project path like {@code C:\dev\foo} to {@code C--dev-foo}
     * (matches Claude CLI's encoding).
     */
    private static String encodeProjectKey(String path) {
        return path.replace(':', '-').replace('\\', '-').replace('/', '-');
    }

    private static boolean isUuid(String s) {
        if (s == null || s.length() != 36) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (i == 8 || i == 13 || i == 18 || i == 23) {
                if (c != '-') return false;
            } else if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return true;
    }
}
