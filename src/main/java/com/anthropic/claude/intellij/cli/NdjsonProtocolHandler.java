package com.anthropic.claude.intellij.cli;

import java.util.*;

import com.anthropic.claude.intellij.util.JsonParser;
import com.intellij.openapi.diagnostic.Logger;

/**
 * Parses NDJSON (newline-delimited JSON) lines from the Claude CLI process
 * and converts them into typed CliMessage objects.
 */
public class NdjsonProtocolHandler {

    private static final Logger LOG = Logger.getInstance(NdjsonProtocolHandler.class);

    public NdjsonProtocolHandler() {}

    /**
     * Parses a single NDJSON line into a CliMessage, or returns null if unrecognized.
     */
    @SuppressWarnings("unchecked")
    public CliMessage parseLine(String line) {
        if (line == null || line.trim().isEmpty()) {
            return null;
        }

        try {
            Map<String, Object> json = JsonParser.parseObject(line);
            String type = JsonParser.getString(json, "type");

            if (type == null) {
                LOG.info("NDJSON line has no 'type' field: " + truncate(line));
                return null;
            }

            switch (type) {
                case "system":
                    return parseSystemInit(json);
                case "assistant":
                    return parseAssistantMessage(json);
                case "user":
                    return parseUserMessage(json);
                case "result":
                    return parseResultMessage(json);
                case "stream_event":
                    return parseStreamEvent(json);
                case "tool_use_permission":
                case "permission_request":
                case "tool_permission":
                    return parsePermissionRequest(json);
                case "control_request":
                    // New CLI format for permission prompts (can_use_tool subtype)
                    return parseControlRequest(json);
                case "rate_limit_event":
                    // Informational event about API rate limits — no action needed
                    LOG.info("[NDJSON] rate_limit_event received");
                    return CliMessage.IGNORED;
                case "tool_use_summary":
                    return parseToolUseSummary(json);
                default:
                    LOG.info("Unrecognized NDJSON message type: " + type);
                    // Check if this might be a permission-related message
                    if (type.contains("permission") || type.contains("Permission")) {
                        return parsePermissionRequest(json);
                    }
                    return null;
            }
        } catch (Exception e) {
            LOG.warn("Failed to parse NDJSON line: " + truncate(line) + " - " + e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private CliMessage.SystemInit parseSystemInit(Map<String, Object> json) {
        CliMessage.SystemInit init = new CliMessage.SystemInit();
        init.setSubtype(JsonParser.getString(json, "subtype"));
        init.setSessionId(JsonParser.getString(json, "session_id"));
        init.setModel(JsonParser.getString(json, "model"));
        init.setCwd(JsonParser.getString(json, "cwd"));
        // CLI uses camelCase "permissionMode"
        init.setPermissionMode(JsonParser.getString(json, "permissionMode"));

        List<Object> toolsList = JsonParser.getList(json, "tools");
        if (toolsList != null) {
            List<String> tools = new ArrayList<>();
            for (Object tool : toolsList) {
                if (tool != null) {
                    tools.add(tool.toString());
                }
            }
            init.setTools(tools);
        }

        LOG.info("Parsed system init: sessionId=" + init.getSessionId() + ", model=" + init.getModel());
        return init;
    }

    @SuppressWarnings("unchecked")
    private CliMessage.AssistantMessage parseAssistantMessage(Map<String, Object> json) {
        CliMessage.AssistantMessage msg = new CliMessage.AssistantMessage();

        // The assistant message has a nested "message" object containing the actual content
        // Format: {"type":"assistant","message":{"content":[...],"usage":{...},"stop_reason":"..."}}
        Map<String, Object> messageObj = JsonParser.getMap(json, "message");
        Map<String, Object> source = (messageObj != null) ? messageObj : json;

        msg.setStopReason(JsonParser.getString(source, "stop_reason"));

        List<Object> contentList = JsonParser.getList(source, "content");
        if (contentList != null) {
            msg.setContent(parseContentBlocks(contentList));
        }

        Map<String, Object> usageMap = JsonParser.getMap(source, "usage");
        if (usageMap != null) {
            msg.setUsage(parseUsageData(usageMap));
        }

        LOG.info("Parsed assistant message: contentBlocks=" +
            (msg.getContent() != null ? msg.getContent().size() : 0));
        return msg;
    }

    @SuppressWarnings("unchecked")
    private CliMessage.UserMessage parseUserMessage(Map<String, Object> json) {
        CliMessage.UserMessage msg = new CliMessage.UserMessage();

        // User messages may also have a nested "message" object
        Map<String, Object> messageObj = JsonParser.getMap(json, "message");
        Map<String, Object> source = (messageObj != null) ? messageObj : json;

        List<Object> contentList = JsonParser.getList(source, "content");
        if (contentList != null) {
            msg.setContent(parseContentBlocks(contentList));
        }

        return msg;
    }

    @SuppressWarnings("unchecked")
    private CliMessage.ResultMessage parseResultMessage(Map<String, Object> json) {
        CliMessage.ResultMessage msg = new CliMessage.ResultMessage();
        msg.setSubtype(JsonParser.getString(json, "subtype"));
        msg.setResult(JsonParser.getString(json, "result"));
        msg.setSessionId(JsonParser.getString(json, "session_id"));
        // CLI uses "total_cost_usd" at the top level
        msg.setCostUsd(JsonParser.getDouble(json, "total_cost_usd",
            JsonParser.getDouble(json, "cost_usd", 0.0)));
        msg.setDurationMs(JsonParser.getLong(json, "duration_ms", 0L));
        msg.setNumTurns(JsonParser.getInt(json, "num_turns", 0));
        msg.setError(JsonParser.getBoolean(json, "is_error", false));

        // Token counts are inside the "usage" object
        Map<String, Object> usageMap = JsonParser.getMap(json, "usage");
        if (usageMap != null) {
            msg.setInputTokens(JsonParser.getInt(usageMap, "input_tokens", 0));
            msg.setOutputTokens(JsonParser.getInt(usageMap, "output_tokens", 0));
            msg.setUsage(parseUsageData(usageMap));
        } else {
            msg.setInputTokens(JsonParser.getInt(json, "input_tokens", 0));
            msg.setOutputTokens(JsonParser.getInt(json, "output_tokens", 0));
        }

        LOG.info("Parsed result: costUsd=" + msg.getCostUsd()
            + ", inputTokens=" + msg.getInputTokens()
            + ", outputTokens=" + msg.getOutputTokens()
            + ", durationMs=" + msg.getDurationMs());
        return msg;
    }

    @SuppressWarnings("unchecked")
    private CliMessage.StreamEvent parseStreamEvent(Map<String, Object> json) {
        CliMessage.StreamEvent event = new CliMessage.StreamEvent();
        event.setSessionId(JsonParser.getString(json, "session_id"));
        event.setUuid(JsonParser.getString(json, "uuid"));

        // The stream event may have a nested "event" object (newer CLI format)
        // or the event data may be directly on the top-level json (older format)
        Map<String, Object> eventData = JsonParser.getMap(json, "event");
        if (eventData == null) {
            eventData = json;
        }

        // Event type may be in "type" (nested) or "event_type" (flat)
        String eventType = JsonParser.getString(eventData, "type");
        if (eventType == null) {
            eventType = JsonParser.getString(json, "event_type");
        }
        event.setEventType(eventType);
        event.setIndex(JsonParser.getInt(eventData, "index", 0));

        Map<String, Object> contentBlockMap = JsonParser.getMap(eventData, "content_block");
        if (contentBlockMap != null) {
            event.setContentBlock(parseContentBlock(contentBlockMap));
        }

        Map<String, Object> deltaMap = JsonParser.getMap(eventData, "delta");
        if (deltaMap != null) {
            event.setDelta(parseDelta(deltaMap));
        }

        return event;
    }

    @SuppressWarnings("unchecked")
    private CliMessage.PermissionRequest parsePermissionRequest(Map<String, Object> json) {
        CliMessage.PermissionRequest req = new CliMessage.PermissionRequest();
        req.setToolUseId(JsonParser.getString(json, "tool_use_id"));
        req.setRequestId(JsonParser.getString(json, "request_id"));
        req.setControlRequest(JsonParser.getBoolean(json, "control_request", false));
        req.setToolName(JsonParser.getString(json, "tool_name"));
        req.setDescription(JsonParser.getString(json, "description"));
        req.setRawJson(json);

        Object toolInput = json.get("tool_input");
        req.setToolInput(toolInput);

        LOG.info("Parsed permission request: tool=" + req.getToolName()
            + ", controlRequest=" + req.isControlRequest());
        return req;
    }

    /**
     * Parse a "control_request" message (new CLI permission format).
     * Format: {"type":"control_request","request_id":"...","request":{"subtype":"can_use_tool","tool_name":"...","input":{...}}}
     * Response: {"type":"control_response","request_id":"...","response":{"behavior":"allow","updatedInput":{...}}}
     */
    @SuppressWarnings("unchecked")
    private CliMessage.PermissionRequest parseControlRequest(Map<String, Object> json) {
        CliMessage.PermissionRequest msg = new CliMessage.PermissionRequest();
        msg.setRawJson(json);
        msg.setControlRequest(true);

        // Top-level request_id — used to correlate the control_response
        String requestId = JsonParser.getString(json, "request_id");
        msg.setRequestId(requestId);

        // Nested "request" object contains the tool details
        Map<String, Object> request = JsonParser.getMap(json, "request");
        if (request != null) {
            msg.setToolName(JsonParser.getString(request, "tool_name"));
            msg.setToolInput(request.get("input"));

            // Build a human-readable description from the input
            Object inputObj = request.get("input");
            if (inputObj instanceof Map) {
                Map<String, Object> inputMap = (Map<String, Object>) inputObj;
                // For Bash, show the command; for others show first key
                String cmd = JsonParser.getString(inputMap, "command");
                if (cmd != null) {
                    msg.setDescription("Run: " + (cmd.length() > 80 ? cmd.substring(0, 77) + "..." : cmd));
                } else if (!inputMap.isEmpty()) {
                    Map.Entry<String, Object> first = inputMap.entrySet().iterator().next();
                    String val = first.getValue() != null ? first.getValue().toString() : "";
                    msg.setDescription(first.getKey() + ": " + (val.length() > 80 ? val.substring(0, 77) + "..." : val));
                }
            }
        }

        LOG.info("[NDJSON] control_request received: tool=" + msg.getToolName()
            + " request_id=" + requestId);
        return msg;
    }

    @SuppressWarnings("unchecked")
    private CliMessage.ToolUseSummary parseToolUseSummary(Map<String, Object> json) {
        CliMessage.ToolUseSummary msg = new CliMessage.ToolUseSummary();
        msg.setSummary(JsonParser.getString(json, "summary"));

        List<String> ids = new ArrayList<>();
        Object idsObj = json.get("preceding_tool_use_ids");
        if (idsObj instanceof List) {
            for (Object id : (List<?>) idsObj) {
                if (id != null) ids.add(id.toString());
            }
        }
        msg.setToolUseIds(ids);

        // Detect failure from summary text
        String summary = msg.getSummary();
        msg.setFailed(summary != null &&
            (summary.toLowerCase().contains("failed") || summary.toLowerCase().contains("error")));

        LOG.info("[NDJSON] tool_use_summary: " + summary + " ids=" + ids);
        return msg;
    }

    @SuppressWarnings("unchecked")
    private List<CliMessage.ContentBlock> parseContentBlocks(List<Object> contentList) {
        List<CliMessage.ContentBlock> blocks = new ArrayList<>();
        for (Object item : contentList) {
            if (item instanceof Map) {
                blocks.add(parseContentBlock((Map<String, Object>) item));
            }
        }
        return blocks;
    }

    private CliMessage.ContentBlock parseContentBlock(Map<String, Object> map) {
        CliMessage.ContentBlock block = new CliMessage.ContentBlock();
        block.setType(JsonParser.getString(map, "type"));
        block.setText(JsonParser.getString(map, "text"));
        block.setId(JsonParser.getString(map, "id"));
        block.setName(JsonParser.getString(map, "name"));
        block.setContent(JsonParser.getString(map, "content"));
        block.setToolUseId(JsonParser.getString(map, "tool_use_id"));
        block.setError(JsonParser.getBoolean(map, "is_error", false));

        Object input = map.get("input");
        block.setInput(input);

        return block;
    }

    private CliMessage.Delta parseDelta(Map<String, Object> map) {
        CliMessage.Delta delta = new CliMessage.Delta();
        delta.setType(JsonParser.getString(map, "type"));
        delta.setText(JsonParser.getString(map, "text"));
        delta.setPartialJson(JsonParser.getString(map, "partial_json"));
        delta.setStopReason(JsonParser.getString(map, "stop_reason"));
        return delta;
    }

    private CliMessage.UsageData parseUsageData(Map<String, Object> map) {
        return new CliMessage.UsageData(
            JsonParser.getInt(map, "input_tokens", 0),
            JsonParser.getInt(map, "output_tokens", 0)
        );
    }

    private static String truncate(String s) {
        if (s == null) return "null";
        if (s.length() <= 200) return s;
        return s.substring(0, 200) + "...[truncated]";
    }
}
