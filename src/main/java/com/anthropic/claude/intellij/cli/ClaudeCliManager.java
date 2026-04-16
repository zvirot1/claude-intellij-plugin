package com.anthropic.claude.intellij.cli;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.anthropic.claude.intellij.settings.ClaudeSettings;
import com.anthropic.claude.intellij.settings.SecureApiKeyStore;
import com.anthropic.claude.intellij.util.JsonParser;
import com.intellij.openapi.diagnostic.Logger;

/**
 * Manages the Claude CLI process lifecycle, including starting, stopping,
 * sending messages, and dispatching received messages to listeners.
 */
public class ClaudeCliManager {

    private static final Logger LOG = Logger.getInstance(ClaudeCliManager.class);

    private static final String CLAUDE_CODE_ENTRYPOINT = "intellij-plugin";

    /**
     * Process lifecycle states.
     */
    public enum ProcessState {
        NOT_STARTED,
        STARTING,
        RUNNING,
        STOPPING,
        STOPPED,
        ERROR
    }

    private Process process;
    private BufferedWriter processWriter;
    private Thread readerThread;
    private Thread errorThread;
    private final NdjsonProtocolHandler protocolHandler;

    private final List<ICliMessageListener> messageListeners = new CopyOnWriteArrayList<>();
    private final List<ICliStateListener> stateListeners = new CopyOnWriteArrayList<>();

    private final AtomicReference<ProcessState> state = new AtomicReference<>(ProcessState.NOT_STARTED);
    private volatile boolean busy;

    private ScheduledExecutorService healthChecker;
    private volatile int lastExitCode = -1;
    private CliProcessConfig storedConfig;

    private String sessionId;
    private String currentModel;
    private String workingDirectory;

    public ClaudeCliManager() {
        this.protocolHandler = new NdjsonProtocolHandler();
    }

    public void addMessageListener(ICliMessageListener listener) {
        messageListeners.add(listener);
    }

    public void removeMessageListener(ICliMessageListener listener) {
        messageListeners.remove(listener);
    }

    public void addStateListener(ICliStateListener listener) {
        stateListeners.add(listener);
    }

    public void removeStateListener(ICliStateListener listener) {
        stateListeners.remove(listener);
    }

    public boolean isRunning() {
        return state.get() == ProcessState.RUNNING;
    }

    public boolean isBusy() {
        return busy;
    }

