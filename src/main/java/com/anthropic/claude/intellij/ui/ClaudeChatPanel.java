package com.anthropic.claude.intellij.ui;

import com.anthropic.claude.intellij.cli.ClaudeCliManager;
import com.anthropic.claude.intellij.cli.CliProcessConfig;
import com.anthropic.claude.intellij.cli.ICliStateListener;
import com.anthropic.claude.intellij.handlers.SlashCommandHandler;
import com.anthropic.claude.intellij.model.ConversationModel;
import com.anthropic.claude.intellij.model.IConversationListener;
import com.anthropic.claude.intellij.model.MessageBlock;
import com.anthropic.claude.intellij.model.SessionInfo;
import com.anthropic.claude.intellij.model.UsageInfo;
import com.anthropic.claude.intellij.settings.ClaudeSettings;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.URL;
import java.nio.file.*;

/**
 * Hosts a JBCefBrowser displaying the Claude chat UI.
 * Creates the JCEF browser, sets up the WebviewBridge for Java/JS communication,
 * and connects to the CLI manager and ConversationModel to wire all events.
 */
public class ClaudeChatPanel implements Disposable {

    private static final Logger LOG = Logger.getInstance(ClaudeChatPanel.class);

    private final Project project;
    private final JPanel rootPanel;
    private JBCefBrowser browser;
    private WebviewBridge bridge;
    private ClaudeCliManager cliManager;
    private ConversationModel conversationModel;
    private com.anthropic.claude.intellij.session.ClaudeSessionManager sessionManager;
    private AttachmentManager attachmentManager;
    private volatile boolean webviewReady = false;
    private boolean tabNameSet = false;
    /** Maps control_request requestId → original toolInput for passing back in control_response. */
    private final java.util.concurrent.ConcurrentHashMap<String, Object> pendingToolInputs = new java.util.concurrent.ConcurrentHashMap<>();
    private IConversationListener conversationListener;
    private com.anthropic.claude.intellij.cli.ICliStateListener cliStateListener;

    public ClaudeChatPanel(Project project) {
        this.project = project;
        this.rootPanel = new JPanel(new BorderLayout());

        if (!JBCefApp.isSupported()) {
            JLabel fallbackLabel = new JLabel(
                "<html><center>" +
                "<h2>JCEF is not available</h2>" +
                "<p>The embedded browser (JCEF) is required for the Claude chat panel.</p>" +
                "<p>Please use an IntelliJ-based IDE that supports JCEF,<br>" +
                "or check your IDE configuration.</p>" +
                "</center></html>",
                SwingConstants.CENTER
            );
            fallbackLabel.setFont(fallbackLabel.getFont().deriveFont(14f));
            rootPanel.add(fallbackLabel, BorderLayout.CENTER);
            return;
        }

        initBrowser();
        initCliManager();
        initSessionManager();
        initThemeListener();
        initSelectionListener();
        attachmentManager = new AttachmentManager(project);

        Disposer.register(this, () -> cleanup());
    }

