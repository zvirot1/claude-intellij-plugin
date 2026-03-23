package com.anthropic.claude.intellij.ui.dialogs;

import com.anthropic.claude.intellij.util.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.components.JBTextArea;
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
 * Dialog for managing Claude Code rules and permissions.
 * Provides tabs for editing CLAUDE.md files and managing permission rules.
 * Uses native IntelliJ DialogWrapper.
 */
public class RulesDialog extends DialogWrapper {

    private static final Logger LOG = Logger.getInstance(RulesDialog.class);

    private final String projectDir;

    // Text editors for markdown rules
    private JBTextArea projectRulesText;
    private JBTextArea localRulesText;
    private JBTextArea globalRulesText;

    // Permissions tab widgets
    private JBTable permissionsTable;
    private DefaultTableModel permissionsModel;
    private JComboBox<String> permissionTypeCombo;
    private JTextField newPermissionField;
    private JComboBox<String> settingsFileCombo;

    // File paths
    private final Path projectRulesPath;
    private final Path localRulesPath;
    private final Path globalRulesPath;
    private final Path settingsPath;
    private final Path settingsLocalPath;

    public RulesDialog(Project project, String projectDir) {
        super(project, true);
        this.projectDir = projectDir != null ? projectDir : System.getProperty("user.home");

        this.projectRulesPath = Paths.get(this.projectDir, "CLAUDE.md");
        this.localRulesPath = Paths.get(this.projectDir, ".claude.local.md");
        this.globalRulesPath = Paths.get(System.getProperty("user.home"), ".claude", "CLAUDE.md");
        this.settingsPath = Paths.get(this.projectDir, ".claude", "settings.json");
        this.settingsLocalPath = Paths.get(this.projectDir, ".claude", "settings.local.json");

        setTitle("Claude Code - Rules Management");
        setSize(800, 650);
        init();
    }

    @Override
    protected JComponent createCenterPanel() {
        JBTabbedPane tabbedPane = new JBTabbedPane();

        // Tab 1: Project Rules (CLAUDE.md)
        projectRulesText = new JBTextArea();
        tabbedPane.addTab("Project Rules (CLAUDE.md)", createMarkdownTab(projectRulesPath, projectRulesText));

        // Tab 2: Local Rules (.claude.local.md)
        localRulesText = new JBTextArea();
        tabbedPane.addTab("Local Rules (.claude.local.md)", createMarkdownTab(localRulesPath, localRulesText));

        // Tab 3: Global Rules (~/.claude/CLAUDE.md)
        globalRulesText = new JBTextArea();
        tabbedPane.addTab("Global Rules (~/.claude/CLAUDE.md)", createMarkdownTab(globalRulesPath, globalRulesText));

        // Tab 4: Permissions
        tabbedPane.addTab("Permissions", createPermissionsTab());

        // Load content
        loadAllContent();

        return tabbedPane;
    }

