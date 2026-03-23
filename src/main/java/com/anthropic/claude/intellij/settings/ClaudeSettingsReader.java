package com.anthropic.claude.intellij.settings;

import com.anthropic.claude.intellij.util.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads and merges Claude CLI settings files from multiple locations.
 * <p>
 * Settings are loaded from (in order of increasing priority):
 * <ol>
 *     <li>{@code ~/.claude/settings.json} — global user-level settings</li>
 *     <li>{@code <project>/.claude/settings.json} — project-level settings</li>
 *     <li>{@code <project>/.claude/settings.local.json} — local overrides (gitignored)</li>
 * </ol>
 * Later sources override earlier ones for the same key.
 */
public class ClaudeSettingsReader {

    private static final Logger LOG = Logger.getInstance(ClaudeSettingsReader.class);

    private final Project project;

    public ClaudeSettingsReader(Project project) {
        this.project = project;
    }

    /**
     * Loads and merges all settings sources.
     * Returns a merged Map with all settings, later sources overriding earlier ones.
     */
    public Map<String, Object> loadMergedSettings() {
        Map<String, Object> merged = new LinkedHashMap<>();

        // 1. Global user settings
        Map<String, Object> userSettings = loadUserSettings();
        if (userSettings != null) {
            deepMerge(merged, userSettings);
        }

        // 2. Project settings
        if (project.getBasePath() != null) {
            Map<String, Object> projectSettings = loadJsonFile(
                Paths.get(project.getBasePath(), ".claude", "settings.json"));
            if (projectSettings != null) {
                deepMerge(merged, projectSettings);
            }

            // 3. Project local settings (highest priority)
            Map<String, Object> localSettings = loadJsonFile(
                Paths.get(project.getBasePath(), ".claude", "settings.local.json"));
            if (localSettings != null) {
                deepMerge(merged, localSettings);
            }
        }

        return merged;
    }

    /**
     * Loads the global user-level settings from ~/.claude/settings.json.
     */
    public Map<String, Object> loadUserSettings() {
        String home = System.getProperty("user.home");
        if (home == null) return null;
        return loadJsonFile(Paths.get(home, ".claude", "settings.json"));
    }

    /**
     * Gets a string setting value from the merged settings.
     */
    public String getUserSetting(String key) {
        Map<String, Object> settings = loadMergedSettings();
        Object value = settings.get(key);
        return value != null ? value.toString() : null;
    }

    /**
     * Gets a boolean setting value from the merged settings.
     */
    public boolean getUserSettingBoolean(String key, boolean defaultValue) {
        Map<String, Object> settings = loadMergedSettings();
        Object value = settings.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return defaultValue;
    }

    /**
     * Gets a nested setting value using dot-separated path (e.g., "permissions.allow").
     */
    @SuppressWarnings("unchecked")
    public Object getNestedSetting(String dotPath) {
        Map<String, Object> settings = loadMergedSettings();
        String[] parts = dotPath.split("\\.");
        Object current = settings;
        for (String part : parts) {
            if (current instanceof Map) {
                current = ((Map<String, Object>) current).get(part);
            } else {
                return null;
            }
        }
        return current;
    }

    // ==================== Internal ====================

    private Map<String, Object> loadJsonFile(Path path) {
        if (!Files.exists(path)) return null;
        try {
            String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            return JsonParser.parseObject(content);
        } catch (IOException e) {
            LOG.warn("Failed to read settings file: " + path, e);
            return null;
        } catch (Exception e) {
            LOG.warn("Failed to parse settings file: " + path, e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private void deepMerge(Map<String, Object> target, Map<String, Object> source) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            Object srcValue = entry.getValue();
            Object tgtValue = target.get(key);

            if (srcValue instanceof Map && tgtValue instanceof Map) {
                deepMerge((Map<String, Object>) tgtValue, (Map<String, Object>) srcValue);
            } else {
                target.put(key, srcValue);
            }
        }
    }
}
