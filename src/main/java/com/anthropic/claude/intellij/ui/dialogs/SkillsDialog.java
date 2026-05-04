package com.anthropic.claude.intellij.ui.dialogs;

import com.anthropic.claude.intellij.util.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.table.JBTable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.stream.Stream;

/**
 * Dialog for browsing and managing Claude Code plugins/skills.
 * Three tabs matching Eclipse: Local Skills, Installed Plugins, Available Plugins.
 */
public class SkillsDialog extends DialogWrapper {

    private static final Logger LOG = Logger.getInstance(SkillsDialog.class);

    private final String projectDir;

    // Local Skills tab
    private JBTable skillsTable;
    private DefaultTableModel skillsModel;
    private JBTabbedPane skillDetailTabs;
    private JTextArea skillInfoArea;
    private JTextArea skillMdArea;
    private JTextArea skillLicenseArea;
    private JLabel skillsPathLabel;
    private final List<SkillInfo> loadedSkills = new ArrayList<>();

    /** Default skills folder when the user hasn't configured one in Settings. Matches Claude CLI. */
    private static Path defaultSkillsFolder() {
        return Paths.get(System.getProperty("user.home"), ".claude", "skills");
    }

    /** Effective skills folder = settings override (when non-empty) or the default. */
    private static Path effectiveSkillsFolder() {
        try {
            String configured = com.anthropic.claude.intellij.settings.ClaudeSettings
                .getInstance().getState().skillsFolder;
            if (configured != null && !configured.trim().isEmpty()) {
                return Paths.get(configured.trim());
            }
        } catch (Exception ignored) {}
        return defaultSkillsFolder();
    }

    // Installed Plugins tab
    private JBTable pluginTable;
    private DefaultTableModel pluginModel;
    private JBTabbedPane pluginDetailTabs;
    private JTextArea pluginInfoArea;
    private final List<PluginInfo> loadedPlugins = new ArrayList<>();

    // Available Plugins tab
    private JBTable availableTable;
    private DefaultTableModel availableModel;
    private TableRowSorter<DefaultTableModel> availableSorter;
    private JTextArea availableInfoArea;
    private JTextArea availableReadmeArea;
    private final List<AvailablePluginInfo> availablePlugins = new ArrayList<>();
    private final Set<String> installedPluginIds = new HashSet<>();

    public SkillsDialog(Project project, String projectDir) {
        super(project, true);
        this.projectDir = projectDir != null ? projectDir : System.getProperty("user.home");
        setTitle("Claude Code - Skills & Plugins");
        setSize(1000, 650);
        init();
    }

