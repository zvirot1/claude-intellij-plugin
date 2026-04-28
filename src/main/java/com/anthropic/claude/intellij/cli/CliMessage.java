package com.anthropic.claude.intellij.cli;

import java.util.*;

import com.anthropic.claude.intellij.util.JsonParser;

/**
 * Abstract base class for all CLI message types exchanged with the Claude CLI process.
 * <p>
 * The Claude CLI communicates via NDJSON (newline-delimited JSON) over stdin/stdout.
 * Each line is a JSON object with a {@code "type"} field determining its schema.
 * <p>
 * <h2>Message Types (CLI → Plugin)</h2>
 * <ul>
 *   <li>{@link SystemInit} — {@code "system"}: Session initialization with session ID, model, tools, CWD</li>
 *   <li>{@link AssistantMessage} — {@code "assistant"}: Complete assistant turn with content blocks</li>
 *   <li>{@link UserMessage} — {@code "user"}: Echoed user message</li>
 *   <li>{@link ResultMessage} — {@code "result"}: Turn result with usage stats (cost, tokens, duration)</li>
 *   <li>{@link StreamEvent} — {@code "stream_event"}: Streaming content deltas (text, tool_use)</li>
 *   <li>{@link PermissionRequest} — {@code "permission_request"} or {@code "control_request"}: Tool authorization</li>
 * </ul>
 * <p>
 * <h2>Message Types (Plugin → CLI)</h2>
 * <ul>
 *   <li>{@link #createUserInputJson(String)} — User text message</li>
 *   <li>{@link #createUserInputJsonRich(String, java.util.List)} — User message with images</li>
 *   <li>{@link #createPermissionResponse(String, boolean)} — Old format permission response</li>
 *   <li>{@link #createControlResponse(String, boolean, Object)} — New format control response</li>
 * </ul>
 * <p>
 * <h2>Control Response Schema (Zod)</h2>
 * The control_response wraps a response object conforming to:
 * <pre>{@code
 * {
 *   "type": "control_response",
 *   "response": {
 *     "subtype": "success",
 *     "request_id": "<id>",
 *     "response": {
 *       "behavior": "allow" | "deny",
 *       "updatedInput": { ... },  // optional, for "allow"
 *       "message": "..."          // optional, for "deny"
 *     }
 *   }
 * }
 * }</pre>
 *
 * @see com.anthropic.claude.intellij.cli.NdjsonProtocolHandler
 * @see com.anthropic.claude.intellij.cli.ClaudeCliManager
 */
public abstract class CliMessage {

    /** Sentinel instance for recognized-but-ignored message types (e.g. tool_use_summary). */
    public static final CliMessage IGNORED = new CliMessage("ignored") {};

    private final String type;

    protected CliMessage(String type) {
        this.type = type;
    }

    public String getType() {
        return type;
    }

    /**
     * System initialization message sent by the CLI at session start.
     * Contains session metadata: session ID, model name, working directory,
     * available tools, and permission mode.
     */
    public static class SystemInit extends CliMessage {
        private String subtype;
        private String sessionId;
        private String model;
        private String cwd;
        private List<String> tools;
        private String permissionMode;

        public SystemInit() { super("system"); }

        public String getSubtype() { return subtype; }
        public void setSubtype(String subtype) { this.subtype = subtype; }
        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getCwd() { return cwd; }
        public void setCwd(String cwd) { this.cwd = cwd; }
        public List<String> getTools() { return tools; }
        public void setTools(List<String> tools) { this.tools = tools; }
        public String getPermissionMode() { return permissionMode; }
        public void setPermissionMode(String permissionMode) { this.permissionMode = permissionMode; }
    }

    /**
     * Non-init system notifications: hook_started, hook_progress, hook_response, compact_boundary.
     * Carries hook execution details (name, stdout, stderr, exit code) so consumers can surface
     * hook failures to the user.
     */
    public static class SystemNotification extends CliMessage {
        private String subtype;
        private String hookName;
        private String stdout;
        private String stderr;
        private Integer exitCode;
        private String rawJson;

        public SystemNotification() { super("system"); }

