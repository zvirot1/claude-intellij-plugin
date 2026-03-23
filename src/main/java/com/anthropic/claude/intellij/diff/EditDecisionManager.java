package com.anthropic.claude.intellij.diff;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.contents.DiffContent;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.markup.*;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;

import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages the staging of Claude's file edits with Accept/Reject workflow.
 * Each edit is shown inline in the editor with colored highlights (green for additions,
 * red for deletions) and can be individually accepted or rejected.
 */
public class EditDecisionManager {

    private static final Logger LOG = Logger.getInstance(EditDecisionManager.class);

    /** Background color for added lines (green tint). */
    private static final JBColor COLOR_ADDED = new JBColor(
        new Color(0xDD, 0xFF, 0xDD), new Color(0x2D, 0x4A, 0x2D));

    /** Background color for removed lines (red tint). */
    private static final JBColor COLOR_REMOVED = new JBColor(
        new Color(0xFF, 0xDD, 0xDD), new Color(0x4A, 0x2D, 0x2D));

    /** Background color for pending edits gutter stripe. */
    private static final JBColor COLOR_GUTTER = new JBColor(
        new Color(0xFF, 0xAA, 0x00), new Color(0xCC, 0x88, 0x00));

    public enum EditState {
        PENDING, ACCEPTED, REJECTED
    }

    /**
     * Represents a single pending edit in a file.
     */
    public static class PendingEdit {
        private final String id;
        private final String filePath;
        private final String originalContent;
        private final String modifiedContent;
        private final int startLine;
        private final int endLine;
        private EditState state = EditState.PENDING;
        private final List<RangeHighlighter> highlighters = new ArrayList<>();

        public PendingEdit(String id, String filePath, String originalContent,
                           String modifiedContent, int startLine, int endLine) {
            this.id = id;
            this.filePath = filePath;
            this.originalContent = originalContent;
            this.modifiedContent = modifiedContent;
            this.startLine = startLine;
            this.endLine = endLine;
        }

        public String getId() { return id; }
        public String getFilePath() { return filePath; }
        public String getOriginalContent() { return originalContent; }
        public String getModifiedContent() { return modifiedContent; }
        public int getStartLine() { return startLine; }
        public int getEndLine() { return endLine; }
        public EditState getState() { return state; }
        public void setState(EditState state) { this.state = state; }
        public List<RangeHighlighter> getHighlighters() { return highlighters; }
    }

    /**
     * Listener interface for edit decision events.
     */
    public interface EditDecisionListener {
        void onEditAccepted(PendingEdit edit);
        void onEditRejected(PendingEdit edit);
        void onAllEditsResolved();
    }

    private Project project;
    private final CheckpointManager checkpointManager;
    private final Map<String, PendingEdit> pendingEdits = new LinkedHashMap<>();
    private final List<EditDecisionListener> listeners = new CopyOnWriteArrayList<>();
    private int editCounter = 0;

    public EditDecisionManager(CheckpointManager checkpointManager) {
        this.checkpointManager = checkpointManager;
    }

    /** Set the project reference (needed for EDT operations). */
    public void setProject(Project project) {
        this.project = project;
    }

    public void addListener(EditDecisionListener listener) {
        listeners.add(listener);
    }

    public void removeListener(EditDecisionListener listener) {
        listeners.remove(listener);
    }

    /**
     * Stages a new edit for review. Highlights the affected lines in the editor.
     *
     * @param filePath        absolute path of the file being edited
     * @param originalContent the original text that was replaced
     * @param modifiedContent the new text that Claude wants to insert
     * @param startLine       0-based start line of the edit
     * @param endLine         0-based end line (exclusive) of the edit
     * @return the edit ID, or null if staging failed
     */
    public String stageEdit(String filePath, String originalContent, String modifiedContent,
                            int startLine, int endLine) {
        String id = "edit-" + (++editCounter);
        PendingEdit edit = new PendingEdit(id, filePath, originalContent, modifiedContent, startLine, endLine);
        pendingEdits.put(id, edit);

        // Add inline highlights
        ApplicationManager.getApplication().invokeLater(() -> {
            addHighlights(edit);
        });

        LOG.info("Staged edit " + id + " in " + filePath + " (lines " + startLine + "-" + endLine + ")");
        return id;
    }

    /**
     * Records a completed edit with the original content from a checkpoint.
     * This preserves the pre-edit content for accurate diff viewing.
     */
    public String recordCompletedEditWithOriginal(String filePath, String originalContent, int startLine, int lineCount) {
        String id = "edit-" + (++editCounter);

        // Read current file content for the modified region
        String modifiedContent = "";
        try {
            List<String> lines = Files.readAllLines(Paths.get(filePath), StandardCharsets.UTF_8);
            int end = Math.min(startLine + lineCount, lines.size());
            StringBuilder sb = new StringBuilder();
            for (int i = startLine; i < end; i++) {
                sb.append(lines.get(i)).append("\n");
            }
            modifiedContent = sb.toString();
        } catch (Exception e) {
            LOG.warn("Failed to read modified content", e);
        }

        String original = (originalContent != null) ? originalContent : "";
        PendingEdit edit = new PendingEdit(id, filePath, original, modifiedContent, startLine, startLine + lineCount);
        edit.setState(EditState.PENDING);
        pendingEdits.put(id, edit);

        ApplicationManager.getApplication().invokeLater(() -> {
            addHighlights(edit);
        });

        return id;
    }

