package com.anthropic.claude.intellij.ui.dialogs;

import com.anthropic.claude.intellij.util.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;

/**
 * Dialog for managing Claude Code hooks.
 * Hooks run before/after tool calls and on session events.
 * Uses native IntelliJ DialogWrapper.
 */
public class HooksDialog extends DialogWrapper {

    private static final Logger LOG = Logger.getInstance(HooksDialog.class);

    private static final String[] EVENT_TYPES = {
        "PreToolUse", "PostToolUse", "SessionStart", "Stop"
    };

    private final String projectDir;

    private JBTable hooksTable;
    private DefaultTableModel hooksModel;
    private JComboBox<String> eventTypeCombo;
    private JTextField matcherField;
    private JTextField commandField;
    private JComboBox<String> settingsFileCombo;

    private final Path settingsPath;
    private final Path settingsLocalPath;

    public HooksDialog(Project project, String projectDir) {
        super(project, true);
        this.projectDir = projectDir != null ? projectDir : System.getProperty("user.home");
        this.settingsPath = Paths.get(this.projectDir, ".claude", "settings.json");
        this.settingsLocalPath = Paths.get(this.projectDir, ".claude", "settings.local.json");

        setTitle("Claude Code - Hooks Management");
        setSize(850, 550);
        init();
    }

    @Override
    protected JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));

        // Description
        JLabel desc = new JLabel("<html>Configure hooks that run before/after tool calls and on session events.<br>"
            + "Hooks execute shell commands when triggered.</html>");
        panel.add(desc, BorderLayout.NORTH);

        // Table
        hooksModel = new DefaultTableModel(new String[]{"Event", "Matcher", "Command", "Source"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        hooksTable = new JBTable(hooksModel);
        hooksTable.getColumnModel().getColumn(0).setPreferredWidth(120);
        hooksTable.getColumnModel().getColumn(1).setPreferredWidth(140);
        hooksTable.getColumnModel().getColumn(2).setPreferredWidth(350);
        hooksTable.getColumnModel().getColumn(3).setPreferredWidth(160);

        panel.add(new JBScrollPane(hooksTable), BorderLayout.CENTER);

        // Add controls
        JPanel addRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));

        settingsFileCombo = new JComboBox<>(new String[]{"settings.local.json", "settings.json"});
        eventTypeCombo = new JComboBox<>(EVENT_TYPES);

        matcherField = new JTextField(12);
        matcherField.setToolTipText("Matcher (optional, e.g. Bash|Edit)");

        commandField = new JTextField(25);
        commandField.setToolTipText("Shell command");
        commandField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        commandField.addActionListener(e -> addHook());

        JButton addBtn = new JButton("Add");
        addBtn.addActionListener(e -> addHook());

        JButton removeBtn = new JButton("Remove");
        removeBtn.addActionListener(e -> removeSelectedHooks());

        addRow.add(settingsFileCombo);
        addRow.add(eventTypeCombo);
        addRow.add(matcherField);
        addRow.add(commandField);
        addRow.add(addBtn);
        addRow.add(removeBtn);

        panel.add(addRow, BorderLayout.SOUTH);

        loadHooks();
        return panel;
    }

    // ==================== Load ====================

    private void loadHooks() {
        hooksModel.setRowCount(0);
        loadHooksFromFile(settingsPath, "settings.json");
        loadHooksFromFile(settingsLocalPath, "settings.local.json");
    }

    @SuppressWarnings("unchecked")
    private void loadHooksFromFile(Path path, String displayName) {
        if (!Files.exists(path)) return;
        try {
            String json = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            Map<String, Object> root = JsonParser.parseObject(json);
            Map<String, Object> hooks = JsonParser.getMap(root, "hooks");
            if (hooks == null) return;

            for (String eventType : EVENT_TYPES) {
                List<Object> hookList = JsonParser.getList(hooks, eventType);
                if (hookList == null) continue;
                for (Object hookObj : hookList) {
                    if (hookObj instanceof Map) {
                        Map<String, Object> hook = (Map<String, Object>) hookObj;
                        String command = JsonParser.getString(hook, "command", "");
                        String matcher = JsonParser.getString(hook, "matcher", "");
                        hooksModel.addRow(new Object[]{eventType, matcher, command, displayName});
                    }
                }
            }
        } catch (Exception e) {
            LOG.error("[HooksDialog] Failed to parse " + path + ": " + e.getMessage(), e);
        }
    }

    // ==================== Actions ====================

    private void addHook() {
        String command = commandField.getText().trim();
        if (command.isEmpty()) return;

        String event = (String) eventTypeCombo.getSelectedItem();
        String matcher = matcherField.getText().trim();
        String file = (String) settingsFileCombo.getSelectedItem();

        hooksModel.addRow(new Object[]{event, matcher, command, file});
        commandField.setText("");
        matcherField.setText("");
        commandField.requestFocusInWindow();
    }

    private void removeSelectedHooks() {
        int[] rows = hooksTable.getSelectedRows();
        for (int i = rows.length - 1; i >= 0; i--) {
            hooksModel.removeRow(rows[i]);
        }
    }

    // ==================== Save ====================

    private void saveAllHooks() {
        Map<String, Map<String, List<Map<String, Object>>>> fileMap = new LinkedHashMap<>();
        fileMap.put("settings.json", new LinkedHashMap<>());
        fileMap.put("settings.local.json", new LinkedHashMap<>());

        for (int i = 0; i < hooksModel.getRowCount(); i++) {
            String event = (String) hooksModel.getValueAt(i, 0);
            String matcher = (String) hooksModel.getValueAt(i, 1);
            String command = (String) hooksModel.getValueAt(i, 2);
            String source = (String) hooksModel.getValueAt(i, 3);

            Map<String, List<Map<String, Object>>> hooks = fileMap.get(source);
            if (hooks == null) continue;

            hooks.computeIfAbsent(event, k -> new ArrayList<>());
            Map<String, Object> hookEntry = new LinkedHashMap<>();
            hookEntry.put("type", "command");
            hookEntry.put("command", command);
            if (!matcher.isEmpty()) {
                hookEntry.put("matcher", matcher);
            }
            hooks.get(event).add(hookEntry);
        }

        saveHooksToFile(settingsPath, fileMap.get("settings.json"));
        saveHooksToFile(settingsLocalPath, fileMap.get("settings.local.json"));
    }

    @SuppressWarnings("unchecked")
    private void saveHooksToFile(Path path, Map<String, List<Map<String, Object>>> hooks) {
        try {
            Map<String, Object> root;
            if (Files.exists(path)) {
                String existing = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                root = JsonParser.parseObject(existing);
            } else {
                root = new LinkedHashMap<>();
            }

            if (hooks == null || hooks.isEmpty()) {
                root.remove("hooks");
            } else {
                Map<String, Object> hooksMap = new LinkedHashMap<>();
                for (Map.Entry<String, List<Map<String, Object>>> entry : hooks.entrySet()) {
                    hooksMap.put(entry.getKey(), new ArrayList<>(entry.getValue()));
                }
                root.put("hooks", hooksMap);
            }

            if (root.isEmpty() && !Files.exists(path)) {
                return;
            }

            Files.createDirectories(path.getParent());
            String json = JsonParser.prettyPrint(JsonParser.toJson(root));
            Files.write(path, json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            LOG.error("Failed to save " + path + ": " + e.getMessage(), e);
        }
    }

    // ==================== Buttons ====================

    @Override
    protected Action[] createActions() {
        return new Action[]{getOKAction(), getCancelAction()};
    }

    @Override
    protected void doOKAction() {
        saveAllHooks();
        super.doOKAction();
    }
}