    public ProcessState getProcessState() {
        return state.get();
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getCurrentModel() {
        return currentModel;
    }

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    /**
     * Starts the Claude CLI process with the given configuration.
     */
    public synchronized void start(CliProcessConfig config) throws IOException {
        if (state.get() == ProcessState.RUNNING || state.get() == ProcessState.STARTING) {
            LOG.warn("CLI process is already running or starting");
            return;
        }

        String cliPath = config.getCliPath();
        if (cliPath == null || cliPath.isEmpty()) {
            cliPath = getCliPath();
        }
        if (cliPath == null || cliPath.isEmpty()) {
            throw new IOException("Claude CLI path is not configured. Please set it in Settings > Tools > Claude Code.");
        }

        fireStateChanged(ProcessState.STARTING);
        this.workingDirectory = config.getWorkingDirectory();
        this.storedConfig = config;

        List<String> command = buildCommand(cliPath, config);

        ProcessBuilder pb = new ProcessBuilder(command);
        if (config.getWorkingDirectory() != null) {
            pb.directory(new File(config.getWorkingDirectory()));
        }

        Map<String, String> env = pb.environment();
        env.put("CLAUDE_CODE_ENTRYPOINT", CLAUDE_CODE_ENTRYPOINT);
        env.put("FORCE_COLOR", "0");

        // Remove environment variables that may interfere with the CLI
        env.remove("NODE_OPTIONS");
        env.remove("CLAUDECODE");
        env.remove("CLAUDE_CODE_OAUTH_TOKEN");

        // Set API key if available
        String apiKey = SecureApiKeyStore.getApiKey();
        if (apiKey != null && !apiKey.isEmpty()) {
            env.put("ANTHROPIC_API_KEY", apiKey);
        }

        LOG.info("Starting Claude CLI: " + String.join(" ", command));

        try {
            process = pb.start();
            processWriter = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

            // Set state to RUNNING BEFORE starting threads to avoid race condition
            // (reader thread checks state.get() == RUNNING in its loop condition)
            fireStateChanged(ProcessState.RUNNING);

            // Start reader thread for stdout (NDJSON messages)
            readerThread = new Thread(this::readProcessOutput, "claude-cli-reader");
            readerThread.setDaemon(true);
            readerThread.start();

            // Start error reader thread for stderr
            errorThread = new Thread(this::readProcessErrors, "claude-cli-error-reader");
            errorThread.setDaemon(true);
            errorThread.start();

            // Start health monitor
            startHealthMonitor();
        } catch (IOException e) {
            fireStateChanged(ProcessState.ERROR);
            throw e;
        }
    }

    /**
     * Interrupts the current query and auto-restarts the CLI with --resume to preserve
     * the conversation memory. This is the preferred way to stop a running query (vs stop()
     * which terminates permanently).
     *
     * @param resumeSessionId if non-null, the CLI will restart with --resume &lt;sessionId&gt;
     */
    public void interruptCurrentQuery(String resumeSessionId) {
        ProcessState current = state.get();
        if (current != ProcessState.RUNNING) {
            return;
        }

        // 1. Suppress further message processing immediately
        protocolHandler.setSuppressed(true);
        busy = false;

        // 2. Kill the process forcibly (including child processes)
        if (process != null) {
            // Kill child process tree first (e.g. Node.js workers)
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }

        // Stop health monitor
        if (healthChecker != null) {
            healthChecker.shutdownNow();
            healthChecker = null;
        }

        fireStateChanged(ProcessState.STOPPED);

        // 3. Auto-restart on background thread with --resume
        if (resumeSessionId != null && !resumeSessionId.isEmpty() && storedConfig != null) {
            Thread restartThread = new Thread(() -> {
                try {
                    // Wait for process to fully terminate
                    if (process != null) {
                        process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
                    }
                    // Re-enable protocol handler
                    protocolHandler.setSuppressed(false);
                    // Restart with --resume
                    start(storedConfig.withResume(resumeSessionId));
                } catch (Exception e) {
                    LOG.error("Failed to restart CLI after interrupt", e);
                    fireStateChanged(ProcessState.ERROR);
                }
            }, "claude-cli-restart");
            restartThread.setDaemon(true);
            restartThread.start();
        }
    }

    /**
     * Stops the CLI process.
     */
    public synchronized void stop() {
        ProcessState current = state.get();
        if (current == ProcessState.STOPPED || current == ProcessState.NOT_STARTED) {
            return;
        }

        fireStateChanged(ProcessState.STOPPING);
        busy = false;

        // Stop health monitor
        if (healthChecker != null) {
            healthChecker.shutdownNow();
            healthChecker = null;
        }

        try {
            if (processWriter != null) {
                processWriter.close();
            }
        } catch (IOException e) {
            LOG.warn("Error closing process writer", e);
        }

        if (process != null) {
            process.destroy();
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }

        if (readerThread != null) {
            readerThread.interrupt();
        }
        if (errorThread != null) {
            errorThread.interrupt();
        }

        fireStateChanged(ProcessState.STOPPED);
    }

    /**
     * Sends a user message to the CLI process.
     */
    public void sendMessage(String userContent) {
        String json = CliMessage.createUserInputJson(userContent);
        sendRawJson(json);
        busy = true;
    }

    /**
     * Sends a user message with content blocks (e.g., tool results).
     */
    public void sendMessageWithContent(List<CliMessage.ContentBlock> contentBlocks) {
        String json = CliMessage.createUserInputJsonWithContent(contentBlocks);
        sendRawJson(json);
        busy = true;
    }

    /**
     * Sends a rich message with text and images.
     */
    public void sendRichMessage(String textContent, List<byte[]> imageDataList) {
        String json = CliMessage.createUserInputJsonRich(textContent, imageDataList);
        sendRawJson(json);
        busy = true;
    }

    /**
     * Sends a permission response to the CLI process.
     */
    public void sendPermissionResponse(String toolUseId, boolean allow) {
        String json = CliMessage.createPermissionResponse(toolUseId, allow);
        sendRawJson(json);
    }

    /**
     * Sends a control response to the CLI process.
     */
    public void sendControlResponse(String requestId, boolean allow, Object toolInput) {
        String json = CliMessage.createControlResponse(requestId, allow, toolInput);
        sendRawJson(json);
    }

    /**
     * Sends a control response without tool input.
     */
    public void sendControlResponse(String requestId, boolean allow) {
        String json = CliMessage.createControlResponse(requestId, allow);
        sendRawJson(json);
    }

    /**
     * Sends raw JSON to the CLI process stdin.
     */
    public synchronized void sendRawJson(String json) {
        if (state.get() != ProcessState.RUNNING || processWriter == null) {
            LOG.warn("Cannot send message: CLI process is not running");
            return;
        }

        try {
            processWriter.write(json);
            processWriter.newLine();
            processWriter.flush();
            LOG.info("Sent to CLI: " + truncateForLog(json));
        } catch (IOException e) {
            LOG.error("Error sending message to CLI process", e);
            for (ICliMessageListener listener : messageListeners) {
                try {
                    listener.onConnectionError(e);
                } catch (Exception ex) {
                    LOG.error("Error in message listener (connection error)", ex);
                }
            }
        }
    }

    /**
     * Returns the CLI path from settings, or attempts to find it automatically.
     */
    public static String getCliPath() {
        String configuredPath = ClaudeSettings.getInstance().getState().cliPath;
        if (configuredPath != null && !configuredPath.isEmpty()) {
            File f = new File(configuredPath);
            if (f.exists() && f.canExecute()) {
                return configuredPath;
            }
        }

        // Try common locations
        String home = System.getProperty("user.home");
        String[] commonPaths = {
            "/usr/local/bin/claude",
            "/usr/bin/claude",
            home + "/.local/bin/claude",
            home + "/.npm/bin/claude",
            home + "/.npm-global/bin/claude",
            home + "/.nvm/versions/node/default/bin/claude",
            home + "/bin/claude",
            home + "/.yarn/bin/claude",
            home + "/.bun/bin/claude",
            "/opt/homebrew/bin/claude",
            // Windows paths
            home + "/AppData/Roaming/npm/claude.cmd",
            home + "/AppData/Local/Programs/claude/claude.exe",
            "C:/Program Files/claude/claude.exe"
        };

        for (String path : commonPaths) {
            File f = new File(path);
            if (f.exists() && f.canExecute()) {
                return path;
            }
        }

        // Try 'which' command
        try {
            ProcessBuilder pb = new ProcessBuilder("which", "claude");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = reader.readLine();
                if (line != null && !line.isEmpty()) {
                    p.waitFor();
                    File f = new File(line.trim());
                    if (f.exists()) {
                        return line.trim();
                    }
                }
            }
        } catch (Exception e) {
            LOG.info("Could not find claude via 'which': " + e.getMessage());
        }

        return null;
    }

