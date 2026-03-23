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
    private JSpinner maxTokensSpinner;
    private JTextField systemPromptField;
    private JSpinner sessionHistoryLimitSpinner;

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

        // CLI status indicator
        cliStatusLabel = new JBLabel();
        cliStatusLabel.setForeground(UIUtil.getContextHelpForeground());
        updateCliStatus();

        // Model combo with common options
        modelCombo = new JComboBox<>(new String[]{
                "default",
                "sonnet",
                "opus",
                "haiku",
                "claude-sonnet-4-20250514",
                "claude-opus-4-20250514"
        });
        modelCombo.setEditable(true); // Allow custom model names

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

        // Max tokens spinner
        maxTokensSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 200000, 1000));
        ((JSpinner.DefaultEditor) maxTokensSpinner.getEditor()).getTextField().setColumns(8);

        // System prompt
        systemPromptField = new JTextField();
        systemPromptField.setToolTipText("Custom system prompt appended to the default (leave empty for none)");

        // Session history limit
        sessionHistoryLimitSpinner = new JSpinner(new SpinnerNumberModel(100, 10, 10000, 10));
        ((JSpinner.DefaultEditor) sessionHistoryLimitSpinner.getEditor()).getTextField().setColumns(6);

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
                .addSeparator()
                .addComponent(autosaveCheckbox)
                .addComponent(ctrlEnterCheckbox)
                .addComponent(respectGitIgnoreCheckbox)
                .addComponent(showCostCheckbox)
                .addComponent(showStreamingCheckbox)
                .addComponent(autoSaveBeforeToolsCheckbox)
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
                || ((Integer) maxTokensSpinner.getValue()) != state.maxTokens
                || !systemPromptField.getText().trim().equals(state.systemPrompt)
                || ((Integer) sessionHistoryLimitSpinner.getValue()) != state.sessionHistoryLimit
                || apiKeyModified;
    }

    @Override
    public void apply() throws ConfigurationException {
        ClaudeSettings.State state = ClaudeSettings.getInstance().getState();
        if (state == null) return;

        state.cliPath = cliPathField.getText().trim();
        state.selectedModel = getSelectedItem(modelCombo);
        state.initialPermissionMode = getSelectedItem(permissionModeCombo);
        state.autosave = autosaveCheckbox.isSelected();
        state.useCtrlEnterToSend = ctrlEnterCheckbox.isSelected();
        state.respectGitIgnore = respectGitIgnoreCheckbox.isSelected();
        state.showCost = showCostCheckbox.isSelected();
        state.showStreaming = showStreamingCheckbox.isSelected();
        state.autoSaveBeforeTools = autoSaveBeforeToolsCheckbox.isSelected();
        state.maxTokens = (Integer) maxTokensSpinner.getValue();
        state.systemPrompt = systemPromptField.getText().trim();
        state.sessionHistoryLimit = (Integer) sessionHistoryLimitSpinner.getValue();

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
        maxTokensSpinner.setValue(state.maxTokens);
        systemPromptField.setText(state.systemPrompt);
        sessionHistoryLimitSpinner.setValue(state.sessionHistoryLimit);

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
        maxTokensSpinner = null;
        systemPromptField = null;
        sessionHistoryLimitSpinner = null;
    }

    private static String getSelectedItem(JComboBox<String> combo) {
        Object item = combo.getSelectedItem();
        return item != null ? item.toString() : "";
    }
}