    /**
     * Records that an edit has been completed by the CLI (file already written).
     * Highlights the modified region for user review.
     */
    public String recordCompletedEdit(String filePath, int startLine, int lineCount) {
        String id = "edit-" + (++editCounter);

        // Read current file content for the modified region
        String modifiedContent = "";
        try {
            List<String> lines = Files.readAllLines(Paths.get(filePath), StandardCharsets.UTF_8);
            int end = Math.min(startLine + lineCount, lines.size());
            StringBuilder sb = new StringBuilder();
            for (int i = startLine; i < end; i++) {
                sb.append(lines.get(i)).append("\n");
            }
            modifiedContent = sb.toString();
        } catch (Exception e) {
            LOG.warn("Failed to read modified content", e);
        }

        PendingEdit edit = new PendingEdit(id, filePath, "", modifiedContent, startLine, startLine + lineCount);
        edit.setState(EditState.PENDING);
        pendingEdits.put(id, edit);

        ApplicationManager.getApplication().invokeLater(() -> {
            addHighlights(edit);
        });

        return id;
    }

    /**
     * Accepts a pending edit. The file content is already applied by the CLI,
     * so we just remove the highlights and mark as accepted.
     */
    public void acceptEdit(String editId) {
        PendingEdit edit = pendingEdits.get(editId);
        if (edit == null || edit.getState() != EditState.PENDING) return;

        edit.setState(EditState.ACCEPTED);
        removeHighlights(edit);

        for (EditDecisionListener listener : listeners) {
            listener.onEditAccepted(edit);
        }
        checkAllResolved();
        LOG.info("Accepted edit: " + editId);
    }

    /**
     * Rejects a pending edit. Reverts the file content to the original.
     */
    public void rejectEdit(String editId) {
        PendingEdit edit = pendingEdits.get(editId);
        if (edit == null || edit.getState() != EditState.PENDING) return;

        edit.setState(EditState.REJECTED);
        removeHighlights(edit);

        // Revert file content if we have original content
        if (edit.getOriginalContent() != null && !edit.getOriginalContent().isEmpty()) {
            ApplicationManager.getApplication().invokeLater(() -> {
                revertEdit(edit);
            });
        }

        for (EditDecisionListener listener : listeners) {
            listener.onEditRejected(edit);
        }
        checkAllResolved();
        LOG.info("Rejected edit: " + editId);
    }

    /**
     * Accepts all pending edits.
     */
    public void acceptAll() {
        List<String> pendingIds = new ArrayList<>();
        for (Map.Entry<String, PendingEdit> entry : pendingEdits.entrySet()) {
            if (entry.getValue().getState() == EditState.PENDING) {
                pendingIds.add(entry.getKey());
            }
        }
        for (String id : pendingIds) {
            acceptEdit(id);
        }
    }

    /**
     * Rejects all pending edits.
     */
    public void rejectAll() {
        List<String> pendingIds = new ArrayList<>();
        for (Map.Entry<String, PendingEdit> entry : pendingEdits.entrySet()) {
            if (entry.getValue().getState() == EditState.PENDING) {
                pendingIds.add(entry.getKey());
            }
        }
        for (String id : pendingIds) {
            rejectEdit(id);
        }
    }

    /**
     * Returns the number of pending (unresolved) edits.
     */
    public int getPendingCount() {
        int count = 0;
        for (PendingEdit edit : pendingEdits.values()) {
            if (edit.getState() == EditState.PENDING) count++;
        }
        return count;
    }

    /**
     * Returns all pending edits for a given file.
     */
    public List<PendingEdit> getEditsForFile(String filePath) {
        List<PendingEdit> result = new ArrayList<>();
        for (PendingEdit edit : pendingEdits.values()) {
            if (edit.getFilePath().equals(filePath) && edit.getState() == EditState.PENDING) {
                result.add(edit);
            }
        }
        return result;
    }

    /**
     * Clears all edits (resolved and pending).
     */
    public void clearAll() {
        for (PendingEdit edit : pendingEdits.values()) {
            removeHighlights(edit);
        }
        pendingEdits.clear();
    }