        public String getSubtype() { return subtype; }
        public void setSubtype(String subtype) { this.subtype = subtype; }
        public String getHookName() { return hookName; }
        public void setHookName(String hookName) { this.hookName = hookName; }
        public String getStdout() { return stdout; }
        public void setStdout(String stdout) { this.stdout = stdout; }
        public String getStderr() { return stderr; }
        public void setStderr(String stderr) { this.stderr = stderr; }
        public Integer getExitCode() { return exitCode; }
        public void setExitCode(Integer exitCode) { this.exitCode = exitCode; }
        public String getRawJson() { return rawJson; }
        public void setRawJson(String rawJson) { this.rawJson = rawJson; }

        /**
         * Heuristic check whether this notification represents a hook error,
         * looking at stdout/stderr for common error patterns or non-zero exit code.
         */
        public boolean hasErrorIndicator() {
            String s = (stderr != null ? stderr : "") + " " + (stdout != null ? stdout : "");
            String low = s.toLowerCase();
            return low.contains("[error]")
                || low.contains("error:")
                || low.contains("token has expired")
                || low.contains("authentication failed")
                || low.contains("unauthorized")
                || low.contains("permission denied")
                || (exitCode != null && exitCode != 0);
        }
    }

    /**
     * Complete assistant message with content blocks (text, tool_use, tool_result).
     * Includes stop reason and optional usage data.
     */
    public static class AssistantMessage extends CliMessage {
        private List<ContentBlock> content = new ArrayList<>();
        private String stopReason;
        private UsageData usage;

        public AssistantMessage() { super("assistant"); }

        public List<ContentBlock> getContent() { return content; }
        public void setContent(List<ContentBlock> content) { this.content = content; }
        public String getStopReason() { return stopReason; }
        public void setStopReason(String stopReason) { this.stopReason = stopReason; }
        public UsageData getUsage() { return usage; }
        public void setUsage(UsageData usage) { this.usage = usage; }
    }

    /** Echoed user message from the CLI stream. */
    public static class UserMessage extends CliMessage {
        private List<ContentBlock> content = new ArrayList<>();

        public UserMessage() { super("user"); }

        public List<ContentBlock> getContent() { return content; }
        public void setContent(List<ContentBlock> content) { this.content = content; }
    }

    /**
     * Turn result message with usage statistics.
     * Contains cost (USD), token counts, duration, number of turns, and session ID.
     * Sent after each complete assistant turn.
     */
    public static class ResultMessage extends CliMessage {
        private String subtype;
        private String result;
        private String sessionId;
        private double costUsd;
        private int inputTokens;
        private int outputTokens;
        private long durationMs;
        private int numTurns;
        private boolean isError;
        private UsageData usage;

        public ResultMessage() { super("result"); }

        public String getSubtype() { return subtype; }
        public void setSubtype(String subtype) { this.subtype = subtype; }
        public String getResult() { return result; }
        public void setResult(String result) { this.result = result; }
        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
        public double getCostUsd() { return costUsd; }
        public void setCostUsd(double costUsd) { this.costUsd = costUsd; }
        public int getInputTokens() { return inputTokens; }
        public void setInputTokens(int inputTokens) { this.inputTokens = inputTokens; }
        public int getOutputTokens() { return outputTokens; }
        public void setOutputTokens(int outputTokens) { this.outputTokens = outputTokens; }
        public long getDurationMs() { return durationMs; }
        public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
        public int getNumTurns() { return numTurns; }
        public void setNumTurns(int numTurns) { this.numTurns = numTurns; }
        public boolean isError() { return isError; }
        public void setError(boolean isError) { this.isError = isError; }
        public UsageData getUsage() { return usage; }
        public void setUsage(UsageData usage) { this.usage = usage; }
    }

    /**
     * Streaming event for real-time content delivery.
     * Event types include: content_block_start, content_block_delta,
     * content_block_stop, message_start, message_delta, message_stop.
     * The nested {@code event} object contains the actual event data.
     */
    public static class StreamEvent extends CliMessage {
        private String eventType;
        private int index;
        private ContentBlock contentBlock;
        private Delta delta;
        private String sessionId;
        private String uuid;

        public StreamEvent() { super("stream_event"); }

        public String getEventType() { return eventType; }
        public void setEventType(String eventType) { this.eventType = eventType; }
        public int getIndex() { return index; }
        public void setIndex(int index) { this.index = index; }
        public ContentBlock getContentBlock() { return contentBlock; }
        public void setContentBlock(ContentBlock contentBlock) { this.contentBlock = contentBlock; }
        public Delta getDelta() { return delta; }
        public void setDelta(Delta delta) { this.delta = delta; }
        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
        public String getUuid() { return uuid; }
        public void setUuid(String uuid) { this.uuid = uuid; }
    }

