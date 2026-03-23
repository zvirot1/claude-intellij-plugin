package com.anthropic.claude.intellij.settings;

import com.anthropic.claude.intellij.util.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Writes Claude CLI settings files with proper JSON pretty-printing.
 * <p>
 * Supports writing to:
 * <ul>
 *     <li>{@code ~/.claude/settings.json} — global user-level settings</li>
 *     <li>{@code <project>/.claude/settings.json} — project-level settings</li>
 *     <li>{@code <project>/.claude/settings.local.json} — local overrides (gitignored)</li>
 * </ul>
 */
public class ClaudeSettingsWriter {

    private static final Logger LOG = Logger.getInstance(ClaudeSettingsWriter.class);

    private final Project project;

    public ClaudeSettingsWriter(Project project) {
        this.project = project;
    }

    /**
     * Reads, modifies, and writes the project-local settings file
     * ({@code <project>/.claude/settings.local.json}).
     *
     * @param path  dot-separated key path (e.g., "permissions.allow")
     * @param value the value to set at the path
     */
    public void setLocalSetting(String path, Object value) {
        String projectPath = project.getBasePath();
        if (projectPath == null) return;
        Path settingsPath = Paths.get(projectPath, ".claude", "settings.local.json");
        writeSetting(settingsPath, path, value);
    }

    /**
     * Reads, modifies, and writes the global user settings file
     * ({@code ~/.claude/settings.json}).
     *
     * @param path  dot-separated key path (e.g., "preferences.theme")
     * @param value the value to set at the path
     */
    public void setGlobalSetting(String path, Object value) {
        String home = System.getProperty("user.home");
        if (home == null) return;
        Path settingsPath = Paths.get(home, ".claude", "settings.json");
        writeSetting(settingsPath, path, value);
    }

    /**
     * Adds an item to an array in the project-local settings file.
     * Creates the array if it doesn't exist. Skips if item already present.
     *
     * @param arrayPath dot-separated path to the array (e.g., "permissions.allow")
     * @param item      the item to add
     */
    @SuppressWarnings("unchecked")
    public void addToLocalArray(String arrayPath, Object item) {
        String projectPath = project.getBasePath();
        if (projectPath == null) return;
        Path settingsPath = Paths.get(projectPath, ".claude", "settings.local.json");

        try {
            Map<String, Object> root = readOrCreate(settingsPath);
            String[] parts = arrayPath.split("\\.");

            // Navigate/create nested maps
            Map<String, Object> current = root;
            for (int i = 0; i < parts.length - 1; i++) {
                Object next = current.get(parts[i]);
                if (!(next instanceof Map)) {
                    next = new LinkedHashMap<>();
                    current.put(parts[i], next);
                }
                current = (Map<String, Object>) next;
            }

            // Get or create array at final key
            String lastKey = parts[parts.length - 1];
            Object existing = current.get(lastKey);
            List<Object> list;
            if (existing instanceof List) {
                list = (List<Object>) existing;
            } else {
                list = new ArrayList<>();
                current.put(lastKey, list);
            }

            // Add if not already present
            if (!list.contains(item)) {
                list.add(item);
                writeJson(settingsPath, root);
                LOG.info("Added '" + item + "' to " + arrayPath);
            }
        } catch (Exception e) {
            LOG.error("Failed to add to array: " + arrayPath, e);
        }
    }

    /**
     * Removes an item from an array in the project-local settings file.
     *
     * @param arrayPath dot-separated path to the array (e.g., "permissions.allow")
     * @param item      the item to remove
     */
    @SuppressWarnings("unchecked")
    public void removeFromLocalArray(String arrayPath, Object item) {
        String projectPath = project.getBasePath();
        if (projectPath == null) return;
        Path settingsPath = Paths.get(projectPath, ".claude", "settings.local.json");

        try {
            Map<String, Object> root = readOrCreate(settingsPath);
            String[] parts = arrayPath.split("\\.");

            Map<String, Object> current = root;
            for (int i = 0; i < parts.length - 1; i++) {
                Object next = current.get(parts[i]);
                if (!(next instanceof Map)) return;
                current = (Map<String, Object>) next;
            }

            String lastKey = parts[parts.length - 1];
            Object existing = current.get(lastKey);
            if (existing instanceof List) {
                List<Object> list = (List<Object>) existing;
                if (list.remove(item)) {
                    writeJson(settingsPath, root);
                    LOG.info("Removed '" + item + "' from " + arrayPath);
                }
            }
        } catch (Exception e) {
            LOG.error("Failed to remove from array: " + arrayPath, e);
        }
    }

    // ==================== Internal ====================

    @SuppressWarnings("unchecked")
    private void writeSetting(Path settingsPath, String dotPath, Object value) {
        try {
            Map<String, Object> root = readOrCreate(settingsPath);
            String[] parts = dotPath.split("\\.");

            // Navigate/create nested maps
            Map<String, Object> current = root;
            for (int i = 0; i < parts.length - 1; i++) {
                Object next = current.get(parts[i]);
                if (!(next instanceof Map)) {
                    next = new LinkedHashMap<>();
                    current.put(parts[i], next);
                }
                current = (Map<String, Object>) next;
            }

            // Set value
            current.put(parts[parts.length - 1], value);
            writeJson(settingsPath, root);
            LOG.info("Wrote setting: " + dotPath + " = " + value);
        } catch (Exception e) {
            LOG.error("Failed to write setting: " + dotPath, e);
        }
    }

    private Map<String, Object> readOrCreate(Path path) {
        if (Files.exists(path)) {
            try {
                String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                Map<String, Object> parsed = JsonParser.parseObject(content);
                return parsed != null ? parsed : new LinkedHashMap<>();
            } catch (Exception e) {
                LOG.warn("Failed to read settings, creating new: " + path, e);
            }
        }
        return new LinkedHashMap<>();
    }

    private void writeJson(Path path, Map<String, Object> data) throws Exception {
        Files.createDirectories(path.getParent());
        String json = JsonParser.prettyPrint(JsonParser.toJson(data));
        Files.write(path, json.getBytes(StandardCharsets.UTF_8));
    }
}