    @Override
    protected JComponent createCenterPanel() {
        JPanel headerPanel = new JPanel(new BorderLayout());
        JLabel title = new JLabel("Skills  Plugins");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));
        JLabel subtitle = new JLabel("Browse, enable, and manage Claude Code plugins and skills.");
        subtitle.setBorder(BorderFactory.createEmptyBorder(2, 0, 8, 0));
        headerPanel.add(title, BorderLayout.NORTH);
        headerPanel.add(subtitle, BorderLayout.SOUTH);

        JBTabbedPane mainTabs = new JBTabbedPane();
        mainTabs.addTab("Local Skills", buildLocalSkillsTab());
        mainTabs.addTab("Installed Plugins", buildInstalledPluginsTab());
        mainTabs.addTab("Available Plugins", buildAvailablePluginsTab());

        JPanel root = new JPanel(new BorderLayout(0, 6));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        root.add(headerPanel, BorderLayout.NORTH);
        root.add(mainTabs, BorderLayout.CENTER);

        loadSkills();
        loadPlugins();
        return root;
    }

    // ==================== Tab 1: Local Skills ====================

    private JPanel buildLocalSkillsTab() {
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setDividerLocation(420);

        // Left: skills table
        skillsModel = new DefaultTableModel(new String[]{"Skill", "Description"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        skillsTable = new JBTable(skillsModel);
        skillsTable.getColumnModel().getColumn(0).setPreferredWidth(150);
        skillsTable.getColumnModel().getColumn(1).setPreferredWidth(270);
        skillsTable.getSelectionModel().addListSelectionListener(this::onSkillSelected);

        JPanel leftPanel = new JPanel(new BorderLayout(0, 6));

        // Header label shows the current skills path verbatim (live-updates when changed via Browse).
        skillsPathLabel = new JLabel();
        skillsPathLabel.setFont(skillsPathLabel.getFont().deriveFont(Font.PLAIN, 11f));
        updateSkillsPathLabel();
        leftPanel.add(skillsPathLabel, BorderLayout.NORTH);
        leftPanel.add(new JBScrollPane(skillsTable), BorderLayout.CENTER);

        // Buttons
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton openFolderBtn = new JButton("Open Folder");
        openFolderBtn.setIcon(UIManager.getIcon("FileView.directoryIcon"));
        openFolderBtn.addActionListener(e -> openSkillFolder());
        JButton browseBtn = new JButton("Browse...");
        browseBtn.setToolTipText("Choose a different skills folder");
        browseBtn.addActionListener(e -> browseSkillsFolder());
        JButton refreshBtn = new JButton("Refresh");
        refreshBtn.addActionListener(e -> loadSkills());
        btnRow.add(openFolderBtn);
        btnRow.add(browseBtn);
        btnRow.add(refreshBtn);
        leftPanel.add(btnRow, BorderLayout.SOUTH);

        // Right: detail tabs
        JPanel rightPanel = new JPanel(new BorderLayout());
        skillDetailTabs = new JBTabbedPane();

        skillInfoArea = createReadOnlyTextArea();
        skillMdArea = createReadOnlyTextArea();
        skillLicenseArea = createReadOnlyTextArea();

        skillDetailTabs.addTab("Info", new JBScrollPane(skillInfoArea));
        skillDetailTabs.addTab("SKILL.md", new JBScrollPane(skillMdArea));
        skillDetailTabs.addTab("LICENSE.txt", new JBScrollPane(skillLicenseArea));

        rightPanel.add(skillDetailTabs, BorderLayout.CENTER);

        splitPane.setLeftComponent(leftPanel);
        splitPane.setRightComponent(rightPanel);
        return wrapInPanel(splitPane);
    }

    private void loadSkills() {
        loadedSkills.clear();
        skillsModel.setRowCount(0);
        if (skillsPathLabel != null) updateSkillsPathLabel();

        Path primary = effectiveSkillsFolder();
        scanSkillsDirectory(primary);

        // Always also scan the Claude CLI default if the user picked something else,
        // so existing skills aren't hidden after a custom path is set.
        Path fallback = defaultSkillsFolder();
        if (!primary.toAbsolutePath().equals(fallback.toAbsolutePath())) {
            scanSkillsDirectory(fallback);
        }

        if (loadedSkills.isEmpty()) {
            String fallbackLine = primary.toAbsolutePath().equals(fallback.toAbsolutePath())
                ? "" : "\n  " + fallback;
            skillInfoArea.setText("No local skills found.\n\nSkill directories scanned:\n  "
                + primary + fallbackLine +
                "\n\nPlace skill directories (containing SKILL.md) in one of the above locations.");
        }
    }

    private void updateSkillsPathLabel() {
        if (skillsPathLabel == null) return;
        skillsPathLabel.setText("Custom skills from " + effectiveSkillsFolder().toString());
    }

    private void scanSkillsDirectory(Path dir) {
        if (!Files.exists(dir) || !Files.isDirectory(dir)) return;

        try (Stream<Path> entries = Files.list(dir)) {
            entries.filter(Files::isDirectory).sorted().forEach(skillDir -> {
                SkillInfo info = loadSkillInfo(skillDir);
                if (info != null) {
                    loadedSkills.add(info);
                    skillsModel.addRow(new Object[]{info.name, info.description});
                }
            });
        } catch (IOException e) {
            LOG.warn("Failed to scan skills directory: " + dir, e);
        }
    }

    private SkillInfo loadSkillInfo(Path skillDir) {
        SkillInfo info = new SkillInfo();
        info.directory = skillDir;
        info.name = formatSkillName(skillDir.getFileName().toString());
        info.description = "";

        // Read SKILL.md for description
        Path skillMd = skillDir.resolve("SKILL.md");
        if (Files.exists(skillMd)) {
            try {
                info.skillMdContent = new String(Files.readAllBytes(skillMd), StandardCharsets.UTF_8);
                // Extract description from frontmatter or first paragraph
                info.description = extractDescription(info.skillMdContent);
            } catch (IOException e) {
                LOG.warn("Failed to read SKILL.md: " + skillMd, e);
            }
        }

        // Read LICENSE.txt if present
        Path license = skillDir.resolve("LICENSE.txt");
        if (!Files.exists(license)) license = skillDir.resolve("LICENSE");
        if (Files.exists(license)) {
            try {
                info.licenseContent = new String(Files.readAllBytes(license), StandardCharsets.UTF_8);
            } catch (IOException e) { /* ignore */ }
        }

        // List contents
        try (Stream<Path> files = Files.list(skillDir)) {
            info.contents = new ArrayList<>();
            files.sorted().forEach(p -> {
                String name = p.getFileName().toString();
                if (Files.isDirectory(p)) {
                    try (Stream<Path> sub = Files.list(p)) {
                        long count = sub.count();
                        info.contents.add("  " + name + "/  (" + count + " items)");
                    } catch (IOException e) {
                        info.contents.add("  " + name + "/");
                    }
                } else {
                    try {
                        long size = Files.size(p);
                        info.contents.add("  " + name + "  (" + formatSize(size) + ")");
                    } catch (IOException e) {
                        info.contents.add("  " + name);
                    }
                }
            });
        } catch (IOException e) { /* ignore */ }

        return (info.skillMdContent != null || (info.contents != null && !info.contents.isEmpty()))
            ? info : null;
    }

    private void onSkillSelected(ListSelectionEvent e) {
        if (e.getValueIsAdjusting()) return;
        int row = skillsTable.getSelectedRow();
        if (row < 0 || row >= loadedSkills.size()) {
            skillInfoArea.setText("");
            skillMdArea.setText("");
            skillLicenseArea.setText("");
            return;
        }

        SkillInfo info = loadedSkills.get(row);

        // Info tab
        StringBuilder sb = new StringBuilder();
        sb.append(info.name).append("\n");
        sb.append("=".repeat(info.name.length())).append("\n\n");
        sb.append("Type:    Local Skill\n");
        sb.append("Path:    ").append(info.directory).append("\n\n");
        sb.append("— Description —\n\n");
        sb.append(info.description).append("\n\n");
        sb.append("— Contents —\n\n");
        if (info.contents != null) {
            for (String c : info.contents) {
                sb.append(c).append("\n");
            }
        }
        skillInfoArea.setText(sb.toString());
        skillInfoArea.setCaretPosition(0);

        // SKILL.md tab
        skillMdArea.setText(info.skillMdContent != null ? info.skillMdContent : "(no SKILL.md found)");
        skillMdArea.setCaretPosition(0);

        // LICENSE tab
        skillLicenseArea.setText(info.licenseContent != null ? info.licenseContent : "(no LICENSE file found)");
        skillLicenseArea.setCaretPosition(0);
    }

    private void openSkillFolder() {
        int row = skillsTable.getSelectedRow();
        Path dir;
        if (row >= 0 && row < loadedSkills.size()) {
            dir = loadedSkills.get(row).directory;
        } else {
            dir = effectiveSkillsFolder();
        }

        // Auto-create the folder so the action always has something to open.
        try { Files.createDirectories(dir); } catch (IOException ignored) {}

        if (openFolderCrossPlatform(dir)) return;

        // Last resort: show the path so the user can copy it manually.
        JOptionPane.showMessageDialog(getContentPanel(),
            "Could not open folder automatically.\nPath:\n" + dir.toAbsolutePath(),
            "Open Folder", JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * Opens a folder via a 3-tier fallback that works on Windows / macOS / Linux:
     *   1) java.awt.Desktop.open() — preferred when supported
     *   2) Windows-only: rundll32 url.dll,FileProtocolHandler &lt;path&gt; (always works)
     *   3) Platform-specific commands (open / xdg-open / explorer)
     * Returns true if any tier succeeded.
     */
    private static boolean openFolderCrossPlatform(Path dir) {
        // Tier 1: Desktop API
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop d = Desktop.getDesktop();
                if (d.isSupported(Desktop.Action.OPEN)) {
                    d.open(dir.toFile());
                    return true;
                }
            }
        } catch (Exception ignored) {}

        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("win")) {
                // Tier 2: rundll32 (always available on Windows)
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler",
                    dir.toAbsolutePath().toString()).start();
                return true;
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", dir.toAbsolutePath().toString()).start();
                return true;
            } else {
                new ProcessBuilder("xdg-open", dir.toAbsolutePath().toString()).start();
                return true;
            }
        } catch (Exception e) {
            LOG.warn("Could not open folder " + dir, e);
            return false;
        }
    }

    /** Lets the user pick a different skills folder; persists to settings + reloads. */
    private void browseSkillsFolder() {
        com.intellij.openapi.fileChooser.FileChooserDescriptor descr =
            com.intellij.openapi.fileChooser.FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle("Select Skills Folder");
        com.intellij.openapi.vfs.VirtualFile preselect =
            com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(
                effectiveSkillsFolder().toAbsolutePath().toString());
        com.intellij.openapi.vfs.VirtualFile chosen =
            com.intellij.openapi.fileChooser.FileChooser.chooseFile(descr, getContentPanel(), null, preselect);
        if (chosen == null) return;
        com.anthropic.claude.intellij.settings.ClaudeSettings.getInstance()
            .getState().skillsFolder = chosen.getPath();
        loadSkills(); // updates label + table
    }

    // ==================== Tab 2: Installed Plugins ====================

    private JPanel buildInstalledPluginsTab() {
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setDividerLocation(420);

        // Left: plugin table
        pluginModel = new DefaultTableModel(new String[]{"Plugin", "Version", "Source", "Enabled"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        pluginTable = new JBTable(pluginModel);
        pluginTable.getColumnModel().getColumn(0).setPreferredWidth(160);
        pluginTable.getColumnModel().getColumn(1).setPreferredWidth(60);
        pluginTable.getColumnModel().getColumn(2).setPreferredWidth(80);
        pluginTable.getColumnModel().getColumn(3).setPreferredWidth(60);
        pluginTable.getSelectionModel().addListSelectionListener(this::onPluginSelected);

        JPanel leftPanel = new JPanel(new BorderLayout(0, 6));
        leftPanel.add(new JBScrollPane(pluginTable), BorderLayout.CENTER);

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton refreshBtn = new JButton("Refresh");
        refreshBtn.addActionListener(e -> loadPlugins());
        JButton toggleBtn = new JButton("Toggle Enable/Disable");
        toggleBtn.addActionListener(e -> toggleSelectedPlugin());
        JButton openBtn = new JButton("Open Folder");
        openBtn.setIcon(UIManager.getIcon("FileView.directoryIcon"));
        openBtn.addActionListener(e -> openPluginFolder());
        btnRow.add(refreshBtn);
        btnRow.add(toggleBtn);
        btnRow.add(openBtn);
        leftPanel.add(btnRow, BorderLayout.SOUTH);

        // Right: plugin details with tabs
        JPanel rightPanel = new JPanel(new BorderLayout());
        pluginDetailTabs = new JBTabbedPane();

        pluginInfoArea = createReadOnlyTextArea();
        pluginDetailTabs.addTab("Info", new JBScrollPane(pluginInfoArea));

        pluginInfoArea.setText("Select a plugin from the list to view details.");
        rightPanel.add(pluginDetailTabs, BorderLayout.CENTER);

        splitPane.setLeftComponent(leftPanel);
        splitPane.setRightComponent(rightPanel);
        return wrapInPanel(splitPane);
    }

    @SuppressWarnings("unchecked")
    private void loadPlugins() {
        loadedPlugins.clear();
        pluginModel.setRowCount(0);

        // Use CLI to get accurate installed plugins list
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                try {
                    String cliPath = findCliPath();
                    if (cliPath == null) return null;
                    ProcessBuilder pb = new ProcessBuilder(cliPath, "plugins", "list", "--json");
                    pb.redirectErrorStream(true);
                    Process proc = pb.start();
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) sb.append(line).append("\n");
                    }
                    proc.waitFor();
                    return sb.toString();
                } catch (Exception e) {
                    LOG.warn("Failed to list installed plugins via CLI", e);
                    return null;
                }
            }

            @Override
            protected void done() {
                try {
                    String json = get();
                    if (json == null || json.isEmpty()) {
                        pluginInfoArea.setText("Failed to list plugins. Make sure Claude CLI is installed.");
                        return;
                    }

                    // CLI returns a JSON array of installed plugins
                    List<Object> plugins = JsonParser.parseArray(json);
                    if (plugins != null) {
                        for (Object item : plugins) {
                            if (!(item instanceof Map)) continue;
                            Map<String, Object> map = (Map<String, Object>) item;

                            PluginInfo info = new PluginInfo();
                            info.pluginId = JsonParser.getString(map, "id", "");
                            info.version = JsonParser.getString(map, "version", "");
                            info.scope = JsonParser.getString(map, "scope", "user");
                            info.enabled = JsonParser.getBoolean(map, "enabled", true);
                            info.installPath = JsonParser.getString(map, "installPath", "");
                            info.installedAt = JsonParser.getString(map, "installedAt", "");
                            info.lastUpdated = JsonParser.getString(map, "lastUpdated", "");

                            // Derive name from ID (e.g., "code-review@claude-plugins-official" -> "Code Review")
                            String rawName = info.pluginId.contains("@")
                                ? info.pluginId.substring(0, info.pluginId.indexOf('@'))
                                : info.pluginId;
                            info.name = formatSkillName(rawName);

                            // Derive marketplace from ID
                            info.marketplace = info.pluginId.contains("@")
                                ? info.pluginId.substring(info.pluginId.indexOf('@') + 1)
                                : "";

                            // Try to read plugin.json from installPath for description/components
                            if (!info.installPath.isEmpty()) {
                                info.directory = Paths.get(info.installPath);
                                loadPluginDetails(info);
                            }

                            loadedPlugins.add(info);
                            pluginModel.addRow(new Object[]{
                                info.name, info.version, info.scope, info.enabled ? "Yes" : "No"
                            });
                        }
                    }

                    if (loadedPlugins.isEmpty()) {
                        pluginInfoArea.setText(
                            "No plugins installed.\n\n" +
                            "Install plugins from the 'Available Plugins' tab\n" +
                            "or use: claude plugins install <plugin-name>"
                        );
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to parse installed plugins", e);
                    pluginInfoArea.setText("Error loading installed plugins.");
                }
            }
        }.execute();
    }

    @SuppressWarnings("unchecked")
    private void loadPluginDetails(PluginInfo info) {
        if (info.directory == null || !Files.exists(info.directory)) return;

        Path pluginJson = info.directory.resolve("plugin.json");
        if (Files.exists(pluginJson)) {
            try {
                String content = new String(Files.readAllBytes(pluginJson), StandardCharsets.UTF_8);
                Map<String, Object> json = JsonParser.parseObject(content);
                info.description = JsonParser.getString(json, "description", "");
                info.skills = extractStringList(json, "skills");
                info.commands = extractStringList(json, "commands");
                info.hooks = extractStringList(json, "hooks");
                info.agents = extractStringList(json, "agents");
                info.rawJson = content;
            } catch (Exception e) {
                LOG.warn("Failed to parse plugin.json for " + info.name, e);
            }
        }
    }

    private void onPluginSelected(ListSelectionEvent e) {
        if (e.getValueIsAdjusting()) return;
        int row = pluginTable.getSelectedRow();
        if (row < 0 || row >= loadedPlugins.size()) {
            pluginInfoArea.setText("Select a plugin from the list to view details.");
            return;
        }

        PluginInfo info = loadedPlugins.get(row);
        StringBuilder sb = new StringBuilder();

        sb.append(info.name).append("\n");
        sb.append("=".repeat(Math.max(1, info.name.length()))).append("\n\n");
        sb.append("ID:          ").append(info.pluginId).append("\n");
        sb.append("Version:     ").append(info.version.isEmpty() ? "-" : info.version).append("\n");
        sb.append("Scope:       ").append(info.scope).append("\n");
        if (!info.marketplace.isEmpty()) {
            sb.append("Marketplace: ").append(info.marketplace).append("\n");
        }
        sb.append("Enabled:     ").append(info.enabled ? "Yes" : "No").append("\n");
        if (info.directory != null) {
            sb.append("Path:        ").append(info.directory).append("\n");
        }
        if (!info.installedAt.isEmpty()) {
            sb.append("Installed:   ").append(info.installedAt).append("\n");
        }
        if (!info.lastUpdated.isEmpty()) {
            sb.append("Updated:     ").append(info.lastUpdated).append("\n");
        }

        if (info.description != null && !info.description.isEmpty()) {
            sb.append("\n— Description —\n\n").append(info.description).append("\n");
        }

        // Show components summary
        List<String> components = new ArrayList<>();
        if (info.skills != null && !info.skills.isEmpty()) components.add("skills");
        if (info.commands != null && !info.commands.isEmpty()) components.add("commands");
        if (info.hooks != null && !info.hooks.isEmpty()) components.add("hooks");
        if (info.agents != null && !info.agents.isEmpty()) components.add("agents");
        if (!components.isEmpty()) {
            sb.append("\n— Components —\n\n  ").append(String.join(" · ", components)).append("\n");
        }

        appendNamedList(sb, "Skills", info.skills, "");
        appendNamedList(sb, "Commands", info.commands, "/");
        appendNamedList(sb, "Hooks", info.hooks, "");
        appendNamedList(sb, "Agents", info.agents, "");

        pluginInfoArea.setText(sb.toString());
        pluginInfoArea.setCaretPosition(0);
    }

    private void toggleSelectedPlugin() {
        int row = pluginTable.getSelectedRow();
        if (row < 0 || row >= loadedPlugins.size()) return;

        PluginInfo info = loadedPlugins.get(row);
        boolean newState = !info.enabled;
        String action = newState ? "enable" : "disable";

        // Use CLI to toggle
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                try {
                    String cliPath = findCliPath();
                    if (cliPath == null) return "CLI not found";
                    ProcessBuilder pb = new ProcessBuilder(cliPath, "plugins", action, info.pluginId);
                    pb.redirectErrorStream(true);
                    Process proc = pb.start();
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) sb.append(line).append("\n");
                    }
                    proc.waitFor();
                    return sb.toString();
                } catch (Exception e) {
                    return "Error: " + e.getMessage();
                }
            }

            @Override
            protected void done() {
                try {
                    get();
                    info.enabled = newState;
                    pluginModel.setValueAt(newState ? "Yes" : "No", row, 3);
                    onPluginSelected(new ListSelectionEvent(pluginTable, row, row, false));
                } catch (Exception e) {
                    LOG.error("Failed to toggle plugin", e);
                }
            }
        }.execute();
    }

    private void openPluginFolder() {
        int row = pluginTable.getSelectedRow();
        if (row < 0 || row >= loadedPlugins.size()) return;
        PluginInfo info = loadedPlugins.get(row);
        if (info.directory != null && Files.exists(info.directory)) {
            try {
                Desktop.getDesktop().open(info.directory.toFile());
            } catch (Exception e) {
                LOG.warn("Failed to open plugin folder", e);
            }
        }
    }

    // ==================== Tab 3: Available Plugins ====================

    private JPanel buildAvailablePluginsTab() {
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setDividerLocation(420);

        // Left: search + table
        JPanel leftPanel = new JPanel(new BorderLayout(0, 6));

        // Search field
        JTextField searchField = new JTextField();
        searchField.putClientProperty("JTextField.placeholderText", "Search plugins...");
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { filterAvailable(searchField.getText()); }
            public void removeUpdate(DocumentEvent e) { filterAvailable(searchField.getText()); }
            public void changedUpdate(DocumentEvent e) { filterAvailable(searchField.getText()); }
        });
        leftPanel.add(searchField, BorderLayout.NORTH);

        availableModel = new DefaultTableModel(new String[]{"Plugin", "Marketplace", "Installs"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        availableTable = new JBTable(availableModel);
        availableTable.getColumnModel().getColumn(0).setPreferredWidth(180);
        availableTable.getColumnModel().getColumn(1).setPreferredWidth(150);
        availableTable.getColumnModel().getColumn(2).setPreferredWidth(70);
        availableSorter = new TableRowSorter<>(availableModel);
        availableTable.setRowSorter(availableSorter);
        availableTable.getSelectionModel().addListSelectionListener(this::onAvailablePluginSelected);
        // Double-click to install
        availableTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) installSelectedPlugin();
            }
        });

        leftPanel.add(new JBScrollPane(availableTable), BorderLayout.CENTER);

        // Buttons + tip
        JPanel bottomPanel = new JPanel(new BorderLayout());
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton installBtn = new JButton("Install Plugin");
        installBtn.addActionListener(e -> installSelectedPlugin());
        JButton copyBtn = new JButton("Copy Command");
        copyBtn.addActionListener(e -> copyInstallCommand());
        btnRow.add(installBtn);
        btnRow.add(copyBtn);
        bottomPanel.add(btnRow, BorderLayout.NORTH);
        JLabel tip = new JLabel("Tip: Double-click a plugin to install it");
        tip.setFont(tip.getFont().deriveFont(Font.ITALIC, 11f));
        tip.setForeground(Color.GRAY);
        tip.setBorder(BorderFactory.createEmptyBorder(4, 4, 0, 0));
        bottomPanel.add(tip, BorderLayout.SOUTH);
        leftPanel.add(bottomPanel, BorderLayout.SOUTH);

        // Right: details
        JPanel rightPanel = new JPanel(new BorderLayout());
        JBTabbedPane detailTabs = new JBTabbedPane();

        availableInfoArea = createReadOnlyTextArea();
        availableReadmeArea = createReadOnlyTextArea();
        detailTabs.addTab("Info", new JBScrollPane(availableInfoArea));
        detailTabs.addTab("README.md", new JBScrollPane(availableReadmeArea));

        availableInfoArea.setText("Select a plugin from the list to view details.");
        rightPanel.add(detailTabs, BorderLayout.CENTER);

        splitPane.setLeftComponent(leftPanel);
        splitPane.setRightComponent(rightPanel);

        // Load data asynchronously
        loadAvailablePlugins();

        return wrapInPanel(splitPane);
    }

    private void loadAvailablePlugins() {
        availablePlugins.clear();
        availableModel.setRowCount(0);
        installedPluginIds.clear();

        // Run CLI in background thread
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                try {
                    String cliPath = findCliPath();
                    if (cliPath == null) return null;
                    ProcessBuilder pb = new ProcessBuilder(cliPath, "plugins", "list", "--available", "--json");
                    pb.redirectErrorStream(true);
                    Process proc = pb.start();
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) sb.append(line).append("\n");
                    }
                    proc.waitFor();
                    return sb.toString();
                } catch (Exception e) {
                    LOG.warn("Failed to fetch available plugins", e);
                    return null;
                }
            }

            @Override
            @SuppressWarnings("unchecked")
            protected void done() {
                try {
                    String json = get();
                    if (json == null || json.isEmpty()) {
                        availableInfoArea.setText("Failed to fetch available plugins.\nMake sure Claude CLI is installed.");
                        return;
                    }
                    Map<String, Object> data = JsonParser.parseObject(json);

                    // Track installed IDs
                    List<Object> installed = JsonParser.getList(data, "installed");
                    if (installed != null) {
                        for (Object item : installed) {
                            if (item instanceof Map) {
                                String id = JsonParser.getString((Map<String, Object>) item, "id", "");
                                if (!id.isEmpty()) installedPluginIds.add(id);
                            }
                        }
                    }

                    // Populate available
                    List<Object> available = JsonParser.getList(data, "available");
                    if (available != null) {
                        for (Object item : available) {
                            if (!(item instanceof Map)) continue;
                            Map<String, Object> map = (Map<String, Object>) item;
                            AvailablePluginInfo info = new AvailablePluginInfo();
                            info.pluginId = JsonParser.getString(map, "pluginId", "");
                            info.name = JsonParser.getString(map, "name", info.pluginId);
                            info.description = JsonParser.getString(map, "description", "");
                            info.marketplace = JsonParser.getString(map, "marketplaceName", "");
                            info.version = JsonParser.getString(map, "version", "");
                            info.installCount = JsonParser.getInt(map, "installCount", 0);
                            info.installed = installedPluginIds.contains(info.pluginId);

                            availablePlugins.add(info);
                            String installs = info.installCount > 0 ? String.valueOf(info.installCount) : "";
                            availableModel.addRow(new Object[]{
                                formatSkillName(info.name), info.marketplace, installs
                            });
                        }
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to parse available plugins", e);
                    availableInfoArea.setText("Error parsing available plugins data.");
                }
            }
        }.execute();
    }

    private void onAvailablePluginSelected(ListSelectionEvent e) {
        if (e.getValueIsAdjusting()) return;
        int viewRow = availableTable.getSelectedRow();
        if (viewRow < 0) {
            availableInfoArea.setText("Select a plugin from the list to view details.");
            return;
        }
        int modelRow = availableTable.convertRowIndexToModel(viewRow);
        if (modelRow < 0 || modelRow >= availablePlugins.size()) return;

        AvailablePluginInfo info = availablePlugins.get(modelRow);
        StringBuilder sb = new StringBuilder();
        sb.append(formatSkillName(info.name)).append("\n");
        sb.append("=".repeat(Math.max(1, info.name.length()))).append("\n\n");
        sb.append("Status:      ").append(info.installed ? "Installed" : "Not installed").append("\n");
        sb.append("ID:          ").append(info.pluginId).append("\n");
        sb.append("Marketplace: ").append(info.marketplace).append("\n");
        if (!info.version.isEmpty()) {
            sb.append("Version:     ").append(info.version).append("\n");
        }
        if (info.installCount > 0) {
            sb.append("Installs:    ").append(info.installCount).append("\n");
        }
        sb.append("\n— Description —\n\n").append(info.description).append("\n");

        availableInfoArea.setText(sb.toString());
        availableInfoArea.setCaretPosition(0);
        availableReadmeArea.setText("(README will be available after installation)");
    }

    private void filterAvailable(String text) {
        if (text == null || text.trim().isEmpty()) {
            availableSorter.setRowFilter(null);
        } else {
            try {
                availableSorter.setRowFilter(javax.swing.RowFilter.regexFilter("(?i)" + text.trim()));
            } catch (java.util.regex.PatternSyntaxException e) {
                // ignore invalid regex
            }
        }
    }

    private void installSelectedPlugin() {
        int viewRow = availableTable.getSelectedRow();
        if (viewRow < 0) return;
        int modelRow = availableTable.convertRowIndexToModel(viewRow);
        if (modelRow < 0 || modelRow >= availablePlugins.size()) return;

        AvailablePluginInfo info = availablePlugins.get(modelRow);
        if (info.installed) {
            JOptionPane.showMessageDialog(getContentPanel(),
                info.name + " is already installed.", "Already Installed", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(getContentPanel(),
            "Install plugin \"" + info.name + "\" from " + info.marketplace + "?",
            "Install Plugin", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;

        // Run install in background
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                try {
                    String cliPath = findCliPath();
                    if (cliPath == null) return "CLI not found";
                    ProcessBuilder pb = new ProcessBuilder(cliPath, "plugins", "install", info.pluginId);
                    pb.redirectErrorStream(true);
                    Process proc = pb.start();
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) sb.append(line).append("\n");
                    }
                    proc.waitFor();
                    return sb.toString();
                } catch (Exception e) {
                    return "Error: " + e.getMessage();
                }
            }

            @Override
            protected void done() {
                try {
                    String result = get();
                    info.installed = true;
                    installedPluginIds.add(info.pluginId);
                    JOptionPane.showMessageDialog(getContentPanel(),
                        "Plugin \"" + info.name + "\" installed successfully.\n\n" + result,
                        "Installed", JOptionPane.INFORMATION_MESSAGE);
                    // Refresh installed tab
                    loadPlugins();
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(getContentPanel(),
                        "Installation failed: " + e.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void copyInstallCommand() {
        int viewRow = availableTable.getSelectedRow();
        if (viewRow < 0) return;
        int modelRow = availableTable.convertRowIndexToModel(viewRow);
        if (modelRow < 0 || modelRow >= availablePlugins.size()) return;

        AvailablePluginInfo info = availablePlugins.get(modelRow);
        String command = "claude plugins install " + info.pluginId;
        java.awt.datatransfer.StringSelection sel = new java.awt.datatransfer.StringSelection(command);
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, null);
        JOptionPane.showMessageDialog(getContentPanel(),
            "Copied to clipboard:\n" + command, "Copied", JOptionPane.INFORMATION_MESSAGE);
    }

    private String findCliPath() {
        String[] paths = {
            System.getProperty("user.home") + "/.local/bin/claude",
            "/usr/local/bin/claude",
            System.getProperty("user.home") + "/.npm/bin/claude"
        };
        for (String p : paths) {
            if (Files.exists(Paths.get(p))) return p;
        }
        return null;
    }

    // ==================== Buttons ====================

    @Override
    protected Action[] createActions() {
        return new Action[]{getOKAction()};
    }

    // ==================== Utilities ====================

    private JTextArea createReadOnlyTextArea() {
        JTextArea area = new JTextArea();
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        return area;
    }

    private JPanel wrapInPanel(JComponent comp) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        panel.add(comp, BorderLayout.CENTER);
        return panel;
    }

    private String formatSkillName(String dirName) {
        // Convert kebab-case/snake_case to Title Case
        return Arrays.stream(dirName.replace('-', ' ').replace('_', ' ').split("\\s+"))
            .map(w -> w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1))
            .reduce((a, b) -> a + " " + b)
            .orElse(dirName);
    }

    private String extractDescription(String skillMdContent) {
        if (skillMdContent == null) return "";
        String[] lines = skillMdContent.split("\n");
        boolean inFrontmatter = false;
        StringBuilder desc = new StringBuilder();

        for (String line : lines) {
            if (line.trim().equals("---")) {
                inFrontmatter = !inFrontmatter;
                continue;
            }
            if (inFrontmatter) {
                // Check for description in frontmatter
                if (line.trim().startsWith("description:")) {
                    return line.substring(line.indexOf(':') + 1).trim().replaceAll("^[\"']|[\"']$", "");
                }
                continue;
            }
            // After frontmatter, get first non-empty, non-heading line
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                desc.append(trimmed);
                if (desc.length() > 120) break;
            } else if (desc.length() > 0) {
                break; // End of first paragraph
            }
        }
        return desc.toString();
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    @SuppressWarnings("unchecked")
    private List<String> extractStringList(Map<String, Object> json, String key) {
        List<Object> raw = JsonParser.getList(json, key);
        if (raw == null) return null;
        List<String> result = new ArrayList<>();
        for (Object item : raw) {
            if (item instanceof String) {
                result.add((String) item);
            } else if (item instanceof Map) {
                String name = JsonParser.getString((Map<String, Object>) item, "name", "");
                if (!name.isEmpty()) result.add(name);
            }
        }
        return result.isEmpty() ? null : result;
    }

    private void appendNamedList(StringBuilder sb, String title, List<String> items, String prefix) {
        if (items != null && !items.isEmpty()) {
            sb.append("\n— ").append(title).append(" (").append(items.size()).append(") —\n\n");
            for (String s : items) {
                sb.append("  - ").append(prefix).append(s).append("\n");
            }
        }
    }

    // ==================== Inner Data ====================

    private static class SkillInfo {
        String name;
        String description;
        Path directory;
        String skillMdContent;
        String licenseContent;
        List<String> contents;
    }

    private static class AvailablePluginInfo {
        String pluginId;
        String name;
        String description;
        String marketplace;
        String version;
        int installCount;
        boolean installed;
    }

    private static class PluginInfo {
        String pluginId;
        String name;
        String version;
        String description;
        String scope;
        String marketplace;
        boolean enabled;
        Path directory;
        String installPath;
        String installedAt;
        String lastUpdated;
        List<String> skills;
        List<String> commands;
        List<String> hooks;
        List<String> agents;
        String rawJson;
    }
}
