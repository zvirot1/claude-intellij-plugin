package com.anthropic.claude.intellij.settings;

import com.anthropic.claude.intellij.cli.ClaudeCliManager;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * Settings UI page for the Claude Code plugin.
 * Appears under Settings &gt; Tools &gt; Claude Code.
 */
public class ClaudeSettingsConfigurable implements Configurable {

    private TextFieldWithBrowseButton cliPathField;
    private JBLabel cliStatusLabel;
    private JComboBox<String> modelCombo;
    private JComboBox<String> permissionModeCombo;
    private JBPasswordField apiKeyField;
    private JBCheckBox autosaveCheckbox;
    private JBCheckBox ctrlEnterCheckbox;
    private JBCheckBox respectGitIgnoreCheckbox;
    private JBCheckBox showCostCheckbox;
    private JBCheckBox showStreamingCheckbox;
    private JBCheckBox autoSaveBeforeToolsCheckbox;
    private JBCheckBox diagnosticLoggingCheckbox;
    private TextFieldWithBrowseButton skillsFolderField;
    private JSpinner maxTokensSpinner;
    private JTextField systemPromptField;
    private JSpinner sessionHistoryLimitSpinner;
    private JComboBox<String> tabTitleStrategyCombo;

    /** User-facing labels for the four tab-title strategies, in display order. */
    private static final String[] TAB_TITLE_LABELS = {
            "Self-generated topic (recommended)",
            "First message (no LLM call)",
            "CLI auto-summary (free, kicks in after several turns)",
            "Hybrid: self-generated, upgrade to CLI summary later"
    };
    /** Internal keys persisted in settings, in the same order as {@link #TAB_TITLE_LABELS}. */
    private static final String[] TAB_TITLE_KEYS = {
            "self_generated", "first_message", "cli_summary", "hybrid"
    };