    /**
     * Returns the Claude CLI version string, or null if unavailable.
     */
    public static String getVersion() {
        String cliPath = getCliPath();
        if (cliPath == null) return null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cliPath, "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = reader.readLine();
                p.waitFor();
                return line != null ? line.trim() : null;
            }
        } catch (Exception e) {
            LOG.info("Could not get CLI version: " + e.getMessage());
            return null;
        }
    }

    /**
     * Returns user-facing CLI installation instructions.
     */
    public static String getInstallInstructions() {
        return "Claude CLI is not installed. Install it with:\n\n" +
               "  npm install -g @anthropic-ai/claude-code\n\n" +
               "Or download from https://claude.ai/download\n\n" +
               "Then configure the path in Settings > Tools > Claude Code.";
    }

    /**
     * Checks whether the user is authenticated via OAuth.
     */
    public static boolean isOAuthAuthenticated() {
        String cliPath = getCliPath();
        if (cliPath == null) {
            LOG.warn("Cannot check OAuth status: CLI path not found");
            return false;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(cliPath, "auth", "status", "--json");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
            }
            int exitCode = p.waitFor();
            if (exitCode == 0) {
                String json = output.toString().trim();
                if (!json.isEmpty()) {
                    try {
                        Map<String, Object> parsed = JsonParser.parseObject(json);
                        String authMethod = JsonParser.getString(parsed, "auth_method");
                        return "oauth".equals(authMethod);
                    } catch (Exception e) {
                        LOG.warn("Failed to parse auth status JSON: " + e.getMessage());
                    }
                }
                return true;
            }
        } catch (Exception e) {
            LOG.warn("Error checking OAuth status: " + e.getMessage());
        }
        return false;
    }

    /**
     * Restarts the CLI process using the previously stored configuration.
     */
    public void restart() throws IOException {
        if (storedConfig == null) {
            throw new IOException("Cannot restart: no previous configuration stored");
        }
        stop();
        start(storedConfig);
    }

    /**
     * Restarts the CLI process with a new configuration.
     */
    public void restart(CliProcessConfig newConfig) throws IOException {
        stop();
        start(newConfig);
    }

    /**
     * Returns the exit code of the last CLI process, or -1 if not available.
     */
    public int getLastExitCode() {
        return lastExitCode;
    }

    // --- Private methods ---

    /**
     * Starts a background health monitor that polls the process every 2 seconds.
     * Detects unexpected process death and transitions to ERROR state.
     */
    private void startHealthMonitor() {
        if (healthChecker != null) {
            healthChecker.shutdownNow();
        }
        healthChecker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "claude-cli-health-monitor");
            t.setDaemon(true);
            return t;
        });
        healthChecker.scheduleAtFixedRate(() -> {
            try {
                if (process != null && !process.isAlive() && state.get() == ProcessState.RUNNING) {
                    lastExitCode = process.exitValue();
                    LOG.warn("CLI process died unexpectedly with exit code: " + lastExitCode);
                    fireStateChanged(ProcessState.ERROR);
                    if (healthChecker != null) {
                        healthChecker.shutdownNow();
                    }
                }
            } catch (Exception e) {
                LOG.warn("Health monitor error: " + e.getMessage());
            }
        }, 2, 2, TimeUnit.SECONDS);
    }

    private List<String> buildCommand(String cliPath, CliProcessConfig config) {
        List<String> command = new ArrayList<>();
        command.add(cliPath);
        command.add("--output-format");
        command.add("stream-json");
        command.add("--verbose");
        command.add("--input-format");
        command.add("stream-json");
        command.add("--permission-prompt-tool");
        command.add("stdio");
        command.add("--include-partial-messages");

        if (config.getPermissionMode() != null && !config.getPermissionMode().isEmpty()) {
            command.add("--permission-mode");
            command.add(config.getPermissionMode());
        }

        if (config.getEffort() != null && !config.getEffort().isEmpty()) {
            command.add("--effort");
            command.add(config.getEffort());
        }

        if (config.getModel() != null && !config.getModel().isEmpty()) {
            command.add("--model");
            command.add(config.getModel());
        }

        if (config.getSessionId() != null && !config.getSessionId().isEmpty()) {
            command.add("--session-id");
            command.add(config.getSessionId());
        }

        if (config.isContinueSession()) {
            command.add("--continue");
        }

        if (config.getResumeSessionId() != null && !config.getResumeSessionId().isEmpty()) {
            command.add("--resume");
            command.add(config.getResumeSessionId());
        }

        if (config.getMaxTurns() > 0) {
            command.add("--max-turns");
            command.add(String.valueOf(config.getMaxTurns()));
        }

        if (config.getAppendSystemPrompt() != null && !config.getAppendSystemPrompt().isEmpty()) {
            command.add("--append-system-prompt");
            command.add(config.getAppendSystemPrompt());
        }

        if (config.getAllowedTools() != null) {
            for (String tool : config.getAllowedTools()) {
                command.add("--allowedTools");
                command.add(tool);
            }
        }

        if (config.getAdditionalDirs() != null) {
            for (String dir : config.getAdditionalDirs()) {
                command.add("--add-dir");
                command.add(dir);
            }
        }

        // Apply settings-based options
        ClaudeSettings.State settings = ClaudeSettings.getInstance().getState();
        if (settings != null) {
            if (settings.maxTokens > 0) {
                command.add("--max-tokens");
                command.add(String.valueOf(settings.maxTokens));
            }
            if (settings.systemPrompt != null && !settings.systemPrompt.isEmpty()
                && (config.getAppendSystemPrompt() == null || config.getAppendSystemPrompt().isEmpty())) {
                command.add("--append-system-prompt");
                command.add(settings.systemPrompt);
            }
        }

        return command;
    }

    private void readProcessOutput() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (state.get() == ProcessState.RUNNING && (line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                try {
                    processNdjsonLine(line);
                } catch (Exception e) {
                    LOG.error("Error processing NDJSON line: " + truncateForLog(line), e);
                    for (ICliMessageListener listener : messageListeners) {
                        try {
                            listener.onParseError(line, e);
                        } catch (Exception ex) {
                            LOG.error("Error in parse error listener", ex);
                        }
                    }
                }
            }
        } catch (IOException e) {
            if (state.get() == ProcessState.RUNNING) {
                LOG.error("Error reading CLI process output", e);
                for (ICliMessageListener listener : messageListeners) {
                    try {
                        listener.onConnectionError(e);
                    } catch (Exception ex) {
                        LOG.error("Error in connection error listener", ex);
                    }
                }
            }
        }

        // If we exit the read loop while supposedly running, the process died
        if (state.get() == ProcessState.RUNNING) {
            fireStateChanged(ProcessState.STOPPED);
        }
    }

    private void readProcessErrors() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while (state.get() == ProcessState.RUNNING && (line = reader.readLine()) != null) {
                LOG.warn("CLI stderr: " + line);
            }
        } catch (IOException e) {
            if (state.get() == ProcessState.RUNNING) {
                LOG.warn("Error reading CLI process stderr", e);
            }
        }
    }

    private void processNdjsonLine(String line) {
        CliMessage message = protocolHandler.parseLine(line);
        if (message == null) {
            LOG.info("Unparseable NDJSON line: " + truncateForLog(line));
            return;
        }
        if (message == CliMessage.IGNORED) {
            return;  // Recognized but no action needed (e.g. tool_use_summary, rate_limit_event)
        }

        // Extract session/model info from system init
        if (message instanceof CliMessage.SystemInit) {
            CliMessage.SystemInit init = (CliMessage.SystemInit) message;
            this.sessionId = init.getSessionId();
            this.currentModel = init.getModel();
        }

        // Mark not busy when result arrives
        if (message instanceof CliMessage.ResultMessage) {
            busy = false;
        }

        // Dispatch to all listeners via the unified onMessage method
        for (ICliMessageListener listener : messageListeners) {
            try {
                listener.onMessage(message);
            } catch (Exception e) {
                LOG.error("Error in message listener", e);
            }
        }
    }

    private void fireStateChanged(ProcessState newState) {
        ProcessState oldState = state.getAndSet(newState);
        if (oldState == newState) return;
        for (ICliStateListener listener : stateListeners) {
            try {
                listener.onStateChanged(oldState, newState);
            } catch (Exception e) {
                LOG.error("Error notifying state listener", e);
            }
        }
    }

    private static String truncateForLog(String s) {
        if (s == null) return "null";
        if (s.length() <= 200) return s;
        return s.substring(0, 200) + "...[truncated]";
    }
}
