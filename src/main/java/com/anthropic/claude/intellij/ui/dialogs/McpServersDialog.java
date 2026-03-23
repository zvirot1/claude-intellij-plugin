package com.anthropic.claude.intellij.ui.dialogs;

import com.anthropic.claude.intellij.util.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
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
 * Dialog for managing MCP (Model Context Protocol) server configurations.
 * Supports project-level (.mcp.json) and global (~/.claude.json) servers.
 * Uses native IntelliJ DialogWrapper.
 */
public class McpServersDialog extends DialogWrapper {

    private static final Logger LOG = Logger.getInstance(McpServersDialog.class);

    private final String projectDir;

    private JBTable projectTable;
    private DefaultTableModel projectModel;
    private JBTable globalTable;
    private DefaultTableModel globalModel;

    private final Path projectMcpPath;
    private final Path globalMcpPath;

    public McpServersDialog(Project project, String projectDir) {
        super(project, true);
        this.projectDir = projectDir != null ? projectDir : System.getProperty("user.home");

        this.projectMcpPath = Paths.get(this.projectDir, ".mcp.json");
        this.globalMcpPath = Paths.get(System.getProperty("user.home"), ".claude.json");

        setTitle("Claude Code - MCP Servers");
        setSize(850, 600);
        init();
    }

    @Override
    protected JComponent createCenterPanel() {
        JBTabbedPane tabbedPane = new JBTabbedPane();

        // Tab 1: Project servers
        projectModel = createServerTableModel();
        projectTable = new JBTable(projectModel);
        configureTable(projectTable);
        tabbedPane.addTab("Project Servers (.mcp.json)", createServerTab(projectTable, projectModel, projectMcpPath));

        // Tab 2: Global servers
        globalModel = createServerTableModel();
        globalTable = new JBTable(globalModel);
        configureTable(globalTable);
        tabbedPane.addTab("Global Servers (~/.claude.json)", createServerTab(globalTable, globalModel, globalMcpPath));

        loadServers();
        return tabbedPane;
    }

    private DefaultTableModel createServerTableModel() {
        return new DefaultTableModel(new String[]{"Name", "Command", "Args", "Env"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    }

    private void configureTable(JBTable table) {
        table.getColumnModel().getColumn(0).setPreferredWidth(140);
        table.getColumnModel().getColumn(1).setPreferredWidth(160);
        table.getColumnModel().getColumn(2).setPreferredWidth(300);
        table.getColumnModel().getColumn(3).setPreferredWidth(150);
    }

    private JPanel createServerTab(JBTable table, DefaultTableModel model, Path filePath) {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JLabel pathLabel = new JLabel("File: " + filePath.toString());
        pathLabel.setFont(pathLabel.getFont().deriveFont(Font.PLAIN, 11f));
        panel.add(pathLabel, BorderLayout.NORTH);

        panel.add(new JBScrollPane(table), BorderLayout.CENTER);

        // Buttons row
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));

        JButton addBtn = new JButton("Add Server");
        addBtn.addActionListener(e -> addServer(model));

        JButton editBtn = new JButton("Edit");
        editBtn.addActionListener(e -> editServer(table, model));

        JButton removeBtn = new JButton("Remove");
        removeBtn.addActionListener(e -> removeServer(table, model));

        btnRow.add(addBtn);
        btnRow.add(editBtn);
        btnRow.add(removeBtn);

        panel.add(btnRow, BorderLayout.SOUTH);
        return panel;
    }

    // ==================== Load ====================

    private void loadServers() {
        loadServersFromFile(projectMcpPath, projectModel);
        loadServersFromFile(globalMcpPath, globalModel);
    }

    @SuppressWarnings("unchecked")
    private void loadServersFromFile(Path path, DefaultTableModel model) {
        model.setRowCount(0);
        if (!Files.exists(path)) return;
        try {
            String json = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            Map<String, Object> root = JsonParser.parseObject(json);
            Map<String, Object> servers = JsonParser.getMap(root, "mcpServers");
            if (servers == null) return;

            for (Map.Entry<String, Object> entry : servers.entrySet()) {
                if (!(entry.getValue() instanceof Map)) continue;
                Map<String, Object> srv = (Map<String, Object>) entry.getValue();

                String name = entry.getKey();
                String command = JsonParser.getString(srv, "command", "");
                List<Object> argsList = JsonParser.getList(srv, "args");
                String args = argsList != null ? String.join(" ", toStringList(argsList)) : "";
                Map<String, Object> envMap = JsonParser.getMap(srv, "env");
                String env = envMap != null ? formatEnvMap(envMap) : "";

                model.addRow(new Object[]{name, command, args, env});
            }
        } catch (Exception e) {
            LOG.error("[McpServersDialog] Failed to parse " + path + ": " + e.getMessage(), e);
        }
    }

    // ==================== Actions ====================

    private void addServer(DefaultTableModel model) {
        McpServerEditDialog dialog = new McpServerEditDialog(null, "", "", "", "");
        if (dialog.showAndGet()) {
            model.addRow(new Object[]{
                dialog.getServerName(),
                dialog.getCommand(),
                dialog.getArgs(),
                dialog.getEnv()
            });
        }
    }