    private static String tabTitleKeyFromLabel(String label) {
        for (int i = 0; i < TAB_TITLE_LABELS.length; i++) {
            if (TAB_TITLE_LABELS[i].equals(label)) return TAB_TITLE_KEYS[i];
        }
        return "self_generated";
    }
    private static String tabTitleLabelFromKey(String key) {
        if (key == null) return TAB_TITLE_LABELS[0];
        for (int i = 0; i < TAB_TITLE_KEYS.length; i++) {
            if (TAB_TITLE_KEYS[i].equals(key)) return TAB_TITLE_LABELS[i];
        }
        return TAB_TITLE_LABELS[0];
    }

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "Claude Code";
    }

    @Override
    public @Nullable JComponent createComponent() {
        // CLI Path with Browse button
        cliPathField = new TextFieldWithBrowseButton();
        cliPathField.addBrowseFolderListener(
                "Select Claude CLI",
                "Select the path to the Claude CLI executable",
                null,
                FileChooserDescriptorFactory.createSingleFileDescriptor()
        );
        // TextFieldWithBrowseButton uses JTextField (not JBTextField), so set tooltip instead
        cliPathField.getTextField().setToolTipText("Auto-detect (leave empty)");

        // Skills folder picker — shown in the Skills & Plugins dialog
        skillsFolderField = new TextFieldWithBrowseButton();
        skillsFolderField.addBrowseFolderListener(
                "Select Skills Folder",
                "Folder scanned by the Skills & Plugins dialog (default: ~/.claude/skills/)",
                null,
                FileChooserDescriptorFactory.createSingleFolderDescriptor()
        );
        skillsFolderField.getTextField().setToolTipText("Default: ~/.claude/skills/ (leave empty)");

        // CLI status indicator
        cliStatusLabel = new JBLabel();
        cliStatusLabel.setForeground(UIUtil.getContextHelpForeground());
        updateCliStatus();

        // Model combo with common options + user-added custom models
        modelCombo = new JComboBox<>(new String[]{
                "default",
                "claude-sonnet-4-6",
                "claude-opus-4-6",
                "claude-haiku-4-5",
                "sonnet",
                "opus",
                "haiku"
        });
        modelCombo.setEditable(true); // Allow custom model names
        // Load persisted custom models into the combo
        loadCustomModels();

        // Permission mode combo
        permissionModeCombo = new JComboBox<>(new String[]{
                "default",
                "plan",
                "acceptEdits",
                "bypassPermissions"
        });

        // API Key (secure)
        apiKeyField = new JBPasswordField();
        apiKeyField.getEmptyText().setText("Uses OAuth if empty");

        // Checkboxes
        autosaveCheckbox = new JBCheckBox("Auto-save files after Claude edits them");
        ctrlEnterCheckbox = new JBCheckBox("Use Ctrl+Enter (⌘+Enter) to send messages instead of Enter");
        respectGitIgnoreCheckbox = new JBCheckBox("Respect .gitignore when searching files");
        showCostCheckbox = new JBCheckBox("Show cost in status bar");
        showStreamingCheckbox = new JBCheckBox("Show streaming output in real time");
        autoSaveBeforeToolsCheckbox = new JBCheckBox("Auto-save dirty editors before tool execution");
        diagnosticLoggingCheckbox = new JBCheckBox("Enable diagnostic logging (verbose [DIAG-*] entries — only for bug investigation)");

        // Max tokens spinner
        maxTokensSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 200000, 1000));
        ((JSpinner.DefaultEditor) maxTokensSpinner.getEditor()).getTextField().setColumns(8);

        // System prompt
        systemPromptField = new JTextField();
        systemPromptField.setToolTipText("Custom system prompt appended to the default (leave empty for none)");

        // Session history limit
        sessionHistoryLimitSpinner = new JSpinner(new SpinnerNumberModel(100, 10, 10000, 10));
        ((JSpinner.DefaultEditor) sessionHistoryLimitSpinner.getEditor()).getTextField().setColumns(6);

        // Tab title strategy
        tabTitleStrategyCombo = new JComboBox<>(TAB_TITLE_LABELS);

        // Description labels
        JBLabel cliDesc = createDescriptionLabel("Path to the 'claude' CLI binary. Leave empty to auto-detect.");
        JBLabel modelDesc = createDescriptionLabel("Model for new conversations. You can also type a custom model name.");
        JBLabel permDesc = createDescriptionLabel("default = ask for permissions, plan = plan mode, acceptEdits = auto-accept file edits, bypassPermissions = skip all permission prompts.");
        JBLabel apiDesc = createDescriptionLabel("Anthropic API key (stored securely in system keychain). Leave empty to use OAuth authentication.");

        // Build the form
        JPanel panel = FormBuilder.createFormBuilder()
                .addLabeledComponent("CLI Path:", cliPathField)
                .addComponentToRightColumn(cliDesc)
                .addComponentToRightColumn(cliStatusLabel)
                .addSeparator()
                .addLabeledComponent("API Key:", apiKeyField)
                .addComponentToRightColumn(apiDesc)
                .addSeparator()
                .addLabeledComponent("Model:", modelCombo)
                .addComponentToRightColumn(modelDesc)
                .addLabeledComponent("Permission Mode:", permissionModeCombo)
                .addComponentToRightColumn(permDesc)
                .addSeparator()
                .addLabeledComponent("Max Tokens:", maxTokensSpinner)
                .addComponentToRightColumn(createDescriptionLabel("Maximum tokens for responses (0 = CLI default)."))
                .addLabeledComponent("System Prompt:", systemPromptField)
                .addComponentToRightColumn(createDescriptionLabel("Custom prompt appended to the default system prompt."))
                .addLabeledComponent("Session History Limit:", sessionHistoryLimitSpinner)
                .addComponentToRightColumn(createDescriptionLabel("Maximum sessions to keep in history."))
                .addLabeledComponent("Tab Title Strategy:", tabTitleStrategyCombo)
                .addComponentToRightColumn(createDescriptionLabel("How new tabs get their title. Self-generated runs a small claude -p call once per new tab to produce a topic title."))
                .addSeparator()
                .addComponent(autosaveCheckbox)
                .addComponent(ctrlEnterCheckbox)
                .addComponent(respectGitIgnoreCheckbox)
                .addComponent(showCostCheckbox)
                .addComponent(showStreamingCheckbox)
                .addComponent(autoSaveBeforeToolsCheckbox)
                .addComponent(diagnosticLoggingCheckbox)
                .addLabeledComponent("Skills folder:", skillsFolderField)
                .addComponentToRightColumn(createDescriptionLabel("Folder scanned by Skills & Plugins. Leave empty for default ~/.claude/skills/."))
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();

        reset();
        return panel;
    }

    private JBLabel createDescriptionLabel(String text) {
        JBLabel label = new JBLabel(text);
        label.setForeground(UIUtil.getContextHelpForeground());
        label.setFont(UIUtil.getLabelFont(UIUtil.FontSize.SMALL));
        label.setBorder(JBUI.Borders.emptyBottom(4));
        return label;
    }

    private void updateCliStatus() {
        if (cliStatusLabel == null) return;
        String detectedPath = ClaudeCliManager.getCliPath();
        boolean hasOAuth = ClaudeCliManager.isOAuthAuthenticated();
        boolean hasApiKey = SecureApiKeyStore.hasApiKey();

        StringBuilder status = new StringBuilder();
        if (detectedPath != null) {
            status.append("✓ CLI found: ").append(detectedPath);
        } else {
            status.append("✗ CLI not found. Install with: npm install -g @anthropic-ai/claude-code");
        }

        if (hasOAuth) {
            status.append("  |  Auth: OAuth ✓");
        } else if (hasApiKey) {
            status.append("  |  Auth: API Key ✓");
        } else {
            status.append("  |  Auth: Not configured");
        }

        cliStatusLabel.setText(status.toString());
        cliStatusLabel.setForeground(detectedPath != null
                ? new Color(115, 201, 145)  // green
                : new Color(244, 135, 113)); // red
    }

    @Override
    public boolean isModified() {
        ClaudeSettings.State state = ClaudeSettings.getInstance().getState();
        if (state == null) return false;

        boolean apiKeyModified = false;
        String currentApiKey = SecureApiKeyStore.getApiKey();
        String enteredApiKey = new String(apiKeyField.getPassword()).trim();
        if (currentApiKey == null) currentApiKey = "";
        apiKeyModified = !enteredApiKey.isEmpty() && !enteredApiKey.equals(currentApiKey);

        return !cliPathField.getText().equals(state.cliPath)
                || !getSelectedItem(modelCombo).equals(state.selectedModel)
                || !getSelectedItem(permissionModeCombo).equals(state.initialPermissionMode)
                || autosaveCheckbox.isSelected() != state.autosave
                || ctrlEnterCheckbox.isSelected() != state.useCtrlEnterToSend
                || respectGitIgnoreCheckbox.isSelected() != state.respectGitIgnore
                || showCostCheckbox.isSelected() != state.showCost
                || showStreamingCheckbox.isSelected() != state.showStreaming
                || autoSaveBeforeToolsCheckbox.isSelected() != state.autoSaveBeforeTools
                || diagnosticLoggingCheckbox.isSelected() != state.diagnosticLogging
                || !skillsFolderField.getText().trim().equals(state.skillsFolder == null ? "" : state.skillsFolder)
                || ((Integer) maxTokensSpinner.getValue()) != state.maxTokens
                || !systemPromptField.getText().trim().equals(state.systemPrompt)
                || ((Integer) sessionHistoryLimitSpinner.getValue()) != state.sessionHistoryLimit
                || !tabTitleKeyFromLabel(getSelectedItem(tabTitleStrategyCombo)).equals(
                        state.tabTitleStrategy == null ? "self_generated" : state.tabTitleStrategy)
                || apiKeyModified;
    }

    @Override
    public void apply() throws ConfigurationException {
        ClaudeSettings.State state = ClaudeSettings.getInstance().getState();
        if (state == null) return;

        state.cliPath = cliPathField.getText().trim();
        state.selectedModel = getSelectedItem(modelCombo);
        saveCustomModelIfNew(state.selectedModel);
        state.initialPermissionMode = getSelectedItem(permissionModeCombo);
        state.autosave = autosaveCheckbox.isSelected();
        state.useCtrlEnterToSend = ctrlEnterCheckbox.isSelected();
        state.respectGitIgnore = respectGitIgnoreCheckbox.isSelected();
        state.showCost = showCostCheckbox.isSelected();
        state.showStreaming = showStreamingCheckbox.isSelected();
        state.autoSaveBeforeTools = autoSaveBeforeToolsCheckbox.isSelected();
        state.diagnosticLogging = diagnosticLoggingCheckbox.isSelected();
        // Apply DIAG flag immediately so toggling takes effect without restart
        com.anthropic.claude.intellij.service.ClaudeApplicationService.DIAG_ENABLED =
            state.diagnosticLogging || Boolean.getBoolean("claude.diag");
        state.skillsFolder = skillsFolderField.getText().trim();
        state.maxTokens = (Integer) maxTokensSpinner.getValue();
        state.systemPrompt = systemPromptField.getText().trim();
        state.sessionHistoryLimit = (Integer) sessionHistoryLimitSpinner.getValue();
        state.tabTitleStrategy = tabTitleKeyFromLabel(getSelectedItem(tabTitleStrategyCombo));

        // Save API key securely
        String apiKey = new String(apiKeyField.getPassword()).trim();
        if (!apiKey.isEmpty()) {
            SecureApiKeyStore.setApiKey(apiKey);
        }

        // Update CLI status after applying
        updateCliStatus();
    }

    @Override
    public void reset() {
        ClaudeSettings.State state = ClaudeSettings.getInstance().getState();
        if (state == null) return;

        cliPathField.setText(state.cliPath);
        modelCombo.setSelectedItem(state.selectedModel);
        permissionModeCombo.setSelectedItem(state.initialPermissionMode);
        autosaveCheckbox.setSelected(state.autosave);
        ctrlEnterCheckbox.setSelected(state.useCtrlEnterToSend);
        respectGitIgnoreCheckbox.setSelected(state.respectGitIgnore);
        showCostCheckbox.setSelected(state.showCost);
        showStreamingCheckbox.setSelected(state.showStreaming);
        autoSaveBeforeToolsCheckbox.setSelected(state.autoSaveBeforeTools);
        diagnosticLoggingCheckbox.setSelected(state.diagnosticLogging);
        skillsFolderField.setText(state.skillsFolder == null ? "" : state.skillsFolder);
        maxTokensSpinner.setValue(state.maxTokens);
        systemPromptField.setText(state.systemPrompt);
        sessionHistoryLimitSpinner.setValue(state.sessionHistoryLimit);
        tabTitleStrategyCombo.setSelectedItem(tabTitleLabelFromKey(state.tabTitleStrategy));

        // Show masked indicator if API key exists (don't load the actual key)
        if (SecureApiKeyStore.hasApiKey()) {
            apiKeyField.setText("••••••••");
        } else {
            apiKeyField.setText("");
        }

        updateCliStatus();
    }

    @Override
    public void disposeUIResources() {
        cliPathField = null;
        cliStatusLabel = null;
        modelCombo = null;
        permissionModeCombo = null;
        apiKeyField = null;
        autosaveCheckbox = null;
        ctrlEnterCheckbox = null;
        respectGitIgnoreCheckbox = null;
        showCostCheckbox = null;
        showStreamingCheckbox = null;
        autoSaveBeforeToolsCheckbox = null;
        diagnosticLoggingCheckbox = null;
        skillsFolderField = null;
        maxTokensSpinner = null;
        systemPromptField = null;
        sessionHistoryLimitSpinner = null;
        tabTitleStrategyCombo = null;
    }

    /** Built-in model names that should NOT be persisted as custom models. */
    private static final java.util.Set<String> BUILTIN_MODELS = java.util.Set.of(
            "default", "claude-sonnet-4-6", "claude-opus-4-6", "claude-haiku-4-5",
            // Legacy names (still recognized by CLI)
            "sonnet", "opus", "haiku"
    );

    /**
     * Loads user-added custom model names from settings into the combo box.
     */
    private void loadCustomModels() {
        ClaudeSettings.State state = ClaudeSettings.getInstance().getState();
        if (state == null || state.customModels == null || state.customModels.isEmpty()) return;
        for (String model : state.customModels.split(",")) {
            String trimmed = model.trim();
            if (!trimmed.isEmpty() && !isInCombo(modelCombo, trimmed)) {
                modelCombo.addItem(trimmed);
            }
        }
    }

    /**
     * If the selected model is custom (not built-in), add it to the combo and persist it.
     */
    private void saveCustomModelIfNew(String model) {
        if (model == null || model.isEmpty() || BUILTIN_MODELS.contains(model)) return;
        // Add to combo if not already there
        if (!isInCombo(modelCombo, model)) {
            modelCombo.addItem(model);
        }
        // Persist in settings
        ClaudeSettings.State state = ClaudeSettings.getInstance().getState();
        if (state == null) return;
        java.util.Set<String> customs = new java.util.LinkedHashSet<>();
        if (state.customModels != null && !state.customModels.isEmpty()) {
            for (String m : state.customModels.split(",")) {
                String t = m.trim();
                if (!t.isEmpty()) customs.add(t);
            }
        }
        customs.add(model);
        state.customModels = String.join(",", customs);
    }

    private static boolean isInCombo(JComboBox<String> combo, String value) {
        for (int i = 0; i < combo.getItemCount(); i++) {
            if (value.equals(combo.getItemAt(i))) return true;
        }
        return false;
    }

    private static String getSelectedItem(JComboBox<String> combo) {
        Object item = combo.getSelectedItem();
        return item != null ? item.toString().trim() : "";
    }
}