    private JPanel createMarkdownTab(Path filePath, JBTextArea textArea) {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // File path label
        JLabel pathLabel = new JLabel("File: " + filePath.toString());
        pathLabel.setFont(pathLabel.getFont().deriveFont(Font.PLAIN, 11f));

        // Status label
        boolean exists = Files.exists(filePath);
        JLabel statusLabel = new JLabel(exists ? "\u2713 File exists" : "\u26A0 Will be created on save");
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.ITALIC, 11f));

        JPanel topPanel = new JPanel(new GridLayout(2, 1, 0, 2));
        topPanel.add(pathLabel);
        topPanel.add(statusLabel);

        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);

        panel.add(topPanel, BorderLayout.NORTH);
        panel.add(new JBScrollPane(textArea), BorderLayout.CENTER);

        return panel;
    }

    private JPanel createPermissionsTab() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // Description
        JLabel desc = new JLabel("<html>Permission rules from .claude/settings.json and .claude/settings.local.json.<br>"
            + "These control which tools Claude can use without asking.</html>");
        desc.setFont(desc.getFont().deriveFont(Font.PLAIN, 12f));
        panel.add(desc, BorderLayout.NORTH);

        // Table
        permissionsModel = new DefaultTableModel(new String[]{"Type", "Rule Pattern", "Source"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        permissionsTable = new JBTable(permissionsModel);
        permissionsTable.getColumnModel().getColumn(0).setPreferredWidth(80);
        permissionsTable.getColumnModel().getColumn(1).setPreferredWidth(420);
        permissionsTable.getColumnModel().getColumn(2).setPreferredWidth(170);

        panel.add(new JBScrollPane(permissionsTable), BorderLayout.CENTER);

        // Add controls row
        JPanel addRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));

        settingsFileCombo = new JComboBox<>(new String[]{"settings.local.json", "settings.json"});
        permissionTypeCombo = new JComboBox<>(new String[]{"allow", "deny", "ask"});

        newPermissionField = new JTextField(25);
        newPermissionField.setToolTipText("e.g., Bash(npm test)  or  Edit  or  Read");
        newPermissionField.addActionListener(e -> addPermission());

        JButton addBtn = new JButton("Add");
        addBtn.addActionListener(e -> addPermission());

        JButton removeBtn = new JButton("Remove");
        removeBtn.addActionListener(e -> removeSelectedPermissions());

        addRow.add(settingsFileCombo);
        addRow.add(permissionTypeCombo);
        addRow.add(newPermissionField);
        addRow.add(addBtn);
        addRow.add(removeBtn);

        panel.add(addRow, BorderLayout.SOUTH);

        return panel;
    }

    // ==================== Load Content ====================

    private void loadAllContent() {
        projectRulesText.setText(readFileOrEmpty(projectRulesPath));
        localRulesText.setText(readFileOrEmpty(localRulesPath));
        globalRulesText.setText(readFileOrEmpty(globalRulesPath));
        loadPermissions();
    }

    private String readFileOrEmpty(Path path) {
        try {
            if (Files.exists(path)) {
                return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            LOG.error("[RulesDialog] Failed to read " + path + ": " + e.getMessage(), e);
        }
        return "";
    }

    private void loadPermissions() {
        permissionsModel.setRowCount(0);
        loadPermissionsFromFile(settingsPath, "settings.json");
        loadPermissionsFromFile(settingsLocalPath, "settings.local.json");
    }

    @SuppressWarnings("unchecked")
    private void loadPermissionsFromFile(Path path, String displayName) {
        if (!Files.exists(path)) return;
        try {
            String json = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            Map<String, Object> root = JsonParser.parseObject(json);
            Map<String, Object> permissions = JsonParser.getMap(root, "permissions");
            if (permissions == null) return;

            for (String type : new String[]{"allow", "deny", "ask"}) {
                List<Object> rules = JsonParser.getList(permissions, type);
                if (rules == null) continue;
                for (Object rule : rules) {
                    permissionsModel.addRow(new Object[]{type, rule.toString(), displayName});
                }
            }
        } catch (Exception e) {
            LOG.error("[RulesDialog] Failed to parse " + path + ": " + e.getMessage(), e);
        }
    }

    // ==================== Permission Actions ====================

    private void addPermission() {
        String pattern = newPermissionField.getText().trim();
        if (pattern.isEmpty()) return;

        String type = (String) permissionTypeCombo.getSelectedItem();
        String file = (String) settingsFileCombo.getSelectedItem();

        permissionsModel.addRow(new Object[]{type, pattern, file});
        newPermissionField.setText("");
        newPermissionField.requestFocusInWindow();
    }

    private void removeSelectedPermissions() {
        int[] rows = permissionsTable.getSelectedRows();
        for (int i = rows.length - 1; i >= 0; i--) {
            permissionsModel.removeRow(rows[i]);
        }
    }

    // ==================== Save Content ====================

    private void saveAllContent() {
        saveTextToFile(projectRulesPath, projectRulesText.getText());
        saveTextToFile(localRulesPath, localRulesText.getText());
        saveTextToFile(globalRulesPath, globalRulesText.getText());
        savePermissions();
    }

    private void saveTextToFile(Path path, String content) {
        try {
            if (content.isEmpty() && !Files.exists(path)) {
                return;
            }
            if (content.isEmpty() && Files.exists(path)) {
                Files.delete(path);
                return;
            }
            Files.createDirectories(path.getParent());
            Files.write(path, content.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            LOG.error("Failed to save " + path + ": " + e.getMessage(), e);
        }
    }

    private void savePermissions() {
        // Group table items by source file
        Map<String, Map<String, List<String>>> fileMap = new LinkedHashMap<>();
        fileMap.put("settings.json", new LinkedHashMap<>());
        fileMap.put("settings.local.json", new LinkedHashMap<>());

        for (int i = 0; i < permissionsModel.getRowCount(); i++) {
            String type = (String) permissionsModel.getValueAt(i, 0);
            String pattern = (String) permissionsModel.getValueAt(i, 1);
            String source = (String) permissionsModel.getValueAt(i, 2);

            Map<String, List<String>> perms = fileMap.get(source);
            if (perms == null) continue;
            perms.computeIfAbsent(type, k -> new ArrayList<>()).add(pattern);
        }

        savePermissionsToFile(settingsPath, fileMap.get("settings.json"));
        savePermissionsToFile(settingsLocalPath, fileMap.get("settings.local.json"));
    }

    @SuppressWarnings("unchecked")
    private void savePermissionsToFile(Path path, Map<String, List<String>> permissions) {
        try {
            Map<String, Object> root;
            if (Files.exists(path)) {
                String existing = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                root = JsonParser.parseObject(existing);
            } else {
                root = new LinkedHashMap<>();
            }

            if (permissions == null || permissions.isEmpty()) {
                root.remove("permissions");
            } else {
                Map<String, Object> permsMap = new LinkedHashMap<>();
                for (Map.Entry<String, List<String>> entry : permissions.entrySet()) {
                    permsMap.put(entry.getKey(), new ArrayList<>(entry.getValue()));
                }
                root.put("permissions", permsMap);
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
        saveAllContent();
        super.doOKAction();
    }
}