    private void editServer(JBTable table, DefaultTableModel model) {
        int row = table.getSelectedRow();
        if (row < 0) return;

        String name = (String) model.getValueAt(row, 0);
        String command = (String) model.getValueAt(row, 1);
        String args = (String) model.getValueAt(row, 2);
        String env = (String) model.getValueAt(row, 3);

        McpServerEditDialog dialog = new McpServerEditDialog(null, name, command, args, env);
        if (dialog.showAndGet()) {
            model.setValueAt(dialog.getServerName(), row, 0);
            model.setValueAt(dialog.getCommand(), row, 1);
            model.setValueAt(dialog.getArgs(), row, 2);
            model.setValueAt(dialog.getEnv(), row, 3);
        }
    }

    private void removeServer(JBTable table, DefaultTableModel model) {
        int[] rows = table.getSelectedRows();
        for (int i = rows.length - 1; i >= 0; i--) {
            model.removeRow(rows[i]);
        }
    }

    // ==================== Save ====================

    private void saveAllServers() {
        saveServersToFile(projectMcpPath, projectModel);
        saveServersToFile(globalMcpPath, globalModel);
    }

    @SuppressWarnings("unchecked")
    private void saveServersToFile(Path path, DefaultTableModel model) {
        try {
            Map<String, Object> root;
            if (Files.exists(path)) {
                String existing = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                root = JsonParser.parseObject(existing);
            } else {
                root = new LinkedHashMap<>();
            }

            if (model.getRowCount() == 0) {
                root.remove("mcpServers");
            } else {
                Map<String, Object> servers = new LinkedHashMap<>();
                for (int i = 0; i < model.getRowCount(); i++) {
                    String name = (String) model.getValueAt(i, 0);
                    String command = (String) model.getValueAt(i, 1);
                    String args = (String) model.getValueAt(i, 2);
                    String env = (String) model.getValueAt(i, 3);

                    Map<String, Object> srv = new LinkedHashMap<>();
                    srv.put("command", command);

                    if (!args.isEmpty()) {
                        List<Object> argsList = new ArrayList<>();
                        for (String arg : args.split("\\s+")) {
                            if (!arg.isEmpty()) argsList.add(arg);
                        }
                        srv.put("args", argsList);
                    }

                    if (!env.isEmpty()) {
                        Map<String, Object> envMap = parseEnvString(env);
                        if (!envMap.isEmpty()) {
                            srv.put("env", envMap);
                        }
                    }

                    servers.put(name, srv);
                }
                root.put("mcpServers", servers);
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

    // ==================== Helpers ====================

    private static List<String> toStringList(List<Object> list) {
        List<String> result = new ArrayList<>();
        for (Object o : list) {
            if (o != null) result.add(o.toString());
        }
        return result;
    }

    private static String formatEnvMap(Map<String, Object> env) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : env.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getKey()).append("=").append(e.getValue());
        }
        return sb.toString();
    }

    private static Map<String, Object> parseEnvString(String env) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String pair : env.split(",\\s*")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                result.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
        return result;
    }

    // ==================== Buttons ====================

    @Override
    protected Action[] createActions() {
        return new Action[]{getOKAction(), getCancelAction()};
    }

    @Override
    protected void doOKAction() {
        saveAllServers();
        super.doOKAction();
    }

    // ==================== Inner Edit Dialog ====================

    /**
     * Inner dialog for adding/editing a single MCP server.
     */
    private static class McpServerEditDialog extends DialogWrapper {

        private JTextField nameField;
        private JTextField commandField;
        private JTextField argsField;
        private JTextField envField;

        McpServerEditDialog(Project project, String name, String command, String args, String env) {
            super(project, false);
            setTitle("MCP Server Configuration");
            init();

            nameField.setText(name);
            commandField.setText(command);
            argsField.setText(args);
            envField.setText(env);
        }

        @Override
        protected JComponent createCenterPanel() {
            JPanel panel = new JPanel(new GridBagLayout());
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(4, 4, 4, 4);
            gbc.anchor = GridBagConstraints.WEST;

            nameField = new JTextField(30);
            commandField = new JTextField(30);
            argsField = new JTextField(30);
            envField = new JTextField(30);

            gbc.gridx = 0; gbc.gridy = 0;
            panel.add(new JLabel("Name:"), gbc);
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1;
            panel.add(nameField, gbc);

            gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
            panel.add(new JLabel("Command:"), gbc);
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1;
            panel.add(commandField, gbc);

            gbc.gridx = 0; gbc.gridy = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
            panel.add(new JLabel("Args:"), gbc);
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1;
            panel.add(argsField, gbc);

            gbc.gridx = 0; gbc.gridy = 3; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
            panel.add(new JLabel("Env (KEY=val,...):"), gbc);
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1;
            panel.add(envField, gbc);

            return panel;
        }

        String getServerName() { return nameField.getText().trim(); }
        String getCommand() { return commandField.getText().trim(); }
        String getArgs() { return argsField.getText().trim(); }
        String getEnv() { return envField.getText().trim(); }
    }
}