    private void initBrowser() {
        browser = new JBCefBrowser();

        // Set up the bridge for Java<->JS communication
        bridge = new WebviewBridge(browser);
        bridge.setMessageHandler(this::handleWebviewMessage);

        // Load the HTML once the browser is ready
        browser.getJBCefClient().addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadEnd(CefBrowser cefBrowser, CefFrame frame, int httpStatusCode) {
                if (frame.isMain()) {
                    webviewReady = true;
                    LOG.info("Webview loaded successfully (status: " + httpStatusCode + ")");
                    // Push initial state to the webview
                    pushInitialState();
                }
            }

            @Override
            public void onLoadError(CefBrowser cefBrowser, CefFrame frame, ErrorCode errorCode,
                                    String errorText, String failedUrl) {
                LOG.error("Webview load error: " + errorCode + " - " + errorText + " url=" + failedUrl);
            }
        }, browser.getCefBrowser());

        // Extract webview resources from JAR to temp directory so JCEF can load them
        // (JCEF cannot resolve relative CSS/JS paths from jar: URLs)
        try {
            Path tempDir = extractWebviewResources();
            if (tempDir != null) {
                Path htmlFile = tempDir.resolve("index.html");
                browser.loadURL(htmlFile.toUri().toString());
                LOG.info("Webview loaded from temp dir: " + tempDir);
            } else {
                throw new IOException("Failed to extract webview resources");
            }
        } catch (IOException e) {
            LOG.error("Could not load webview resources", e);
            browser.loadHTML(
                "<html><body style='background:#1e1e1e;color:#ccc;font-family:sans-serif;padding:40px;text-align:center'>" +
                "<h2>Error: Missing webview resources</h2>" +
                "<p>Could not load the chat interface: " + e.getMessage() + "</p>" +
                "</body></html>"
            );
        }

        rootPanel.add(browser.getComponent(), BorderLayout.CENTER);
    }

    private void initCliManager() {
        // Use the project-service singletons so that StatusBar widget and other
        // components share the same CliManager / ConversationModel instances.
        com.anthropic.claude.intellij.service.ClaudeProjectService svc =
            com.anthropic.claude.intellij.service.ClaudeProjectService.getInstance(project);
        cliManager = svc.getCliManager();
        conversationModel = svc.getConversationModel();

        // Wire the conversation model as a CLI message listener
        cliManager.addMessageListener(conversationModel);

        // Wire EditDecisionManager with the project reference
        com.anthropic.claude.intellij.service.ClaudeProjectService.getInstance(project)
            .getEditDecisionManager().setProject(project);

        // Listen to conversation model events and push them to the webview
        conversationListener = new IConversationListener() {
            @Override
            public void onSessionInitialized(SessionInfo info) {
                sendToWebview("session_initialized", buildSessionInfoJson(info));
                // Start tracking this session for auto-save
                if (info != null && info.getSessionId() != null) {
                    sessionManager.startNewSession(info.getWorkingDirectory());
                }
            }

            @Override
            public void onUserMessageAdded(MessageBlock block) {
                sendToWebview("user_message_added", buildMessageBlockJson(block));
            }

            @Override
            public void onAssistantMessageStarted(MessageBlock block) {
                sendToWebview("assistant_message_started", buildMessageBlockJson(block));
                startStreamingTimeout(); // begin 45s watchdog
            }

            @Override
            public void onStreamingTextAppended(MessageBlock block, String delta) {
                sendToWebview("streaming_text_appended", "{\"delta\":" + jsonString(delta) + "}");
                touchStreamActivity(); // reset streaming timeout
            }

            @Override
            public void onToolCallStarted(MessageBlock block, MessageBlock.ToolCallSegment toolCall) {
                sendToWebview("tool_call_started", buildToolCallJson(toolCall));
                touchStreamActivity(); // reset streaming timeout
                // Auto-save dirty editors before tool execution
                if (com.anthropic.claude.intellij.settings.ClaudeSettings.getInstance().getState().autoSaveBeforeTools) {
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() ->
                        com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments()
                    );
                }
            }

            @Override
            public void onToolCallInputComplete(MessageBlock block, MessageBlock.ToolCallSegment toolCall) {
                touchStreamActivity(); // reset streaming timeout
                // Snapshot file BEFORE tool executes (checkpoint for revert).
                // We snapshot here because the tool name + file_path are now known,
                // but the CLI hasn't executed the tool yet (this fires when input JSON is complete).
                // NOTE: For some CLI modes the file may already be written. As a safety net
                // we also snapshot eagerly on first input delta (see onToolCallInputDelta).
                snapshotForCheckpoint(toolCall);
            }

            @Override
            public void onToolCallInputDelta(MessageBlock block, MessageBlock.ToolCallSegment toolCall, String delta) {
                sendToWebview("tool_call_input_delta", "{\"toolId\":" + jsonString(toolCall.getToolId()) +
                    ",\"delta\":" + jsonString(delta) + "}");
                touchStreamActivity(); // reset streaming timeout
                // Eagerly snapshot on first delta — ensures we capture file content
                // before the CLI writes, even if input_complete arrives too late.
                snapshotForCheckpointEager(toolCall);
            }

            @Override
            public void onToolCallCompleted(MessageBlock block, MessageBlock.ToolCallSegment toolCall) {
                sendToWebview("tool_call_completed", buildToolCallJson(toolCall));
                // Refresh VFS so IntelliJ picks up file changes made by CLI tools (Edit, Write, Bash)
                refreshProjectFiles();
                // Stage completed edits for accept/reject review
                stageCompletedEdit(toolCall);
            }

            @Override
            public void onAssistantMessageCompleted(MessageBlock block) {
                sendToWebview("assistant_message_completed", buildMessageBlockJson(block));
                cancelStreamingTimeout(); // streaming is done
            }

            @Override
            public void onResultReceived(UsageInfo usage) {
                sendToWebview("result_received", buildUsageJson(usage));
                cancelStreamingTimeout(); // result received, done
                // Auto-save session state after each turn completes
                try {
                    sessionManager.saveCurrentSession(conversationModel);
                } catch (Exception e) {
                    LOG.warn("Failed to auto-save session", e);
                }
            }

            @Override
            public void onPermissionRequested(String toolUseId, String toolName, String description,
                                              String requestId, Object toolInput) {
                // Store original tool input so we can pass it back in control_response
                if (requestId != null && toolInput != null) {
                    pendingToolInputs.put(requestId, toolInput);
                }
                StringBuilder json = new StringBuilder("{");
                json.append("\"toolUseId\":").append(jsonString(toolUseId));
                json.append(",\"toolName\":").append(jsonString(toolName));
                json.append(",\"description\":").append(jsonString(description));
                if (requestId != null) {
                    json.append(",\"requestId\":").append(jsonString(requestId));
                }
                json.append("}");
                sendToWebview("permission_requested", json.toString());
            }

            @Override
            public void onExtendedThinkingStarted() {
                sendToWebview("extended_thinking_started", "{}");
            }

            @Override
            public void onExtendedThinkingEnded() {
                sendToWebview("extended_thinking_ended", "{}");
            }

            @Override
            public void onError(String error) {
                sendToWebview("error", "{\"message\":" + jsonString(error) + "}");
            }

            @Override
            public void onConversationCleared() {
                sendToWebview("conversation_cleared", "{}");
            }
        };
        conversationModel.addListener(conversationListener);

        // Listen to CLI state changes
        cliStateListener = new ICliStateListener() {
            @Override
            public void onStateChanged(ClaudeCliManager.ProcessState oldState,
                                       ClaudeCliManager.ProcessState newState) {
                switch (newState) {
                    case RUNNING:
                        sendToWebview("cli_state_changed", "{\"state\":\"connected\"}");
                        break;
                    case STOPPED:
                        sendToWebview("cli_state_changed", "{\"state\":\"disconnected\"}");
                        break;
                    case ERROR:
                        sendToWebview("cli_state_changed", "{\"state\":\"error\"}");
                        break;
                    default:
                        break;
                }
            }
        };
        cliManager.addStateListener(cliStateListener);
    }

    private void initSessionManager() {
        sessionManager = new com.anthropic.claude.intellij.session.ClaudeSessionManager();
    }

    /**
     * Registers a Look-and-Feel listener so the webview theme switches
     * automatically when the user changes the IDE theme (dark/light).
     */
    @SuppressWarnings("deprecation")
    private void initThemeListener() {
        com.intellij.ide.ui.LafManager.getInstance().addLafManagerListener(source -> {
            @SuppressWarnings("deprecation")
            boolean isDark = com.intellij.util.ui.UIUtil.isUnderDarcula();
            sendToWebview("set_theme", "{\"theme\":\"" + (isDark ? "dark" : "light") + "\"}");
        });
    }

    /**
     * Monitors editor selection changes and sends "N lines selected" indicator to webview.
     */
    private void initSelectionListener() {
        com.intellij.openapi.editor.EditorFactory.getInstance().getEventMulticaster()
            .addSelectionListener(new com.intellij.openapi.editor.event.SelectionListener() {
                @Override
                public void selectionChanged(com.intellij.openapi.editor.event.SelectionEvent e) {
                    com.intellij.openapi.editor.Editor editor = e.getEditor();
                    if (editor.getProject() != project) return;
                    String selectedText = editor.getSelectionModel().getSelectedText();
                    if (selectedText != null && !selectedText.isEmpty()) {
                        int lineCount = selectedText.split("\n", -1).length;
                        com.intellij.openapi.vfs.VirtualFile file = editor.getVirtualFile();
                        String fileName = file != null ? file.getName() : "untitled";
                        sendToWebview("selection_indicator",
                            "{\"lineCount\":" + lineCount + ",\"fileName\":" + jsonString(fileName) + "}");
                    } else {
                        sendToWebview("selection_indicator", "{\"lineCount\":0}");
                    }
                }
            }, this);
    }

    /**
     * Handles messages received from the webview JavaScript.
     */
    private void handleWebviewMessage(String type, String payload) {
        LOG.info("Message from webview: type=" + type);
        switch (type) {
            case "send_message":
                handleSendMessage(payload);
                break;
            case "stop_generation":
                handleStopGeneration();
                break;
            case "new_session":
                handleNewSession();
                break;
            case "accept_permission":
                handlePermissionResponse(payload, true);
                break;
            case "reject_permission":
                handlePermissionResponse(payload, false);
                break;
            case "webview_ready":
                webviewReady = true;
                pushInitialState();
                break;
            case "slash_suggestions":
                handleSlashSuggestions(payload);
                break;
            case "execute_slash_command":
                handleExecuteSlashCommand(payload);
                break;
            case "file_search":
                handleFileSearch(payload);
                break;
            case "open_dialog":
                handleOpenDialog(payload);
                break;
            case "clear_conversation":
                conversationModel.clear();
                break;
            case "always_allow_permission":
                handleAlwaysAllowPermission(payload);
                break;
            case "apply_to_editor":
                handleApplyToEditor(payload);
                break;
            case "insert_at_cursor":
                handleInsertAtCursor(payload);
                break;
            case "attach_file":
                handleAttachFile(payload);
                break;
            case "attach_file_dialog":
                handleAttachFileDialog();
                break;
            case "remove_attachment":
                handleRemoveAttachment(payload);
                break;
            case "new_tab":
                handleNewTab();
                break;
            case "view_diff":
                handleViewDiff(payload);
                break;
            case "accept_edit":
                handleAcceptEdit(payload);
                break;
            case "reject_edit":
                handleRejectEdit(payload);
                break;
            case "fork_from_message":
                handleForkFromMessage(payload);
                break;
            default:
                LOG.warn("Unknown message type from webview: " + type);
        }
    }

    private void handleViewDiff(String payload) {
        try {
            java.util.Map<String, Object> data = com.anthropic.claude.intellij.util.JsonParser.parseObject(payload);
            String editId = com.anthropic.claude.intellij.util.JsonParser.getString(data, "editId");
            com.anthropic.claude.intellij.diff.EditDecisionManager edm =
                com.anthropic.claude.intellij.service.ClaudeProjectService.getInstance(project).getEditDecisionManager();
            if (edm != null) {
                edm.viewDiff(project, editId);
            }
        } catch (Exception e) {
            LOG.warn("Failed to view diff", e);
        }
    }

    private void handleAcceptEdit(String payload) {
        try {
            java.util.Map<String, Object> data = com.anthropic.claude.intellij.util.JsonParser.parseObject(payload);
            String editId = com.anthropic.claude.intellij.util.JsonParser.getString(data, "editId");
            com.anthropic.claude.intellij.diff.EditDecisionManager edm =
                com.anthropic.claude.intellij.service.ClaudeProjectService.getInstance(project).getEditDecisionManager();
            if (edm != null) {
                edm.acceptEdit(editId);
                sendToast("Edit accepted");
            }
        } catch (Exception e) {
            LOG.warn("Failed to accept edit", e);
        }
    }

    private void handleRejectEdit(String payload) {
        try {
            java.util.Map<String, Object> data = com.anthropic.claude.intellij.util.JsonParser.parseObject(payload);
            String editId = com.anthropic.claude.intellij.util.JsonParser.getString(data, "editId");
            com.anthropic.claude.intellij.diff.EditDecisionManager edm =
                com.anthropic.claude.intellij.service.ClaudeProjectService.getInstance(project).getEditDecisionManager();
            if (edm != null) {
                edm.rejectEdit(editId);
                sendToast("Edit reverted");
                refreshProjectFiles();
            }
        } catch (Exception e) {
            LOG.warn("Failed to reject edit", e);
        }
    }

    /**
     * Forks the conversation from a specific message index.
     * Saves the current session, clears the model, replays messages
     * up to the fork point, and starts a fresh CLI session.
     */
    private void handleForkFromMessage(String payload) {
        try {
            java.util.Map<String, Object> data = com.anthropic.claude.intellij.util.JsonParser.parseObject(payload);
            Number indexNum = (Number) data.get("messageIndex");
            if (indexNum == null) return;
            int forkIndex = indexNum.intValue();

            java.util.List<MessageBlock> allMessages = conversationModel.getMessages();
            if (forkIndex < 0 || forkIndex >= allMessages.size()) {
                LOG.warn("Fork index out of range: " + forkIndex + " (messages: " + allMessages.size() + ")");
                return;
            }

            // Collect messages up to and including the fork point
            java.util.List<MessageBlock> forkedMessages = new java.util.ArrayList<>(
                allMessages.subList(0, forkIndex + 1));

            LOG.info("Forking conversation at message " + forkIndex + " (" + forkedMessages.size() + " messages)");

            // Save current session before forking
            com.anthropic.claude.intellij.service.ClaudeProjectService service =
                com.anthropic.claude.intellij.service.ClaudeProjectService.getInstance(project);
            service.getSessionManager().saveCurrentSession(conversationModel);

            // Stop current CLI
            if (cliManager.isRunning()) {
                cliManager.stop();
            }

            // Clear model — fires conversation_cleared → webview clears
            conversationModel.clear();
            cancelStreamingTimeout();
            service.getCheckpointManager().clearCheckpoints();
            service.getEditDecisionManager().clearAll();
            eagerSnapshotDone.clear();
            stagedEditDone.clear();
            tabNameSet = false;

            // Replay forked messages into the model (fires UI events → webview re-renders)
            conversationModel.loadHistory(forkedMessages);

            // Start fresh CLI
            startCli();

            sendToast("\u2442 Forked conversation (" + forkedMessages.size() + " messages)");
        } catch (Exception e) {
            LOG.warn("Failed to fork conversation", e);
            sendToast("Fork failed: " + e.getMessage());
        }
    }

    private void handleSendMessage(String payload) {
        try {
            java.util.Map<String, Object> data = com.anthropic.claude.intellij.util.JsonParser.parseObject(payload);
            String message = com.anthropic.claude.intellij.util.JsonParser.getString(data, "message");
            if (message == null || message.trim().isEmpty()) {
                return;
            }

            // Check if this is a slash command
            if (SlashCommandHandler.isSlashCommand(message)) {
                if (SlashCommandHandler.isLocalCommand(message)) {
                    handleLocalSlashCommand(message);
                    return;
                }
                // Non-local slash commands fall through to be sent to CLI
            }

            // If CLI is not running, start it
            if (!cliManager.isRunning()) {
                startCli();
            }

            // Prepend file context from attachments if any
            String fileContext = attachmentManager.buildFileContext();
            if (!fileContext.isEmpty()) {
                message = fileContext + "\n" + message;
                attachmentManager.clearAttachments();
                sendToWebview("attachments_cleared", "{}");
            }

            // Add the user message to the conversation model
            conversationModel.addUserMessage(message);

            // Set tab name from first user message (like Eclipse)
            if (!tabNameSet) {
                tabNameSet = true;
                String tabTitle = message.trim();
                if (tabTitle.length() > 30) tabTitle = tabTitle.substring(0, 30) + "\u2026";
                updateTabDisplayName(tabTitle);
            }

            // Check for attached images
            @SuppressWarnings("unchecked")
            java.util.List<Object> imagesList = (java.util.List<Object>) data.get("images");
            if (imagesList != null && !imagesList.isEmpty()) {
                java.util.List<byte[]> imageDataList = new java.util.ArrayList<>();
                for (Object img : imagesList) {
                    if (img instanceof String) {
                        try {
                            imageDataList.add(java.util.Base64.getDecoder().decode((String) img));
                        } catch (IllegalArgumentException e2) {
                            LOG.warn("Skipping invalid base64 image data", e2);
                        }
                    }
                }
                cliManager.sendRichMessage(message, imageDataList);
            } else {
                cliManager.sendMessage(message);
            }
        } catch (Exception e) {
            LOG.error("Error handling send_message from webview", e);
            sendToWebview("error", "{\"message\":" + jsonString("Failed to send message: " + e.getMessage()) + "}");
        }
    }

    /**
     * Handles slash commands that are processed locally by the plugin (not forwarded to CLI).
     */
    private void handleLocalSlashCommand(String input) {
        String cmd = SlashCommandHandler.getCommandName(input);
        String args = SlashCommandHandler.getCommandArgs(input);

        switch (cmd) {
            case "/help":
                showSystemMessage(SlashCommandHandler.formatHelp());
                break;

            case "/new":
                handleNewSession();
                break;

            case "/clear":
                conversationModel.clear();
                break;

            case "/stop":
                handleStopGeneration();
                break;

            case "/cost":
                handleCostCommand();
                break;

            case "/model":
                handleModelCommand(args);
                break;

            case "/compact":
                handleCompactCommand();
                break;

            case "/resume":
                handleResumeCommand(args);
                break;

            case "/history":
                ApplicationManager.getApplication().invokeLater(this::openSessionHistoryDialog);
                break;

            case "/rules":
                handleOpenDialog("{\"dialog\":\"rules\"}");
                break;

            case "/mcp":
                handleOpenDialog("{\"dialog\":\"mcp\"}");
                break;

            case "/hooks":
                handleOpenDialog("{\"dialog\":\"hooks\"}");
                break;

            case "/memory":
                handleOpenDialog("{\"dialog\":\"memory\"}");
                break;

            case "/skills":
                handleOpenDialog("{\"dialog\":\"skills\"}");
                break;

            default:
                showSystemMessage("Unknown command: `" + cmd + "`. Type `/help` for available commands.");
                break;
        }
    }

    private void handleCostCommand() {
        UsageInfo usage = conversationModel.getCumulativeUsage();
        if (usage.getTotalTokens() == 0) {
            showSystemMessage("No usage data yet. Send a message first.");
        } else {
            String costMsg = SlashCommandHandler.formatCost(
                usage.formatTokens(), usage.formatCost(),
                usage.formatDuration(), usage.getTotalTurns()
            );
            showSystemMessage(costMsg);
        }
    }

    private void handleModelCommand(String args) {
        LOG.info("handleModelCommand called with args: '" + args + "'");
        if (args.isEmpty()) {
            // Show current model and available options
            String currentModel = cliManager.getCurrentModel();
            if (currentModel == null || currentModel.isEmpty()) {
                currentModel = ClaudeSettings.getInstance().getState().selectedModel;
            }
            StringBuilder msg = new StringBuilder();
            msg.append("## Current Model\n\n");
            msg.append("Current model: **").append(currentModel != null ? currentModel : "default").append("**\n\n");
            msg.append("Usage: `/model <model-name>` to switch models.\n\n");
            msg.append("Examples:\n");
            msg.append("- `/model sonnet` — Claude Sonnet\n");
            msg.append("- `/model opus` — Claude Opus\n");
            msg.append("- `/model haiku` — Claude Haiku\n");
            msg.append("- `/model claude-sonnet-4-20250514` — Specific model version\n");
            showSystemMessage(msg.toString());
        } else {
            // Switch model: stop CLI and restart with new model
            String newModel = args.trim();
            ClaudeSettings.getInstance().getState().selectedModel = newModel;

            if (cliManager.isRunning()) {
                cliManager.stop();
            }
            conversationModel.clear();

            try {
                String projectPath = project.getBasePath();
                if (projectPath == null) {
                    projectPath = System.getProperty("user.home");
                }
                String cliPath = ClaudeCliManager.getCliPath();
                if (cliPath == null) {
                    sendToWebview("error", "{\"message\":" + jsonString("Claude CLI not found.") + "}");
                    return;
                }
                CliProcessConfig config = new CliProcessConfig.Builder(cliPath, projectPath)
                    .model(newModel)
                    .build();
                cliManager.start(config);
                showSystemMessage("Switched to model: **" + newModel + "**");
            } catch (Exception e) {
                LOG.error("Failed to restart CLI with new model", e);
                sendToWebview("error", "{\"message\":" + jsonString("Failed to switch model: " + e.getMessage()) + "}");
            }
        }
    }

    private void handleCompactCommand() {
        if (!cliManager.isRunning()) {
            showSystemMessage("CLI is not running. Send a message first.");
            return;
        }
        // Send /compact to CLI as a regular message — it handles it
        conversationModel.addUserMessage("/compact");
        cliManager.sendMessage("/compact");
    }

    /**
     * Shows a system-generated message in the chat (not from CLI, from the plugin itself).
     */
    private void showSystemMessage(String markdownText) {
        sendToWebview("system_message", "{\"text\":" + jsonString(markdownText) + "}");
    }

    /**
     * Handles slash command execution from the webview menu.
     * If a command has sub-options, sends them back; otherwise executes immediately.
     */
    private void handleExecuteSlashCommand(String payload) {
        try {
            LOG.info("execute_slash_command raw payload: " + payload);
            java.util.Map<String, Object> data = com.anthropic.claude.intellij.util.JsonParser.parseObject(payload);
            String command = com.anthropic.claude.intellij.util.JsonParser.getString(data, "command");
            String args = com.anthropic.claude.intellij.util.JsonParser.getString(data, "args");
            LOG.info("execute_slash_command parsed: command='" + command + "', args='" + args + "'");
            if (command == null || command.trim().isEmpty()) {
                LOG.warn("execute_slash_command: command is null/empty, ignoring");
                return;
            }

            // Ensure command starts with /
            command = command.trim();
            if (!command.startsWith("/")) {
                command = "/" + command;
            }

            // If args provided, execute directly with the argument
            if (args != null && !args.isEmpty()) {
                String fullCommand = command + " " + args.trim();
                if (SlashCommandHandler.isLocalCommand(fullCommand)) {
                    handleLocalSlashCommand(fullCommand);
                } else {
                    // CLI command - send to CLI
                    if (!cliManager.isRunning()) {
                        startCli();
                    }
                    conversationModel.addUserMessage(fullCommand);
                    cliManager.sendMessage(fullCommand);
                }
                return;
            }

            // Check if command has sub-options
            java.util.List<SlashCommandHandler.SubOption> subOptions = SlashCommandHandler.getSubOptions(command);
            if (!subOptions.isEmpty()) {
                // Send sub-options back to webview for the picker
                StringBuilder json = new StringBuilder("{\"command\":");
                json.append(jsonString(command));
                json.append(",\"options\":[");
                boolean first = true;
                for (SlashCommandHandler.SubOption opt : subOptions) {
                    if (!first) json.append(",");
                    first = false;
                    json.append("{\"value\":").append(jsonString(opt.getValue()));
                    json.append(",\"label\":").append(jsonString(opt.getLabel()));
                    json.append(",\"description\":").append(jsonString(opt.getDescription()));
                    json.append("}");
                }
                json.append("]}");
                sendToWebview("command_picker_options", json.toString());
                return;
            }

            // No sub-options — execute immediately
            if (SlashCommandHandler.isLocalCommand(command)) {
                handleLocalSlashCommand(command);
            } else {
                // CLI command - send to CLI
                if (!cliManager.isRunning()) {
                    startCli();
                }
                conversationModel.addUserMessage(command);
                cliManager.sendMessage(command);
            }
        } catch (Exception e) {
            LOG.error("Error handling execute_slash_command", e);
        }
    }

    /**
     * Handles slash command suggestion requests from the webview.
     */
    private void handleSlashSuggestions(String payload) {
        try {
            java.util.Map<String, Object> data = com.anthropic.claude.intellij.util.JsonParser.parseObject(payload);
            String prefix = com.anthropic.claude.intellij.util.JsonParser.getString(data, "prefix");
            if (prefix == null) prefix = "/";

            java.util.List<SlashCommandHandler.CommandInfo> suggestions = SlashCommandHandler.getSuggestions(prefix);

            StringBuilder json = new StringBuilder("{\"suggestions\":[");
            boolean first = true;
            for (SlashCommandHandler.CommandInfo cmd : suggestions) {
                if (!first) json.append(",");
                first = false;
                json.append("{\"name\":").append(jsonString(cmd.getName()));
                json.append(",\"description\":").append(jsonString(cmd.getDescription()));
                json.append(",\"local\":").append(cmd.isLocalOnly());
                json.append(",\"hasSubOptions\":").append(cmd.hasSubOptions());
                json.append("}");
            }
            json.append("]}");
            sendToWebview("slash_suggestions", json.toString());
        } catch (Exception e) {
            LOG.error("Error handling slash_suggestions", e);
        }
    }

    /**
     * Handles file search requests from the webview (@mention autocomplete).
     * Searches project files matching the query and returns suggestions.
     */
    private void handleFileSearch(String payload) {
        try {
            java.util.Map<String, Object> data = com.anthropic.claude.intellij.util.JsonParser.parseObject(payload);
            String query = com.anthropic.claude.intellij.util.JsonParser.getString(data, "query");
            if (query == null) query = "";

            // Search project files using IntelliJ's VirtualFile API
            String basePath = project.getBasePath();
            if (basePath == null) {
                sendToWebview("file_suggestions", "{\"files\":[]}");
                return;
            }

            final String lowerQuery = query.toLowerCase();
            final java.util.List<java.io.File> matches = new java.util.ArrayList<>();
            final int maxResults = 15;

            // Simple recursive file search (respecting common ignore patterns)
            java.io.File baseDir = new java.io.File(basePath);
            searchFiles(baseDir, baseDir, lowerQuery, matches, maxResults);

            // Build JSON response
            StringBuilder json = new StringBuilder("{\"files\":[");
            boolean first = true;
            for (java.io.File file : matches) {
                if (!first) json.append(",");
                first = false;
                String relativePath = basePath.length() < file.getAbsolutePath().length()
                    ? file.getAbsolutePath().substring(basePath.length() + 1)
                    : file.getName();
                json.append("{\"name\":").append(jsonString(file.getName()));
                json.append(",\"relativePath\":").append(jsonString(relativePath));
                json.append(",\"absolutePath\":").append(jsonString(file.getAbsolutePath()));
                json.append("}");
            }
            json.append("]}");
            sendToWebview("file_suggestions", json.toString());
        } catch (Exception e) {
            LOG.error("Error handling file_search", e);
        }
    }

    /**
     * Recursively searches for files matching the query.
     */
    private void searchFiles(java.io.File dir, java.io.File baseDir, String query,
                             java.util.List<java.io.File> results, int maxResults) {
        if (results.size() >= maxResults) return;

        java.io.File[] children = dir.listFiles();
        if (children == null) return;

        for (java.io.File child : children) {
            if (results.size() >= maxResults) return;

            String name = child.getName();
            // Skip hidden and common ignore directories
            if (name.startsWith(".") || "node_modules".equals(name)
                    || "build".equals(name) || "out".equals(name)
                    || "target".equals(name) || "__pycache__".equals(name)
                    || ".gradle".equals(name) || ".idea".equals(name)) {
                continue;
            }

            if (child.isDirectory()) {
                searchFiles(child, baseDir, query, results, maxResults);
            } else if (child.isFile()) {
                String lowerName = name.toLowerCase();
                String relativePath = baseDir.toPath().relativize(child.toPath()).toString().toLowerCase();
                // Match: file name contains query, or relative path contains query
                if (query.isEmpty() || lowerName.contains(query) || relativePath.contains(query)) {
                    results.add(child);
                }
            }
        }
    }

    /**
     * Opens a native IntelliJ dialog based on the dialog name from the settings menu.
     */
    private void handleOpenDialog(String payload) {
        try {
            java.util.Map<String, Object> data = com.anthropic.claude.intellij.util.JsonParser.parseObject(payload);
            String dialog = com.anthropic.claude.intellij.util.JsonParser.getString(data, "dialog");
            if (dialog == null) return;

            String projectPath = project.getBasePath();
            if (projectPath == null) projectPath = System.getProperty("user.home");

            final String projDir = projectPath;
            ApplicationManager.getApplication().invokeLater(() -> {
                try {
                    switch (dialog) {
                        case "rules":
                            new com.anthropic.claude.intellij.ui.dialogs.RulesDialog(project, projDir).show();
                            break;
                        case "mcp":
                            new com.anthropic.claude.intellij.ui.dialogs.McpServersDialog(project, projDir).show();
                            break;
                        case "hooks":
                            new com.anthropic.claude.intellij.ui.dialogs.HooksDialog(project, projDir).show();
                            break;
                        case "memory":
                            new com.anthropic.claude.intellij.ui.dialogs.MemoryDialog(project, projDir).show();
                            break;
                        case "skills":
                            new com.anthropic.claude.intellij.ui.dialogs.SkillsDialog(project, projDir).show();
                            break;
                        case "history":
                            openSessionHistoryDialog();
                            break;
                        case "preferences":
                            com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                                .showSettingsDialog(project, "Claude Code");
                            break;
                        default:
                            LOG.warn("Unknown dialog type: " + dialog);
                    }
                } catch (Exception e) {
                    LOG.error("Error opening dialog: " + dialog, e);
                }
            });
        } catch (Exception e) {
            LOG.error("Error handling open_dialog", e);
        }
    }

    /**
     * Handles "Always Allow" for a permission request: approves the request AND
     * persists an allow rule in .claude/settings.local.json.
     */
    private void handleAlwaysAllowPermission(String payload) {
        try {
            java.util.Map<String, Object> data = com.anthropic.claude.intellij.util.JsonParser.parseObject(payload);
            String requestId = com.anthropic.claude.intellij.util.JsonParser.getString(data, "requestId");
            String toolUseId = com.anthropic.claude.intellij.util.JsonParser.getString(data, "toolUseId");

            // First, accept the current permission request (with original tool input)
            if (requestId != null) {
                Object toolInput = pendingToolInputs.remove(requestId);
                cliManager.sendControlResponse(requestId, true, toolInput);
            } else if (toolUseId != null) {
                cliManager.sendPermissionResponse(toolUseId, true);
            }

            // Then persist the "always allow" rule for this tool
            // We need the tool name from the pending permission — it's stored in conversationModel
            String toolName = conversationModel.getLastPermissionToolName();
            if (toolName != null && !toolName.isEmpty()) {
                persistAlwaysAllowRule(toolName);
            }
        } catch (Exception e) {
            LOG.error("Error handling always_allow_permission", e);
        }
    }

    /**
     * Applies code from a code block to the currently open editor, replacing its content
     * or the current selection.
     */
    private void handleApplyToEditor(String payload) {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
            try {
                java.util.Map<String, Object> data = com.anthropic.claude.intellij.util.JsonParser.parseObject(payload);
                String code = com.anthropic.claude.intellij.util.JsonParser.getString(data, "code");
                if (code == null || code.isEmpty()) return;

                com.intellij.openapi.fileEditor.FileEditor editor =
                    com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).getSelectedEditor();
                if (editor instanceof com.intellij.openapi.fileEditor.TextEditor) {
                    com.intellij.openapi.editor.Editor textEditor = ((com.intellij.openapi.fileEditor.TextEditor) editor).getEditor();
                    com.intellij.openapi.editor.SelectionModel selection = textEditor.getSelectionModel();
                    com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project, "Apply Code from Claude", null, () -> {
                        if (selection.hasSelection()) {
                            textEditor.getDocument().replaceString(
                                selection.getSelectionStart(), selection.getSelectionEnd(), code);
                        } else {
                            textEditor.getDocument().setText(code);
                        }
                    });
                }
            } catch (Exception e) {
                LOG.error("Error applying code to editor", e);
            }
        });
    }

    /**
     * Inserts code from a code block at the cursor position in the currently open editor.
     */
    private void handleInsertAtCursor(String payload) {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
            try {
                java.util.Map<String, Object> data = com.anthropic.claude.intellij.util.JsonParser.parseObject(payload);
                String code = com.anthropic.claude.intellij.util.JsonParser.getString(data, "code");
                if (code == null || code.isEmpty()) return;

                com.intellij.openapi.fileEditor.FileEditor editor =
                    com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).getSelectedEditor();
                if (editor instanceof com.intellij.openapi.fileEditor.TextEditor) {
                    com.intellij.openapi.editor.Editor textEditor = ((com.intellij.openapi.fileEditor.TextEditor) editor).getEditor();
                    int offset = textEditor.getCaretModel().getOffset();
                    com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project, "Insert Code from Claude", null, () -> {
                        textEditor.getDocument().insertString(offset, code);
                        textEditor.getCaretModel().moveToOffset(offset + code.length());
                    });
                }
            } catch (Exception e) {
                LOG.error("Error inserting code at cursor", e);
            }
        });
    }

    /**
     * Handles attaching a file by path (from @-mention in webview).
     */
    private void handleAttachFile(String payload) {
        try {
            java.util.Map<String, Object> data = com.anthropic.claude.intellij.util.JsonParser.parseObject(payload);
            String filePath = com.anthropic.claude.intellij.util.JsonParser.getString(data, "path");
            if (filePath == null) return;

            attachmentManager.attachFileByPath(filePath, attachments -> {
                sendAttachmentListToWebview(attachments);
            });
        } catch (Exception e) {
            LOG.error("Error handling attach_file", e);
        }
    }

    /**
     * Opens the file attachment dialog (project/filesystem picker).
     */
    private void handleAttachFileDialog() {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
            attachmentManager.showAttachMenu(rootPanel, attachments -> {
                sendAttachmentListToWebview(attachments);
            });
        });
    }

    /**
     * Removes a file attachment by index.
     */
    private void handleRemoveAttachment(String payload) {
        try {
            java.util.Map<String, Object> data = com.anthropic.claude.intellij.util.JsonParser.parseObject(payload);
            Number index = (Number) data.get("index");
            if (index != null) {
                attachmentManager.removeAttachment(index.intValue());
                sendAttachmentListToWebview(attachmentManager.getAttachments());
            }
        } catch (Exception e) {
            LOG.error("Error handling remove_attachment", e);
        }
    }

    /**
     * Sends the current attachment list to the webview for UI rendering.
     */
    private void sendAttachmentListToWebview(java.util.List<AttachmentManager.FileAttachment> attachments) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < attachments.size(); i++) {
            if (i > 0) json.append(",");
            AttachmentManager.FileAttachment att = attachments.get(i);
            json.append("{\"label\":").append(jsonString(att.getLabel()));
            json.append(",\"path\":").append(jsonString(att.getFilePath())).append("}");
        }
        json.append("]");
        sendToWebview("attachments_updated", "{\"attachments\":" + json + "}");
    }

    /**
     * Writes an allow rule for the given tool to .claude/settings.local.json
     */
    private void persistAlwaysAllowRule(String toolName) {
        try {
            new com.anthropic.claude.intellij.settings.ClaudeSettingsWriter(project)
                .addToLocalArray("permissions.allow", toolName);
        } catch (Exception e) {
            LOG.error("Failed to persist always-allow rule", e);
        }
    }

    /**
     * Opens the session history dialog and resumes the selected session if any.
     */
    private void openSessionHistoryDialog() {
        com.anthropic.claude.intellij.ui.dialogs.SessionHistoryDialog dialog =
            new com.anthropic.claude.intellij.ui.dialogs.SessionHistoryDialog(project, sessionManager);
        dialog.show();

        String sessionId = dialog.getSelectedSessionId();
        if (sessionId != null && !sessionId.isEmpty()) {
            resumeSession(sessionId);
        }
    }

    /**
     * Handles /resume command. If an ID is given, resumes directly; otherwise opens the history dialog.
     */
    private void handleResumeCommand(String args) {
        if (args != null && !args.isEmpty()) {
            // Resume by session ID directly
            resumeSession(args.trim());
        } else {
            // Open history dialog to pick a session
            ApplicationManager.getApplication().invokeLater(this::openSessionHistoryDialog);
        }
    }

    /**
     * Resumes a previous session by stopping the current CLI and restarting with --resume flag.
     */
    private void resumeSession(String sessionId) {
        try {
            if (cliManager.isRunning()) {
                cliManager.stop();
            }
            conversationModel.clear();

            String projectPath = project.getBasePath();
            if (projectPath == null) {
                projectPath = System.getProperty("user.home");
            }
            String cliPath = ClaudeCliManager.getCliPath();
            if (cliPath == null) {
                sendToWebview("error", "{\"message\":" + jsonString("Claude CLI not found.") + "}");
                return;
            }

            ClaudeSettings.State settings = ClaudeSettings.getInstance().getState();
            CliProcessConfig.Builder builder = new CliProcessConfig.Builder(cliPath, projectPath)
                .resumeSessionId(sessionId);

            if (settings.selectedModel != null && !settings.selectedModel.isEmpty()
                    && !"default".equals(settings.selectedModel)) {
                builder.model(settings.selectedModel);
            }

            cliManager.start(builder.build());
            showSystemMessage("Resuming session: **" + sessionId + "**");

            // Load conversation history from JSONL file in background,
            // then replay on EDT so webview events are properly ordered.
            final String sid = sessionId;
            new Thread(() -> {
                java.util.List<MessageBlock> history = loadSessionHistoryFromJsonl(sid);
                if (!history.isEmpty()) {
                    LOG.info("Loaded " + history.size() + " messages from JSONL for session " + sid);
                    ApplicationManager.getApplication().invokeLater(() -> {
                        sendToWebview("conversation_cleared", "{}");
                        conversationModel.loadHistory(history);
                    });
                }
            }, "Claude-History-Loader").start();
        } catch (Exception e) {
            LOG.error("Failed to resume session: " + sessionId, e);
            sendToWebview("error", "{\"message\":" + jsonString("Failed to resume session: " + e.getMessage()) + "}");
        }
    }

    /**
     * Reads ~/.claude/projects/{any}/SESSION_ID.jsonl and parses conversation history.
     * Returns MessageBlocks for plain user messages and final assistant messages.
     */
    @SuppressWarnings("unchecked")
    private java.util.List<MessageBlock> loadSessionHistoryFromJsonl(String sessionId) {
        java.util.List<MessageBlock> blocks = new java.util.ArrayList<>();
        try {
            java.io.File claudeProjects = new java.io.File(System.getProperty("user.home") + "/.claude/projects");
            if (!claudeProjects.exists()) return blocks;

            java.io.File jsonlFile = null;
            java.io.File[] projectDirs = claudeProjects.listFiles(java.io.File::isDirectory);
            if (projectDirs == null) return blocks;
            for (java.io.File dir : projectDirs) {
                java.io.File candidate = new java.io.File(dir, sessionId + ".jsonl");
                if (candidate.exists()) {
                    jsonlFile = candidate;
                    break;
                }
            }
            if (jsonlFile == null) return blocks;

            java.util.List<String> lines = java.nio.file.Files.readAllLines(
                jsonlFile.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.trim().isEmpty()) continue;
                try {
                    java.util.Map<String, Object> obj =
                        com.anthropic.claude.intellij.util.JsonParser.parseObject(line);
                    String type = com.anthropic.claude.intellij.util.JsonParser.getString(obj, "type");
                    java.util.Map<String, Object> msg =
                        com.anthropic.claude.intellij.util.JsonParser.getMap(obj, "message");
                    if (msg == null) continue;
                    String role = com.anthropic.claude.intellij.util.JsonParser.getString(msg, "role");

                    if ("user".equals(type) && "user".equals(role)) {
                        Object content = msg.get("content");
                        String text = null;
                        if (content instanceof String) {
                            text = (String) content;
                        } else if (content instanceof java.util.List) {
                            java.util.List<Object> contentList = (java.util.List<Object>) content;
                            if (contentList.isEmpty()) continue;
                            Object first = contentList.get(0);
                            if (first instanceof java.util.Map
                                && "tool_result".equals(com.anthropic.claude.intellij.util.JsonParser.getString(
                                    (java.util.Map<String, Object>) first, "type"))) {
                                continue;
                            }
                            StringBuilder sb = new StringBuilder();
                            for (Object item : contentList) {
                                if (item instanceof java.util.Map) {
                                    java.util.Map<String, Object> itemMap =
                                        (java.util.Map<String, Object>) item;
                                    if ("text".equals(com.anthropic.claude.intellij.util.JsonParser
                                            .getString(itemMap, "type"))) {
                                        String t = com.anthropic.claude.intellij.util.JsonParser
                                            .getString(itemMap, "text");
                                        if (t != null) sb.append(t);
                                    }
                                }
                            }
                            text = sb.toString();
                        }
                        if (text != null && !text.trim().isEmpty()) {
                            MessageBlock block = new MessageBlock(MessageBlock.Role.USER);
                            MessageBlock.TextSegment seg = new MessageBlock.TextSegment();
                            seg.appendText(text);
                            block.addSegment(seg);
                            blocks.add(block);
                        }
                    } else if ("assistant".equals(type) && "assistant".equals(role)) {
                        String stopReason = com.anthropic.claude.intellij.util.JsonParser
                            .getString(msg, "stop_reason");
                        if (stopReason == null) continue;
                        Object content = msg.get("content");
                        if (!(content instanceof java.util.List)) continue;
                        java.util.List<Object> contentList = (java.util.List<Object>) content;
                        MessageBlock block = new MessageBlock(MessageBlock.Role.ASSISTANT);
                        for (Object item : contentList) {
                            if (!(item instanceof java.util.Map)) continue;
                            java.util.Map<String, Object> itemMap =
                                (java.util.Map<String, Object>) item;
                            String itemType = com.anthropic.claude.intellij.util.JsonParser
                                .getString(itemMap, "type");
                            if ("text".equals(itemType)) {
                                String t = com.anthropic.claude.intellij.util.JsonParser
                                    .getString(itemMap, "text");
                                if (t != null && !t.isEmpty()) {
                                    MessageBlock.TextSegment seg = new MessageBlock.TextSegment();
                                    seg.appendText(t);
                                    block.addSegment(seg);
                                }
                            } else if ("tool_use".equals(itemType)) {
                                MessageBlock.ToolCallSegment toolSeg =
                                    new MessageBlock.ToolCallSegment();
                                toolSeg.setToolId(com.anthropic.claude.intellij.util.JsonParser
                                    .getString(itemMap, "id"));
                                toolSeg.setToolName(com.anthropic.claude.intellij.util.JsonParser
                                    .getString(itemMap, "name"));
                                Object inputObj = itemMap.get("input");
                                if (inputObj != null) {
                                    toolSeg.setInput(
                                        com.anthropic.claude.intellij.util.JsonParser.toJson(inputObj));
                                }
                                toolSeg.setStatus(MessageBlock.ToolStatus.COMPLETED);
                                block.addSegment(toolSeg);
                            }
                        }
                        if (!block.getSegments().isEmpty()) {
                            blocks.add(block);
                        }
                    }
                } catch (Exception lineEx) {
                    // Skip invalid lines
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to load session history for " + sessionId, e);
        }
        return blocks;
    }

    private void handleStopGeneration() {
        if (cliManager.isRunning()) {
            cliManager.stop();
            conversationModel.markActiveToolCallsFailed("Stopped by user");
            sendToWebview("generation_stopped", "{}");
        }
    }

    private void handleNewSession() {
        if (cliManager.isRunning()) {
            cliManager.stop();
        }
        conversationModel.clear();
        cancelStreamingTimeout();
        // Clear checkpoints and pending edit decisions for the new session
        com.anthropic.claude.intellij.service.ClaudeProjectService service =
            com.anthropic.claude.intellij.service.ClaudeProjectService.getInstance(project);
        service.getCheckpointManager().clearCheckpoints();
        service.getEditDecisionManager().clearAll();
        eagerSnapshotDone.clear();
        stagedEditDone.clear();
        tabNameSet = false;
        startCli();
    }

    /**
     * Updates the tool window tab display name to match the first user message (like Eclipse).
     */
    // ==================== Streaming Timeout (45s watchdog) ====================

    private volatile long lastStreamActivity = 0;
    private java.util.concurrent.ScheduledExecutorService streamingWatchdog;
    private java.util.concurrent.ScheduledFuture<?> streamingWatchdogFuture;

    private void touchStreamActivity() {
        lastStreamActivity = System.currentTimeMillis();
    }

    private void startStreamingTimeout() {
        cancelStreamingTimeout();
        touchStreamActivity();
        streamingWatchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "claude-streaming-watchdog");
            t.setDaemon(true);
            return t;
        });
        streamingWatchdogFuture = streamingWatchdog.scheduleAtFixedRate(() -> {
            long elapsed = System.currentTimeMillis() - lastStreamActivity;
            if (elapsed >= 45_000 && !conversationModel.hasRunningToolCalls()) {
                LOG.warn("Streaming timeout after " + (elapsed / 1000) + "s with no activity");
                conversationModel.markActiveToolCallsFailed("Streaming timeout — no response for 45s");
                sendToWebview("error", "{\"message\":\"Streaming timeout — no response for 45 seconds\"}");
                cancelStreamingTimeout();
            }
        }, 5, 5, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void cancelStreamingTimeout() {
        if (streamingWatchdogFuture != null) {
            streamingWatchdogFuture.cancel(false);
            streamingWatchdogFuture = null;
        }
        if (streamingWatchdog != null) {
            streamingWatchdog.shutdownNow();
            streamingWatchdog = null;
        }
    }

    // ==================== Checkpoint Snapshots ====================

    /**
     * Snapshot a file before a tool (Write/Edit) modifies it, for revert capability.
     */
    /** Set of tool IDs for which we already attempted an eager snapshot. */
    private final java.util.Set<String> eagerSnapshotDone = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private void snapshotForCheckpoint(MessageBlock.ToolCallSegment toolCall) {
        String toolName = toolCall.getToolName();
        if (!"Write".equals(toolName) && !"Edit".equals(toolName) && !"MultiEdit".equals(toolName)) {
            return;
        }
        // Extract file_path from tool input JSON
        String input = toolCall.getInput();
        if (input == null) return;
        try {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> inputMap = com.anthropic.claude.intellij.util.JsonParser.parseObject(input);
            String filePath = com.anthropic.claude.intellij.util.JsonParser.getString(inputMap, "file_path");
            if (filePath == null) {
                filePath = com.anthropic.claude.intellij.util.JsonParser.getString(inputMap, "path");
            }
            if (filePath != null) {
                com.anthropic.claude.intellij.diff.CheckpointManager checkpointMgr =
                    com.anthropic.claude.intellij.service.ClaudeProjectService.getInstance(project).getCheckpointManager();
                checkpointMgr.snapshot(filePath);
            }
        } catch (Exception e) {
            LOG.warn("Failed to snapshot for checkpoint", e);
        }
    }

    /**
     * Eagerly snapshot on the first input delta for Write/Edit tools.
     * At this point only the tool name is known (not file_path yet),
     * but we try to extract a partial file_path from accumulated input.
     * Even if it fails, snapshotForCheckpoint() will retry on input_complete.
     */
    private void snapshotForCheckpointEager(MessageBlock.ToolCallSegment toolCall) {
        String toolName = toolCall.getToolName();
        if (!"Write".equals(toolName) && !"Edit".equals(toolName) && !"MultiEdit".equals(toolName)) {
            return;
        }
        String toolId = toolCall.getToolId();
        if (toolId == null || !eagerSnapshotDone.add(toolId)) {
            return; // already attempted for this tool call
        }
        // Try to parse partial input for file_path
        String input = toolCall.getInput();
        if (input == null) return;
        try {
            String filePath = extractFilePathFromPartialJson(input);
            if (filePath != null) {
                com.anthropic.claude.intellij.diff.CheckpointManager checkpointMgr =
                    com.anthropic.claude.intellij.service.ClaudeProjectService.getInstance(project).getCheckpointManager();
                checkpointMgr.snapshot(filePath);
            }
        } catch (Exception e) {
            // Partial JSON parsing may fail, that's OK — snapshotForCheckpoint will retry
        }
    }

    /**
     * Tries to extract "file_path" or "path" from potentially incomplete JSON input.
     * Uses regex since the JSON may not be fully formed yet.
     */
    private String extractFilePathFromPartialJson(String input) {
        // Try full JSON parse first
        try {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> map = com.anthropic.claude.intellij.util.JsonParser.parseObject(input);
            String fp = com.anthropic.claude.intellij.util.JsonParser.getString(map, "file_path");
            if (fp == null) fp = com.anthropic.claude.intellij.util.JsonParser.getString(map, "path");
            return fp;
        } catch (Exception ignored) {}

        // Fallback: regex for "file_path":"..." or "path":"..."
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            "\"(?:file_path|path)\"\\s*:\\s*\"([^\"]+)\"").matcher(input);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    /**
     * After a Write/Edit tool completes, record the edit in EditDecisionManager
     * and send an edit_staged message to the webview for accept/reject UI.
     */
    /** Set of tool IDs for which we already staged an edit (prevents duplicates). */
    private final java.util.Set<String> stagedEditDone = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private void stageCompletedEdit(MessageBlock.ToolCallSegment toolCall) {
        String toolName = toolCall.getToolName();
        if (!"Write".equals(toolName) && !"Edit".equals(toolName) && !"MultiEdit".equals(toolName)) {
            return;
        }
        // Prevent duplicate staging (onToolCallCompleted may fire from both updateToolCallResult and handleToolUseSummary)
        String toolId = toolCall.getToolId();
        if (toolId != null && !stagedEditDone.add(toolId)) {
            return;
        }
        String input = toolCall.getInput();
        if (input == null) return;
        try {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> inputMap = com.anthropic.claude.intellij.util.JsonParser.parseObject(input);
            String filePath = com.anthropic.claude.intellij.util.JsonParser.getString(inputMap, "file_path");
            if (filePath == null) {
                filePath = com.anthropic.claude.intellij.util.JsonParser.getString(inputMap, "path");
            }
            if (filePath != null) {
                com.anthropic.claude.intellij.service.ClaudeProjectService service =
                    com.anthropic.claude.intellij.service.ClaudeProjectService.getInstance(project);
                com.anthropic.claude.intellij.diff.EditDecisionManager edm = service.getEditDecisionManager();
                com.anthropic.claude.intellij.diff.CheckpointManager checkpointMgr = service.getCheckpointManager();

                // Get original content from checkpoint (snapshotted BEFORE tool executed)
                String originalContent = checkpointMgr.getCheckpoint(filePath);

                // Count lines in the current (modified) file to highlight
                java.util.List<String> lines = java.nio.file.Files.readAllLines(
                    java.nio.file.Paths.get(filePath), java.nio.charset.StandardCharsets.UTF_8);
                String editId = edm.recordCompletedEditWithOriginal(filePath, originalContent, 0, lines.size());

                // Send edit_staged to webview
                String fileName = java.nio.file.Paths.get(filePath).getFileName().toString();
                sendToWebview("edit_staged",
                    "{\"editId\":" + jsonString(editId) +
                    ",\"filePath\":" + jsonString(filePath) +
                    ",\"fileName\":" + jsonString(fileName) + "}");
            }
        } catch (Exception e) {
            LOG.warn("Failed to stage completed edit", e);
        }
    }

    // ==================== Toast ====================

    /**
     * Shows a transient toast notification in the webview.
     */
    public void sendToast(String message) {
        sendToWebview("toast", "{\"message\":" + jsonString(message) + "}");
    }

    /**
     * Refreshes IntelliJ's VFS so editor picks up file changes made by CLI tools.
     */
    private void refreshProjectFiles() {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
            com.intellij.openapi.vfs.VirtualFile projectDir = project.getBaseDir();
            if (projectDir != null) {
                projectDir.refresh(true, true);
            }
        });
    }

    private void updateTabDisplayName(String title) {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
            com.intellij.openapi.wm.ToolWindow toolWindow =
                com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("Claude");
            if (toolWindow == null) return;

            // Find our content by matching the component
            for (com.intellij.ui.content.Content content : toolWindow.getContentManager().getContents()) {
                if (content.getComponent() == rootPanel) {
                    content.setDisplayName(title);
                    break;
                }
            }
        });
    }

    /**
     * Opens a new parallel conversation tab in the Claude tool window.
     */
    private void handleNewTab() {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
            com.intellij.openapi.wm.ToolWindow toolWindow =
                com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("Claude");
            if (toolWindow == null) return;

            ClaudeChatPanel newPanel = new ClaudeChatPanel(project);
            int tabCount = toolWindow.getContentManager().getContentCount();
            com.intellij.ui.content.Content content =
                com.intellij.ui.content.ContentFactory.getInstance()
                    .createContent(newPanel.getComponent(), "Chat " + (tabCount + 1), false);
            content.setCloseable(true);
            toolWindow.getContentManager().addContent(content);
            toolWindow.getContentManager().setSelectedContent(content);
        });
    }

    private void handlePermissionResponse(String payload, boolean allow) {
        try {
            java.util.Map<String, Object> data = com.anthropic.claude.intellij.util.JsonParser.parseObject(payload);
            String requestId = com.anthropic.claude.intellij.util.JsonParser.getString(data, "requestId");
            String toolUseId = com.anthropic.claude.intellij.util.JsonParser.getString(data, "toolUseId");

            if (requestId != null) {
                // New control_request format — pass back original tool input
                Object toolInput = pendingToolInputs.remove(requestId);
                cliManager.sendControlResponse(requestId, allow, toolInput);
            } else if (toolUseId != null) {
                // Old permission_response format
                cliManager.sendPermissionResponse(toolUseId, allow);
            }
        } catch (Exception e) {
            LOG.error("Error handling permission response from webview", e);
        }
    }

    private void startCli() {
        try {
            String projectPath = project.getBasePath();
            if (projectPath == null) {
                projectPath = System.getProperty("user.home");
            }
            String cliPath = ClaudeCliManager.getCliPath();
            if (cliPath == null) {
                sendToWebview("error", "{\"message\":" + jsonString("Claude CLI not found. Please configure the path in Settings > Tools > Claude Code.") + "}");
                return;
            }

            ClaudeSettings.State settings = ClaudeSettings.getInstance().getState();
            CliProcessConfig.Builder builder = new CliProcessConfig.Builder(cliPath, projectPath);

            // Apply model from settings (unless "default")
            if (settings.selectedModel != null && !settings.selectedModel.isEmpty()
                    && !"default".equals(settings.selectedModel)) {
                builder.model(settings.selectedModel);
            }

            // Apply permission mode from settings
            if (settings.initialPermissionMode != null && !settings.initialPermissionMode.isEmpty()
                    && !"default".equals(settings.initialPermissionMode)) {
                builder.permissionMode(settings.initialPermissionMode);
            }

            CliProcessConfig config = builder.build();
            cliManager.start(config);
        } catch (IOException e) {
            LOG.error("Failed to start Claude CLI", e);
            sendToWebview("error", "{\"message\":" + jsonString("Failed to start Claude CLI: " + e.getMessage()) + "}");
        }
    }

    /**
     * Push the initial state to the webview after it loads.
     */
    @SuppressWarnings("deprecation")
    private void pushInitialState() {
        if (!webviewReady) return;

        // Send theme based on current IDE look and feel
        boolean isDark = com.intellij.util.ui.UIUtil.isUnderDarcula();
        sendToWebview("set_theme", "{\"theme\":\"" + (isDark ? "dark" : "light") + "\"}");

        // Send connection state
        if (cliManager.isRunning()) {
            sendToWebview("cli_state_changed", "{\"state\":\"connected\"}");
        } else {
            sendToWebview("cli_state_changed", "{\"state\":\"disconnected\"}");
        }

        // Send session info if available
        SessionInfo info = conversationModel.getSessionInfo();
        if (info != null) {
            sendToWebview("session_initialized", buildSessionInfoJson(info));
        }

        // Replay existing messages
        for (MessageBlock block : conversationModel.getMessages()) {
            if (block.getRole() == MessageBlock.Role.USER) {
                sendToWebview("user_message_added", buildMessageBlockJson(block));
            } else if (block.getRole() == MessageBlock.Role.ASSISTANT) {
                sendToWebview("assistant_message_started", buildMessageBlockJson(block));
                sendToWebview("assistant_message_completed", buildMessageBlockJson(block));
            }
        }

        // Send current usage
        UsageInfo usage = conversationModel.getCumulativeUsage();
        if (usage.getTotalTokens() > 0) {
            sendToWebview("result_received", buildUsageJson(usage));
        }

        // Send Enter-to-Send preference
        boolean ctrlEnter = com.anthropic.claude.intellij.settings.ClaudeSettings.getInstance()
            .getState().useCtrlEnterToSend;
        sendToWebview("set_enter_mode", "{\"ctrlEnter\":" + ctrlEnter + "}");
    }

    /**
     * Send a message to the webview.
     */
    public void sendToWebview(String type, String payload) {
        if (bridge != null && webviewReady) {
            ApplicationManager.getApplication().invokeLater(() -> {
                bridge.sendToWebview(type, payload);
            });
        }
    }

    /**
     * Returns the root Swing component to be added to the tool window.
     */
    public JComponent getComponent() {
        return rootPanel;
    }

    public ConversationModel getConversationModel() {
        return conversationModel;
    }

    public ClaudeCliManager getCliManager() {
        return cliManager;
    }

    /**
     * Extracts webview resources (HTML, CSS, JS) from the plugin JAR to a temp directory
     * so that JCEF can load them with proper relative path resolution.
     */
    private Path extractWebviewResources() throws IOException {
        String[] resources = {
            "webview/index.html",
            "webview/css/chat.css",
            "webview/js/bridge.js",
            "webview/js/app.js",
            "webview/js/highlight.js"
        };

        Path tempDir = Files.createTempDirectory("claude-webview-");
        // Create subdirectories
        Files.createDirectories(tempDir.resolve("css"));
        Files.createDirectories(tempDir.resolve("js"));

        ClassLoader cl = getClass().getClassLoader();
        for (String res : resources) {
            URL url = cl.getResource(res);
            if (url == null) {
                LOG.error("Missing webview resource: " + res);
                continue;
            }
            // Target path strips the "webview/" prefix
            String relativePath = res.substring("webview/".length());
            Path target = tempDir.resolve(relativePath);
            try (InputStream is = url.openStream()) {
                Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        // Clean up temp dir on JVM exit
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                Files.walk(tempDir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
            } catch (IOException ignored) {}
        }));

        return tempDir;
    }

    private void cleanup() {
        // Remove listeners to prevent memory leaks
        if (conversationModel != null && conversationListener != null) {
            conversationModel.removeListener(conversationListener);
        }
        if (cliManager != null && cliStateListener != null) {
            cliManager.removeStateListener(cliStateListener);
        }
        cancelStreamingTimeout();
        if (cliManager != null && cliManager.isRunning()) {
            cliManager.stop();
        }
        if (bridge != null) {
            bridge.dispose();
        }
        if (browser != null) {
            browser.dispose();
        }
    }

    @Override
    public void dispose() {
        cleanup();
    }

    // ==================== JSON Builders ====================

    private String buildSessionInfoJson(SessionInfo info) {
        StringBuilder json = new StringBuilder("{");
        json.append("\"sessionId\":").append(jsonString(info.getSessionId()));
        json.append(",\"model\":").append(jsonString(info.getModel()));
        json.append(",\"workingDirectory\":").append(jsonString(info.getWorkingDirectory()));
        json.append(",\"permissionMode\":").append(jsonString(info.getPermissionMode()));
        json.append("}");
        return json.toString();
    }

    private String buildMessageBlockJson(MessageBlock block) {
        StringBuilder json = new StringBuilder("{");
        json.append("\"role\":").append(jsonString(block.getRole().name().toLowerCase()));
        json.append(",\"timestamp\":").append(block.getTimestamp());
        json.append(",\"segments\":[");

        boolean first = true;
        for (MessageBlock.ContentSegment seg : block.getSegments()) {
            if (!first) json.append(",");
            first = false;

            if (seg instanceof MessageBlock.TextSegment) {
                MessageBlock.TextSegment textSeg = (MessageBlock.TextSegment) seg;
                json.append("{\"type\":\"text\",\"text\":").append(jsonString(textSeg.getText())).append("}");
            } else if (seg instanceof MessageBlock.ToolCallSegment) {
                json.append(buildToolCallJson((MessageBlock.ToolCallSegment) seg));
            }
        }

        json.append("]}");
        return json.toString();
    }

    private String buildToolCallJson(MessageBlock.ToolCallSegment toolCall) {
        StringBuilder json = new StringBuilder("{");
        json.append("\"type\":\"tool_use\"");
        json.append(",\"toolId\":").append(jsonString(toolCall.getToolId()));
        json.append(",\"toolName\":").append(jsonString(toolCall.getToolName()));
        json.append(",\"displayName\":").append(jsonString(toolCall.getDisplayName()));
        json.append(",\"summary\":").append(jsonString(toolCall.getSummary()));
        json.append(",\"input\":").append(jsonString(toolCall.getInput()));
        json.append(",\"output\":").append(jsonString(toolCall.getOutput()));
        json.append(",\"status\":").append(jsonString(toolCall.getStatus().name().toLowerCase()));
        json.append("}");
        return json.toString();
    }

    private String buildUsageJson(UsageInfo usage) {
        StringBuilder json = new StringBuilder("{");
        json.append("\"inputTokens\":").append(usage.getTotalInputTokens());
        json.append(",\"outputTokens\":").append(usage.getTotalOutputTokens());
        json.append(",\"totalTokens\":").append(usage.getTotalTokens());
        json.append(",\"costUsd\":").append(usage.getTotalCostUsd());
        json.append(",\"durationMs\":").append(usage.getTotalDurationMs());
        json.append(",\"turns\":").append(usage.getTotalTurns());
        json.append(",\"formattedCost\":").append(jsonString(usage.formatCost()));
        json.append(",\"formattedTokens\":").append(jsonString(usage.formatTokens()));
        json.append(",\"formattedDuration\":").append(jsonString(usage.formatDuration()));
        json.append("}");
        return json.toString();
    }

    /**
     * Properly escapes a Java string for JSON embedding.
     */
    private static String jsonString(String value) {
        if (value == null) return "null";
        return "\"" + com.anthropic.claude.intellij.util.JsonParser.escapeJsonString(value) + "\"";
    }
}