    /**
     * A content block within a message (text, tool_use, or tool_result).
     * For tool_use: has id, name, input. For tool_result: has toolUseId, content, isError.
     */
    public static class ContentBlock {
        private String type;
        private String text;
        private String id;
        private String name;
        private Object input;
        private String content;
        private String toolUseId;
        private boolean isError;

        public ContentBlock() {}

        public ContentBlock(String type) {
            this.type = type;
        }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Object getInput() { return input; }
        public void setInput(Object input) { this.input = input; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public String getToolUseId() { return toolUseId; }
        public void setToolUseId(String toolUseId) { this.toolUseId = toolUseId; }
        public boolean isError() { return isError; }
        public void setError(boolean isError) { this.isError = isError; }

        public String getInputAsString() {
            if (input == null) return "";
            if (input instanceof String) return (String) input;
            return input.toString();
        }
    }

    /** Streaming delta within a content_block_delta event. Contains incremental text or partial JSON. */
    public static class Delta {
        private String type;
        private String text;
        private String partialJson;
        private String stopReason;

        public Delta() {}

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public String getPartialJson() { return partialJson; }
        public void setPartialJson(String partialJson) { this.partialJson = partialJson; }
        public String getStopReason() { return stopReason; }
        public void setStopReason(String stopReason) { this.stopReason = stopReason; }
    }

    /** Token usage data (input + output token counts). */
    public static class UsageData {
        private int inputTokens;
        private int outputTokens;

        public UsageData() {}
        public UsageData(int inputTokens, int outputTokens) {
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
        }

        public int getInputTokens() { return inputTokens; }
        public void setInputTokens(int inputTokens) { this.inputTokens = inputTokens; }
        public int getOutputTokens() { return outputTokens; }
        public void setOutputTokens(int outputTokens) { this.outputTokens = outputTokens; }
    }

    /**
     * Permission/control request from the CLI, asking the user to authorize a tool call.
     * <p>
     * Two formats exist:
     * <ul>
     *   <li><b>Legacy</b> ({@code permission_request}): Uses {@code toolUseId} for identification.
     *       Response via {@link #createPermissionResponse(String, boolean)}.</li>
     *   <li><b>New</b> ({@code control_request}): Uses {@code requestId} for identification.
     *       Has {@code controlRequest=true}. Response via {@link #createControlResponse(String, boolean, Object)}.</li>
     * </ul>
     * Both carry toolName, description, and toolInput for display in the permission banner.
     */
    public static class PermissionRequest extends CliMessage {
        private String toolUseId;
        private String requestId;
        private boolean controlRequest;
        private String toolName;
        private String description;
        private Object toolInput;
        private Map<String, Object> rawJson;

        public PermissionRequest() { super("permission_request"); }

        public String getToolUseId() { return toolUseId; }
        public void setToolUseId(String toolUseId) { this.toolUseId = toolUseId; }
        public String getRequestId() { return requestId; }
        public void setRequestId(String requestId) { this.requestId = requestId; }
        public boolean isControlRequest() { return controlRequest; }
        public void setControlRequest(boolean controlRequest) { this.controlRequest = controlRequest; }
        public String getToolName() { return toolName; }
        public void setToolName(String toolName) { this.toolName = toolName; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public Object getToolInput() { return toolInput; }
        public void setToolInput(Object toolInput) { this.toolInput = toolInput; }
        public Map<String, Object> getRawJson() { return rawJson; }
        public void setRawJson(Map<String, Object> rawJson) { this.rawJson = rawJson; }
    }

    /**
     * Creates a JSON string for sending a simple text message to the CLI.
     * Format: {@code {"type":"user","message":{"role":"user","content":"..."}}}
     */
    public static String createUserInputJson(String userContent) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"");
        sb.append(JsonParser.escapeJsonString(userContent));
        sb.append("\"}}");
        return sb.toString();
    }

