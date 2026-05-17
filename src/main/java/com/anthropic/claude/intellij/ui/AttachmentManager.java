package com.anthropic.claude.intellij.ui;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages file attachments for the Claude chat.
 * Provides dialogs for picking project files, filesystem files,
 * and optional line ranges. Builds context blocks for the CLI.
 */
public class AttachmentManager {

    private static final Logger LOG = Logger.getInstance(AttachmentManager.class);

    private final Project project;
    private final List<FileAttachment> attachments = new ArrayList<>();

    public AttachmentManager(Project project) {
        this.project = project;
    }

    /**
     * Represents a file attachment with optional line range.
     */
    public static class FileAttachment {
        private final String filePath;
        private final String displayName;
        private final int startLine; // 0 = no range
        private final int endLine;   // 0 = no range

        public FileAttachment(String filePath, String displayName, int startLine, int endLine) {
            this.filePath = filePath;
            this.displayName = displayName;
            this.startLine = startLine;
            this.endLine = endLine;
        }

        public String getFilePath() { return filePath; }
        public String getDisplayName() { return displayName; }
        public int getStartLine() { return startLine; }
        public int getEndLine() { return endLine; }
        public boolean hasLineRange() { return startLine > 0 && endLine > 0; }

        public String getLabel() {
            if (hasLineRange()) {
                return displayName + " (L" + startLine + "-" + endLine + ")";
            }
            return displayName;
        }
    }

    /**
     * Shows a popup menu letting the user choose between project file and filesystem file.
     */
    public void showAttachMenu(Component parent, AttachmentCallback callback) {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem projectItem = new JMenuItem("From Project...");
        projectItem.addActionListener(e -> attachFileFromProject(callback));
        menu.add(projectItem);

        JMenuItem filesystemItem = new JMenuItem("From Filesystem...");
        filesystemItem.addActionListener(e -> attachFileFromFilesystem(callback));
        menu.add(filesystemItem);

        menu.show(parent, 0, parent.getHeight());
    }

    /**
     * Opens the IntelliJ file chooser for project files.
     */
    public void attachFileFromProject(AttachmentCallback callback) {
        FileChooserDescriptor descriptor = new FileChooserDescriptor(true, false, false, false, false, true)
            .withTitle("Attach Project Files")
            .withDescription("Select files to attach to the conversation");

        VirtualFile projectDir = project.getBaseDir();
        VirtualFile[] files = FileChooser.chooseFiles(descriptor, project, projectDir);
        if (files.length == 0) return;

        for (VirtualFile file : files) {
            String relativePath = projectDir != null
                ? VfsUtilCore.getRelativePath(file, projectDir)
                : null;
            String displayName = relativePath != null ? relativePath : file.getName();

            // Match Eclipse behaviour: attach the whole file with no
            // intermediate "Line range (optional)" dialog — that extra prompt
            // was added per-IntelliJ but felt like friction for the common
            // case of "just attach this file". Line-range support is still
            // wired through FileAttachment for future @-mentions that want it.
            FileAttachment attachment = new FileAttachment(
                file.getPath(), displayName, 0, 0
            );
            attachments.add(attachment);
        }
        callback.onAttachmentsChanged(getAttachments());
    }

    /**
     * Opens a native filesystem file chooser.
     */
    public void attachFileFromFilesystem(AttachmentCallback callback) {
        FileChooserDescriptor descriptor = new FileChooserDescriptor(true, false, false, false, false, true)
            .withTitle("Attach Files from Filesystem")
            .withDescription("Select files to attach to the conversation");

        VirtualFile[] files = FileChooser.chooseFiles(descriptor, project, null);
        if (files.length == 0) return;

        for (VirtualFile file : files) {
            // Match Eclipse behaviour — attach whole file, no line-range prompt.
            FileAttachment attachment = new FileAttachment(
                file.getPath(), file.getName(), 0, 0
            );
            attachments.add(attachment);
        }
        callback.onAttachmentsChanged(getAttachments());
    }

    /**
     * Handles an @-mention file reference from the webview.
     * The filePath should be a project-relative path.
     */
    public void attachFileByPath(String filePath, AttachmentCallback callback) {
        VirtualFile projectDir = project.getBaseDir();
        if (projectDir == null) return;

        VirtualFile file = projectDir.findFileByRelativePath(filePath);
        if (file == null || file.isDirectory()) return;

        String displayName = filePath;
        FileAttachment attachment = new FileAttachment(file.getPath(), displayName, 0, 0);
        attachments.add(attachment);
        callback.onAttachmentsChanged(getAttachments());
    }