    /**
     * Opens IntelliJ's diff viewer for a pending edit,
     * showing original (checkpoint) vs. current (modified) content.
     */
    public void viewDiff(Project proj, String editId) {
        PendingEdit edit = pendingEdits.get(editId);
        if (edit == null) return;

        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                String originalText = edit.getOriginalContent();
                // If no original content stored, try to get from checkpoint
                if ((originalText == null || originalText.isEmpty()) && checkpointManager != null) {
                    originalText = checkpointManager.getCheckpoint(edit.getFilePath());
                }
                if (originalText == null) originalText = "";

                // Read current file content
                String currentText = "";
                try {
                    currentText = new String(Files.readAllBytes(Paths.get(edit.getFilePath())), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    LOG.warn("Failed to read current file for diff", e);
                }

                DiffContentFactory dcf = DiffContentFactory.getInstance();
                DiffContent left = dcf.create(proj, originalText);
                DiffContent right = dcf.create(proj, currentText);

                String fileName = Paths.get(edit.getFilePath()).getFileName().toString();
                SimpleDiffRequest request = new SimpleDiffRequest(
                    "Claude Edit: " + fileName,
                    left, right,
                    "Before", "After (Claude)"
                );

                DiffManager.getInstance().showDiff(proj, request);
            } catch (Exception e) {
                LOG.warn("Failed to show diff for edit " + editId, e);
            }
        });
    }

    // ==================== Internal ====================

    private void addHighlights(PendingEdit edit) {
        VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(edit.getFilePath());
        if (vf == null) return;

        Document doc = FileDocumentManager.getInstance().getDocument(vf);
        if (doc == null) return;

        com.intellij.openapi.fileEditor.FileEditor fe =
            FileEditorManager.getInstance(project).getSelectedEditor(vf);
        if (!(fe instanceof TextEditor)) return;

        Editor editor = ((TextEditor) fe).getEditor();
        MarkupModel markup = editor.getMarkupModel();

        int startLine = Math.max(0, edit.getStartLine());
        int endLine = Math.min(doc.getLineCount(), edit.getEndLine());

        for (int line = startLine; line < endLine; line++) {
            int startOff = doc.getLineStartOffset(line);
            int endOff = doc.getLineEndOffset(line);

            TextAttributes attrs = new TextAttributes();
            attrs.setBackgroundColor(COLOR_ADDED);
            attrs.setErrorStripeColor(COLOR_GUTTER);

            RangeHighlighter hl = markup.addRangeHighlighter(
                startOff, endOff,
                HighlighterLayer.SELECTION - 1,
                attrs,
                HighlighterTargetArea.LINES_IN_RANGE
            );
            hl.setGutterIconRenderer(new EditGutterIcon(edit.getId()));
            edit.getHighlighters().add(hl);
        }
    }

    private void removeHighlights(PendingEdit edit) {
        ApplicationManager.getApplication().invokeLater(() -> {
            VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(edit.getFilePath());
            if (vf == null) return;

            com.intellij.openapi.fileEditor.FileEditor fe =
                FileEditorManager.getInstance(project).getSelectedEditor(vf);
            if (!(fe instanceof TextEditor)) return;

            Editor editor = ((TextEditor) fe).getEditor();
            MarkupModel markup = editor.getMarkupModel();

            for (RangeHighlighter hl : edit.getHighlighters()) {
                markup.removeHighlighter(hl);
            }
            edit.getHighlighters().clear();
        });
    }

    private void revertEdit(PendingEdit edit) {
        VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(edit.getFilePath());
        if (vf == null) return;

        Document doc = FileDocumentManager.getInstance().getDocument(vf);
        if (doc == null) return;

        WriteCommandAction.runWriteCommandAction(project, "Revert Claude Edit", null, () -> {
            int startLine = Math.max(0, edit.getStartLine());
            int endLine = Math.min(doc.getLineCount(), edit.getEndLine());

            if (startLine < endLine) {
                int startOff = doc.getLineStartOffset(startLine);
                int endOff = endLine < doc.getLineCount()
                    ? doc.getLineStartOffset(endLine)
                    : doc.getTextLength();
                doc.replaceString(startOff, endOff, edit.getOriginalContent());
            }
        });
    }

    private void checkAllResolved() {
        if (getPendingCount() == 0) {
            for (EditDecisionListener listener : listeners) {
                listener.onAllEditsResolved();
            }
        }
    }

    /**
     * Gutter icon for pending edits showing accept/reject markers.
     */
    private static class EditGutterIcon extends GutterIconRenderer {
        private final String editId;

        EditGutterIcon(String editId) {
            this.editId = editId;
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof EditGutterIcon && ((EditGutterIcon) obj).editId.equals(editId);
        }

        @Override
        public int hashCode() {
            return editId.hashCode();
        }

        @Override
        public javax.swing.Icon getIcon() {
            return com.intellij.icons.AllIcons.General.Modified;
        }

        @Override
        public String getTooltipText() {
            return "Claude edit (pending review)";
        }

        @Override
        public Alignment getAlignment() {
            return Alignment.LEFT;
        }
    }
}
