package com.anthropic.claude.intellij.ui.dialogs;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.components.JBTextArea;

import javax.swing.*;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Dialog for managing Claude project memory (MEMORY.md).
 * Memory persists context across sessions for a specific project.
 * Uses native IntelliJ DialogWrapper.
 */
public class MemoryDialog extends DialogWrapper {

    private static final Logger LOG = Logger.getInstance(MemoryDialog.class);

    private JBTextArea memoryText;
    private final Path memoryPath;

    public MemoryDialog(Project project, String projectDir) {
        super(project, true);

        String dir = projectDir != null ? projectDir : System.getProperty("user.home");
        String encodedPath = dir.replace("/", "-");
        this.memoryPath = Paths.get(
            System.getProperty("user.home"), ".claude", "projects",
            encodedPath, "memory", "MEMORY.md");

        setTitle("Claude Code - Memory & Context");
        setSize(800, 600);
        init();
    }

    @Override
    protected JComponent createCenterPanel() {
        JBTabbedPane tabbedPane = new JBTabbedPane();

        tabbedPane.addTab("Project Memory (MEMORY.md)", createMemoryTab());
        tabbedPane.addTab("Tips & Examples", createTipsTab());

        loadMemory();
        return tabbedPane;
    }

    private JPanel createMemoryTab() {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JLabel pathLabel = new JLabel("File: " + memoryPath.toString());
        pathLabel.setFont(pathLabel.getFont().deriveFont(Font.PLAIN, 11f));

        boolean exists = Files.exists(memoryPath);
        JLabel statusLabel = new JLabel(exists ? "\u2713 File exists" : "\u26A0 Will be created on save");
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.ITALIC, 11f));

        JPanel topPanel = new JPanel(new GridLayout(2, 1, 0, 2));
        topPanel.add(pathLabel);
        topPanel.add(statusLabel);

        memoryText = new JBTextArea();
        memoryText.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        memoryText.setLineWrap(true);
        memoryText.setWrapStyleWord(true);

        panel.add(topPanel, BorderLayout.NORTH);
        panel.add(new JBScrollPane(memoryText), BorderLayout.CENTER);

        return panel;
    }

    private JPanel createTipsTab() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JBTextArea tipsText = new JBTextArea();
        tipsText.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        tipsText.setEditable(false);
        tipsText.setLineWrap(true);
        tipsText.setWrapStyleWord(true);
        tipsText.setText(
            "# Memory Tips\n\n"
            + "Memory files help Claude remember important context about your project.\n"
            + "Claude reads MEMORY.md at the start of each session.\n\n"
            + "## What to include:\n\n"
            + "- Project architecture and key design decisions\n"
            + "- Important file locations and their purposes\n"
            + "- Coding conventions and style preferences\n"
            + "- Known issues or gotchas\n"
            + "- Build/test commands and workflows\n"
            + "- Team agreements and code review standards\n\n"
            + "## Example:\n\n"
            + "# Project Memory\n\n"
            + "## Architecture\n"
            + "- IntelliJ Platform Plugin using Gradle\n"
            + "- Communicates with Claude CLI via NDJSON over stdin/stdout\n"
            + "- JCEF webview for chat UI\n\n"
            + "## Build Commands\n"
            + "- Build: ./gradlew buildPlugin\n"
            + "- Run: ./gradlew runIde\n\n"
            + "Claude can also add to memory automatically during conversations\n"
            + "when it discovers important project context."
        );

        panel.add(new JBScrollPane(tipsText), BorderLayout.CENTER);
        return panel;
    }

    private void loadMemory() {
        try {
            if (Files.exists(memoryPath)) {
                String content = new String(Files.readAllBytes(memoryPath), StandardCharsets.UTF_8);
                memoryText.setText(content);
            }
        } catch (Exception e) {
            LOG.error("[MemoryDialog] Failed to read " + memoryPath + ": " + e.getMessage(), e);
        }
    }

    private void saveMemory() {
        try {
            String content = memoryText.getText();
            if (content.isEmpty() && !Files.exists(memoryPath)) {
                return;
            }
            if (content.isEmpty() && Files.exists(memoryPath)) {
                Files.delete(memoryPath);
                return;
            }
            Files.createDirectories(memoryPath.getParent());
            Files.write(memoryPath, content.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            LOG.error("Failed to save " + memoryPath + ": " + e.getMessage(), e);
        }
    }

    @Override
    protected Action[] createActions() {
        return new Action[]{getOKAction(), getCancelAction()};
    }

    @Override
    protected void doOKAction() {
        saveMemory();
        super.doOKAction();
    }
}
