package com.anthropic.claude.intellij.ui.dialogs;

import com.anthropic.claude.intellij.model.SessionInfo;
import com.anthropic.claude.intellij.session.ClaudeSessionManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

/**
 * Dialog for browsing and resuming previous Claude sessions.
 * Shows session history with filtering, details preview, and resume/delete actions.
 * Uses native IntelliJ DialogWrapper.
 */
public class SessionHistoryDialog extends DialogWrapper {

    private static final Logger LOG = Logger.getInstance(SessionHistoryDialog.class);
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm");

    private final ClaudeSessionManager sessionManager;
    private JBTable historyTable;
    private DefaultTableModel historyModel;
    private JTextArea detailsArea;
    private SearchTextField filterField;
    private TableRowSorter<DefaultTableModel> sorter;
    private List<SessionInfo> sessions = new ArrayList<>();

    /** The session ID selected for resume (null if dialog was cancelled or no selection). */
    private String selectedSessionId;

    public SessionHistoryDialog(Project project, ClaudeSessionManager sessionManager) {
        super(project, true);
        this.sessionManager = sessionManager;
        setTitle("Claude Code - Session History");
        setSize(900, 600);
        init();
    }

    @Override
    protected JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));

        // Top: filter field
        JPanel topPanel = new JPanel(new BorderLayout(8, 0));
        JLabel filterLabel = new JLabel("Filter:");
        filterField = new SearchTextField();
        filterField.addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) { applyFilter(); }
            @Override
            public void removeUpdate(DocumentEvent e) { applyFilter(); }
            @Override
            public void changedUpdate(DocumentEvent e) { applyFilter(); }
        });
        topPanel.add(filterLabel, BorderLayout.WEST);
        topPanel.add(filterField, BorderLayout.CENTER);
        panel.add(topPanel, BorderLayout.NORTH);

        // Center: split pane with table and details
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setDividerLocation(550);

        // Left: history table
        historyModel = new DefaultTableModel(new String[]{"Date", "Summary", "Model", "Messages"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        historyTable = new JBTable(historyModel);
        historyTable.getColumnModel().getColumn(0).setPreferredWidth(130);
        historyTable.getColumnModel().getColumn(1).setPreferredWidth(260);
        historyTable.getColumnModel().getColumn(2).setPreferredWidth(100);
        historyTable.getColumnModel().getColumn(3).setPreferredWidth(60);

        sorter = new TableRowSorter<>(historyModel);
        historyTable.setRowSorter(sorter);

        historyTable.getSelectionModel().addListSelectionListener(this::onSessionSelected);

        // Double-click to resume
        historyTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    resumeSelectedSession();
                }
            }
        });

        JPanel leftPanel = new JPanel(new BorderLayout());
        leftPanel.add(new JBScrollPane(historyTable), BorderLayout.CENTER);

        // Buttons below table
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JButton resumeBtn = new JButton("Resume Session");
        resumeBtn.addActionListener(e -> resumeSelectedSession());
        JButton deleteBtn = new JButton("Delete");
        deleteBtn.addActionListener(e -> deleteSelectedSession());
        JButton refreshBtn = new JButton("Refresh");
        refreshBtn.addActionListener(e -> loadSessions());
        btnRow.add(resumeBtn);
        btnRow.add(deleteBtn);
        btnRow.add(refreshBtn);
        leftPanel.add(btnRow, BorderLayout.SOUTH);

        // Right: details
        JPanel rightPanel = new JPanel(new BorderLayout(0, 4));
        rightPanel.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 0));

        JLabel detailsTitle = new JLabel("Session Details");
        detailsTitle.setFont(detailsTitle.getFont().deriveFont(Font.BOLD, 12f));
        rightPanel.add(detailsTitle, BorderLayout.NORTH);

        detailsArea = new JTextArea();
        detailsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        detailsArea.setEditable(false);
        detailsArea.setLineWrap(true);
        detailsArea.setWrapStyleWord(true);
        detailsArea.setText("Select a session to view details.\nDouble-click or press 'Resume Session' to continue a session.");
        rightPanel.add(new JBScrollPane(detailsArea), BorderLayout.CENTER);

        splitPane.setLeftComponent(leftPanel);
        splitPane.setRightComponent(rightPanel);
        panel.add(splitPane, BorderLayout.CENTER);

        loadSessions();
        return panel;
    }

    // ==================== Load ====================

    private void loadSessions() {
        // Fast path: enumerate JSONL filenames + mtimes only — no content
        // read. With many GB of transcripts this finishes in milliseconds
        // and the dialog opens immediately. Per-row summary/model/count
        // get filled lazily in the background below.
        sessions = com.anthropic.claude.intellij.session.JsonlSessionScanner.listSessionsFast(null);
        historyModel.setRowCount(0);

        for (SessionInfo info : sessions) {
            String date = info.getLastActiveTime() > 0
                ? DATE_FORMAT.format(new Date(info.getLastActiveTime()))
                : "Unknown";
            historyModel.addRow(new Object[]{date, "(loading…)", "", 0});
        }

        if (sessions.isEmpty()) {
            detailsArea.setText("No saved sessions found.\n\nSessions are saved automatically during conversations.");
            return;
        }

        // Background filler: read each row's summary/model/messageCount and
        // update the table on the EDT. Stop early if the dialog is closed.
        final List<SessionInfo> snapshot = new ArrayList<>(sessions);
        Thread t = new Thread(() -> {
            for (int i = 0; i < snapshot.size(); i++) {
                final int idx = i;
                final SessionInfo info = snapshot.get(i);
                try {
                    com.anthropic.claude.intellij.session.JsonlSessionScanner.fillSessionDetails(info);
                } catch (Exception ignored) {}
                SwingUtilities.invokeLater(() -> {
                    if (idx >= historyModel.getRowCount()) return;
                    String summary = info.getSummary() != null ? info.getSummary() : "";
                    String model = info.getModel() != null ? info.getModel() : "";
                    int msgs = info.getMessageCount();
                    historyModel.setValueAt(summary, idx, 1);
                    historyModel.setValueAt(model, idx, 2);
                    historyModel.setValueAt(msgs, idx, 3);
                });
            }
        }, "Claude-SessionDetails-Filler");
        t.setDaemon(true);
        t.start();
    }

    // ==================== Selection ====================

    private void onSessionSelected(ListSelectionEvent e) {
        if (e.getValueIsAdjusting()) return;
        int viewRow = historyTable.getSelectedRow();
        if (viewRow < 0) {
            detailsArea.setText("Select a session to view details.");
            return;
        }

        int modelRow = historyTable.convertRowIndexToModel(viewRow);
        if (modelRow < 0 || modelRow >= sessions.size()) return;

        SessionInfo info = sessions.get(modelRow);
        StringBuilder sb = new StringBuilder();

        sb.append("Session ID:  ").append(info.getSessionId()).append("\n");
        if (info.getModel() != null) {
            sb.append("Model:       ").append(info.getModel()).append("\n");
        }
        if (info.getWorkingDirectory() != null) {
            sb.append("Working Dir: ").append(info.getWorkingDirectory()).append("\n");
        }
        if (info.getPermissionMode() != null) {
            sb.append("Permissions: ").append(info.getPermissionMode()).append("\n");
        }
        sb.append("Messages:    ").append(info.getMessageCount()).append("\n");

        if (info.getStartTime() > 0) {
            sb.append("Started:     ").append(DATE_FORMAT.format(new Date(info.getStartTime()))).append("\n");
        }
        if (info.getLastActiveTime() > 0) {
            sb.append("Last Active: ").append(DATE_FORMAT.format(new Date(info.getLastActiveTime()))).append("\n");
        }

        if (info.getSummary() != null && !info.getSummary().isEmpty()) {
            sb.append("\nSummary:\n").append(info.getSummary()).append("\n");
        }

        sb.append("\nDouble-click or press 'Resume Session' to continue this session.");

        detailsArea.setText(sb.toString());
        detailsArea.setCaretPosition(0);
    }

    // ==================== Actions ====================

    private void resumeSelectedSession() {
        int viewRow = historyTable.getSelectedRow();
        if (viewRow < 0) return;

        int modelRow = historyTable.convertRowIndexToModel(viewRow);
        if (modelRow < 0 || modelRow >= sessions.size()) return;

        selectedSessionId = sessions.get(modelRow).getSessionId();
        close(OK_EXIT_CODE);
    }

    private void deleteSelectedSession() {
        int viewRow = historyTable.getSelectedRow();
        if (viewRow < 0) return;

        int modelRow = historyTable.convertRowIndexToModel(viewRow);
        if (modelRow < 0 || modelRow >= sessions.size()) return;

        SessionInfo info = sessions.get(modelRow);
        int confirm = Messages.showYesNoDialog(
            "Delete session " + info.getSessionId() + "?",
            "Confirm Delete",
            Messages.getQuestionIcon()
        );

        if (confirm == Messages.YES) {
            sessionManager.deleteSession(info.getSessionId());
            loadSessions();
        }
    }

    // ==================== Filter ====================

    private void applyFilter() {
        String text = filterField.getText().trim().toLowerCase();
        if (text.isEmpty()) {
            sorter.setRowFilter(null);
        } else {
            sorter.setRowFilter(javax.swing.RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote(text)));
        }
    }

    // ==================== Result ====================

    /**
     * Returns the session ID that the user selected to resume, or null if cancelled.
     */
    public String getSelectedSessionId() {
        return selectedSessionId;
    }

    // ==================== Buttons ====================

    @Override
    protected Action[] createActions() {
        return new Action[]{getCancelAction()};
    }
}