    /**
     * Creates a JSON string for sending a multi-block message (e.g., tool results) to the CLI.
     * Content blocks can include text, tool_result, and image types.
     */
    public static String createUserInputJsonWithContent(List<ContentBlock> contentBlocks) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":[");
        for (int i = 0; i < contentBlocks.size(); i++) {
            ContentBlock block = contentBlocks.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"type\":\"").append(JsonParser.escapeJsonString(block.getType())).append("\"");
            if (block.getToolUseId() != null) {
                sb.append(",\"tool_use_id\":\"").append(JsonParser.escapeJsonString(block.getToolUseId())).append("\"");
            }
            if (block.getContent() != null) {
                sb.append(",\"content\":\"").append(JsonParser.escapeJsonString(block.getContent())).append("\"");
            }
            if (block.isError()) {
                sb.append(",\"is_error\":true");
            }
            sb.append("}");
        }
        sb.append("]}}");
        return sb.toString();
    }

    /**
     * Creates a JSON string for sending a message with inline images (base64-encoded PNG) to the CLI.
     * Falls back to {@link #createUserInputJson(String)} if no images are provided.
     */
    public static String createUserInputJsonRich(String textContent, List<byte[]> imageDataList) {
        if (imageDataList == null || imageDataList.isEmpty()) {
            return createUserInputJson(textContent);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":[");
        boolean first = true;
        for (byte[] imageBytes : imageDataList) {
            if (!first) sb.append(",");
            first = false;
            String base64 = java.util.Base64.getEncoder().encodeToString(imageBytes);
            sb.append("{\"type\":\"image\",\"source\":{");
            sb.append("\"type\":\"base64\",");
            sb.append("\"media_type\":\"image/png\",");
            sb.append("\"data\":\"").append(base64).append("\"}}");
        }
        if (!first) sb.append(",");
        sb.append("{\"type\":\"text\",\"text\":\"").append(JsonParser.escapeJsonString(textContent)).append("\"}");
        sb.append("]}}");
        return sb.toString();
    }

    /**
     * Creates a legacy permission response JSON for the old {@code permission_request} format.
     * Uses {@code toolUseId} to identify which request is being responded to.
     *
     * @param toolUseId the tool_use_id from the original permission_request
     * @param allow     true to allow, false to deny
     */
    public static String createPermissionResponse(String toolUseId, boolean allow) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"permission_response\"");
        if (toolUseId != null) {
            sb.append(",\"tool_use_id\":\"").append(JsonParser.escapeJsonString(toolUseId)).append("\"");
        }
        sb.append(",\"permission\":\"").append(allow ? "allow" : "deny").append("\"");
        sb.append("}");
        return sb.toString();
    }

    /**
     * Creates a control response JSON for the new {@code control_request} format.
     * <p>
     * On allow: sends {@code {"behavior":"allow","updatedInput":{...}}} where updatedInput
     * echoes back the original tool input (or empty object).
     * <p>
     * On deny: sends {@code {"behavior":"deny","message":"User denied"}}.
     *
     * @param requestId the request_id from the original control_request
     * @param allow     true to allow, false to deny
     * @param toolInput the original tool input to echo back (nullable; defaults to empty object)
     */
    public static String createControlResponse(String requestId, boolean allow, Object toolInput) {
        String escapedId = requestId != null ? JsonParser.escapeJsonString(requestId) : "";
        String innerResponse;
        if (allow) {
            String inputJson = (toolInput != null) ? JsonParser.toJson(toolInput) : "{}";
            innerResponse = "{\"behavior\":\"allow\",\"updatedInput\":" + inputJson + "}";
        } else {
            innerResponse = "{\"behavior\":\"deny\",\"message\":\"User denied\"}";
        }
        return "{\"type\":\"control_response\",\"response\":{\"subtype\":\"success\",\"request_id\":\""
            + escapedId + "\",\"response\":" + innerResponse + "}}";
    }

    /** Convenience overload that defaults toolInput to null. */
    public static String createControlResponse(String requestId, boolean allow) {
        return createControlResponse(requestId, allow, null);
    }

    /**
     * Represents a tool_use_summary message from the CLI, indicating one or more
     * tool calls have completed. Contains the summary text and the tool use IDs.
     */
    public static class ToolUseSummary extends CliMessage {
        private String summary;
        private java.util.List<String> toolUseIds;
        private boolean failed;

        public ToolUseSummary() { super("tool_use_summary"); }

        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }

        public java.util.List<String> getToolUseIds() { return toolUseIds; }
        public void setToolUseIds(java.util.List<String> toolUseIds) { this.toolUseIds = toolUseIds; }

        public boolean isFailed() { return failed; }
        public void setFailed(boolean failed) { this.failed = failed; }
    }
}
