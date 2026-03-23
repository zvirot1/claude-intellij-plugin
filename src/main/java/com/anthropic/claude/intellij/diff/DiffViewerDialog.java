package com.anthropic.claude.intellij.diff;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * A diff viewer dialog with "Apply to Editor" and "Copy Modified Code" buttons,
 * matching the Eclipse plugin's DiffViewerDialog functionality.
 * <p>
 * Shows a side-by-side view of original vs modified content with action buttons.
 */
public class DiffViewerDialog extends DialogWrapper {

    private static final Logger LOG = Logger.getInstance(DiffViewerDialog.class);

    private final Project project;
    private final String filePath;
    private final String originalContent;
    private final String modifiedContent;

    public DiffViewerDialog(Project project, String filePath,
                            String originalContent, String modifiedContent) {
        super(project, true);
        this.project = project;
        this.filePath = filePath;
        this.originalContent = originalContent;
        this.modifiedContent = modifiedContent;

        setTitle("Claude Diff" + (filePath != null ? ": " + filePath : ""));
        setOKButtonText("Close");
        setCancelButtonText(null);
        init();
    }

    @Override
    protected JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setPreferredSize(new Dimension(900, 600));

        // Header with file path
        if (filePath != null) {
            JBLabel pathLabel = new JBLabel("File: " + filePath);
            pathLabel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
            panel.add(pathLabel, BorderLayout.NORTH);
        }

        // Side-by-side diff panels
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setResizeWeight(0.5);

        // Left: Original
        JPanel leftPanel = createCodePanel("Original", originalContent);
        splitPane.setLeftComponent(leftPanel);

        // Right: Modified
        JPanel rightPanel = createCodePanel("Modified (Claude)", modifiedContent);
        splitPane.setRightComponent(rightPanel);

        panel.add(splitPane, BorderLayout.CENTER);

        // Action buttons bar
        JPanel actionBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));

        JButton applyBtn = new JButton("Apply to Editor");
        applyBtn.addActionListener(e -> applyToEditor());
        actionBar.add(applyBtn);

        JButton copyBtn = new JButton("Copy Modified Code");
        copyBtn.addActionListener(e -> copyModifiedCode());
        actionBar.add(copyBtn);

        JButton showInDiffBtn = new JButton("Open in Diff Viewer");
        showInDiffBtn.addActionListener(e -> {
            DiffService.showDiff(project, filePath, originalContent, modifiedContent);
        });
        actionBar.add(showInDiffBtn);

        panel.add(actionBar, BorderLayout.SOUTH);

        return panel;
    }

    @Override
    protected Action[] createActions() {
        return new Action[]{getOKAction()};
    }

    private JPanel createCodePanel(String title, String content) {
        JPanel panel = new JPanel(new BorderLayout());

        JBLabel titleLabel = new JBLabel(title);
        titleLabel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD));
        panel.add(titleLabel, BorderLayout.NORTH);

        JTextArea textArea = new JTextArea(content);
        textArea.setEditable(false);
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        textArea.setTabSize(4);

        // Simple diff coloring
        panel.add(new JBScrollPane(textArea), BorderLayout.CENTER);

        return panel;
    }

    private void applyToEditor() {
        if (filePath != null) {
            // Write to the file directly
            ApplicationManager.getApplication().invokeLater(() -> {
                VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(filePath);
                if (vf != null) {
                    Document doc = FileDocumentManager.getInstance().getDocument(vf);
                    if (doc != null) {
                        WriteCommandAction.runWriteCommandAction(project,
                            "Apply Claude Edit", null, () -> {
                                doc.setText(modifiedContent);
                            });
                        close(OK_EXIT_CODE);
                        return;
                    }
                }

                // Fallback: write to filesystem
                try {
                    Files.write(Paths.get(filePath),
                        modifiedContent.getBytes(StandardCharsets.UTF_8));
                    close(OK_EXIT_CODE);
                } catch (IOException e) {
                    LOG.error("Failed to apply edit to file: " + filePath, e);
                    Messages.showErrorDialog(project,
                        "Failed to apply edit: " + e.getMessage(), "Apply Error");
                }
            });
        } else {
            // No file path — try to apply to current editor
            ApplicationManager.getApplication().invokeLater(() -> {
                com.intellij.openapi.fileEditor.FileEditor fe =
                    FileEditorManager.getInstance(project).getSelectedEditor();
                if (fe instanceof TextEditor) {
                    Editor editor = ((TextEditor) fe).getEditor();
                    WriteCommandAction.runWriteCommandAction(project,
                        "Apply Claude Edit", null, () -> {
                            editor.getDocument().setText(modifiedContent);
                        });
                    close(OK_EXIT_CODE);
                }
            });
        }
    }

    private void copyModifiedCode() {
        Toolkit.getDefaultToolkit().getSystemClipboard()
            .setContents(new StringSelection(modifiedContent), null);
        Messages.showInfoMessage(project, "Modified code copied to clipboard.", "Copied");
    }

    /**
     * Convenience: show the dialog for a file, reading the original content from disk.
     */
    public static void openForFile(Project project, String filePath, String modifiedContent) {
        String originalContent = "";
        try {
            originalContent = new String(
                Files.readAllBytes(Paths.get(filePath)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("Could not read original file: " + filePath, e);
        }
        new DiffViewerDialog(project, filePath, originalContent, modifiedContent).show();
    }
}
