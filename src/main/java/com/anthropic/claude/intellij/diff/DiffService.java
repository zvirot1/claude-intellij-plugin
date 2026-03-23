package com.anthropic.claude.intellij.diff;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.contents.DiffContent;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Project-level service for showing file diffs using IntelliJ's built-in diff viewer.
 * <p>
 * Wraps {@link DiffManager} and {@link DiffContentFactory} to present a side-by-side
 * comparison of original and modified file contents.
 */
public class DiffService {

    private static final Logger LOG = Logger.getInstance(DiffService.class);

    private final Project project;

    public DiffService(@NotNull Project project) {
        this.project = project;
    }

    /**
     * Returns the per-project singleton instance.
     */
    public static DiffService getInstance(@NotNull Project project) {
        return project.getService(DiffService.class);
    }

    /**
     * Opens IntelliJ's diff viewer to compare original and modified content.
     *
     * @param filePath        the file path displayed in the diff title; may be null
     * @param originalContent the original (left-side) content
     * @param modifiedContent the modified (right-side) content
     */
    public void showDiff(@Nullable String filePath,
                         @NotNull String originalContent,
                         @NotNull String modifiedContent) {
        showDiff(project, filePath, originalContent, modifiedContent);
    }

    /**
     * Opens IntelliJ's diff viewer to compare original and modified content
     * for the given project.
     *
     * @param project         the project context
     * @param filePath        the file path displayed in the diff title; may be null
     * @param originalContent the original (left-side) content
     * @param modifiedContent the modified (right-side) content
     */
    public static void showDiff(@NotNull Project project,
                                @Nullable String filePath,
                                @NotNull String originalContent,
                                @NotNull String modifiedContent) {
        try {
            DiffContentFactory contentFactory = DiffContentFactory.getInstance();

            DiffContent left = contentFactory.create(project, originalContent);
            DiffContent right = contentFactory.create(project, modifiedContent);

            String title = filePath != null
                    ? "Claude Diff: " + filePath
                    : "Claude Diff";

            SimpleDiffRequest diffRequest = new SimpleDiffRequest(
                    title,
                    left,
                    right,
                    "Original",
                    "Modified (Claude)"
            );

            DiffManager.getInstance().showDiff(project, diffRequest);
        } catch (Exception e) {
            LOG.error("Failed to show diff" + (filePath != null ? " for " + filePath : ""), e);
        }
    }
}