    /**
     * Asks the user for an optional line range.
     */
    // Kept around for potential future use (e.g. an explicit "Attach with
    // line range…" entry point or @-mention extensions). Not called by the
    // default attach flow, which now matches Eclipse and skips the prompt.
    @SuppressWarnings("unused")
    private LineRange askForLineRange(String fileName) {
        LineRangeDialog dialog = new LineRangeDialog(project, fileName);
        if (dialog.showAndGet()) {
            int start = dialog.getStartLine();
            int end = dialog.getEndLine();
            if (start > 0 && end >= start) {
                return new LineRange(start, end);
            }
        }
        return null;
    }

    /**
     * Removes an attachment by index.
     */
    public void removeAttachment(int index) {
        if (index >= 0 && index < attachments.size()) {
            attachments.remove(index);
        }
    }

    /**
     * Clears all attachments.
     */
    public void clearAttachments() {
        attachments.clear();
    }

    /**
     * Returns current attachment list.
     */
    public List<FileAttachment> getAttachments() {
        return new ArrayList<>(attachments);
    }

    /**
     * Builds the file context string for all attachments.
     * Each file is wrapped in XML tags with optional line range.
     */
    public String buildFileContext() {
        if (attachments.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        for (FileAttachment att : attachments) {
            try {
                java.io.File file = new java.io.File(att.getFilePath());
                if (!file.exists() || !file.isFile()) continue;

                List<String> allLines = java.nio.file.Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);

                sb.append("<file path=\"").append(att.getDisplayName()).append("\"");
                if (att.hasLineRange()) {
                    sb.append(" lines=\"").append(att.getStartLine()).append("-").append(att.getEndLine()).append("\"");
                }
                sb.append(">\n");

                if (att.hasLineRange()) {
                    int start = Math.max(0, att.getStartLine() - 1);
                    int end = Math.min(allLines.size(), att.getEndLine());
                    for (int i = start; i < end; i++) {
                        sb.append(allLines.get(i)).append("\n");
                    }
                } else {
                    for (String line : allLines) {
                        sb.append(line).append("\n");
                    }
                }
                sb.append("</file>\n\n");
            } catch (IOException e) {
                LOG.warn("Failed to read file: " + att.getFilePath(), e);
            }
        }
        return sb.toString();
    }

    // ==================== Inner Classes ====================

    private static class LineRange {
        final int start;
        final int end;
        LineRange(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    /**
     * Dialog for specifying an optional line range.
     */
    private static class LineRangeDialog extends DialogWrapper {
        private final JBTextField startField = new JBTextField(8);
        private final JBTextField endField = new JBTextField(8);
        private final String fileName;

        LineRangeDialog(Project project, String fileName) {
            super(project, false);
            this.fileName = fileName;
            setTitle("Line Range (Optional)");
            setOKButtonText("Attach");
            init();
        }

        @Override
        protected JComponent createCenterPanel() {
            JPanel panel = new JPanel(new GridBagLayout());
            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(4, 4, 4, 4);
            c.fill = GridBagConstraints.HORIZONTAL;

            c.gridx = 0; c.gridy = 0; c.gridwidth = 2;
            panel.add(new JBLabel("Attach: " + fileName), c);

            c.gridy = 1; c.gridwidth = 2;
            panel.add(new JBLabel("Leave empty to attach the entire file."), c);

            c.gridy = 2; c.gridwidth = 1; c.gridx = 0;
            panel.add(new JBLabel("Start line:"), c);
            c.gridx = 1;
            panel.add(startField, c);

            c.gridy = 3; c.gridx = 0;
            panel.add(new JBLabel("End line:"), c);
            c.gridx = 1;
            panel.add(endField, c);

            return panel;
        }

        int getStartLine() {
            try { return Integer.parseInt(startField.getText().trim()); }
            catch (NumberFormatException e) { return 0; }
        }

        int getEndLine() {
            try { return Integer.parseInt(endField.getText().trim()); }
            catch (NumberFormatException e) { return 0; }
        }
    }

    /**
     * Callback for attachment changes.
     */
    public interface AttachmentCallback {
        void onAttachmentsChanged(List<FileAttachment> attachments);
    }
}
