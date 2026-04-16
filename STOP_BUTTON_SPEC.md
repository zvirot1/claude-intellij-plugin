# Send/Stop Button Toggle + Conversation Memory After Stop

## Background
In the Eclipse version of this plugin, two issues were fixed that also need to be implemented in the IntelliJ version:

1. **Send button should toggle to Stop button during streaming** (like VS Code does)
2. **After Stop, the CLI should restart with `--resume <sessionId>`** to preserve conversation memory

## Change 1: Send Button Toggles to Stop Button

### Concept
When the user sends a message and streaming begins, the send button (↑) transforms into a stop button (■). When streaming ends (or user clicks stop), it reverts back to send (↑).

### Implementation Steps

1. **Add a state flag:**
```java
private boolean sendButtonIsStop = false;
```

2. **Modify the send button click handler to check mode:**
```java
// Instead of always calling handleInput():
sendButton.addActionListener(e -> {
    if (sendButtonIsStop) {
        handleStop();
    } else {
        handleInput();
    }
});
```

3. **Add two toggle methods:**
```java
/** Switch send button to "stop" mode — shown while streaming */
private void setSendButtonToStop() {
    sendButtonIsStop = true;
    sendButton.setText("■");      // or setIcon() with a stop icon
    sendButton.setToolTipText("Stop current query (Escape)");
}

/** Switch send button back to "send" mode — shown when idle */
private void setSendButtonToSend() {
    sendButtonIsStop = false;
    sendButton.setText("↑");      // or setIcon() with a send/arrow icon
    sendButton.setToolTipText("Send message (Enter)");
}
```

4. **Add a centralized `handleStop()` method** (replaces all inline stop logic):
```java
private void handleStop() {
    renderingSuppressed = true;
    cancelStreamingTimeout();
    
    // Get session ID for resume (see Change 2)
    String sessionId = null;
    SessionInfo info = model.getSessionInfo();
    if (info != null && info.getSessionId() != null && !info.getSessionId().isEmpty()) {
        sessionId = info.getSessionId();
    }
    cliManager.interruptCurrentQuery(sessionId);
    
    setSendButtonToSend();
    hideThinkingIndicator();
    updateStatus("Interrupted");
}
```

5. **Call `setSendButtonToStop()` when streaming starts** (in `handleInput()` after sending):
```java
// At the end of handleInput(), after cliManager.sendMessage(text):
renderingSuppressed = false;
setSendButtonToStop();            // <-- ADD THIS
updateStatus("Streaming...");
```

6. **Call `setSendButtonToSend()` when streaming completes** (in `onResultReceived()`):
```java
// In the result/complete callback:
hideThinkingIndicator();
setSendButtonToSend();            // <-- ADD THIS
updateStatus("Ready");
```

7. **Add Escape key binding to stop:**
```java
// In key listener for the input field:
if (keyCode == KeyEvent.VK_ESCAPE) {
    if (isAutocompleteVisible()) {
        dismissAutocomplete();
    } else if (sendButtonIsStop) {
        handleStop();
    }
}
```

8. **Update toolbar stop button** (if exists) to also use `handleStop()`:
```java
toolbarStopButton.addActionListener(e -> handleStop());
```

## Change 2: Preserve Conversation Memory After Stop

### Problem
When `interruptCurrentQuery()` kills the CLI and auto-restarts it, it uses the original config which has no session ID. So the new session starts fresh — all conversation memory is lost.

### Fix: Restart with `--resume <sessionId>`

1. **Add `withResume()` to CliProcessConfig** (creates a new config that resumes an existing session):
```java
public CliProcessConfig withResume(String resumeId) {
    Builder b = new Builder(cliPath, workingDirectory)
        .permissionMode(permissionMode)
        .model(model)
        .resumeSessionId(resumeId)      // --resume <id>
        .maxTurns(maxTurns);
    if (appendSystemPrompt != null) b.appendSystemPrompt(appendSystemPrompt);
    if (allowedTools != null) b.allowedTools(allowedTools);
    if (additionalDirs != null) b.additionalDirs(additionalDirs);
    // Do NOT copy sessionId / continueSession — resume replaces them
    return b.build();
}
```

2. **Update `interruptCurrentQuery` to accept a session ID:**
```java
// Backwards-compatible overload
public void interruptCurrentQuery() {
    interruptCurrentQuery(null);
}

public void interruptCurrentQuery(String resumeSessionId) {
    // ... (existing code: stop protocol handler, kill process tree) ...

    // In the auto-restart section, change from:
    if (config != null) {
        start(config);                    // OLD: starts fresh empty session
    }

    // To:
    if (config != null) {
        if (resumeSessionId != null && !resumeSessionId.isEmpty()) {
            start(config.withResume(resumeSessionId));  // NEW: resumes session
        } else {
            start(config);
        }
    }
}
```

3. **In `handleStop()`** — extract session ID from model and pass it:
```java
String sessionId = model.getSessionInfo().getSessionId();
cliManager.interruptCurrentQuery(sessionId);
```

## Flow Diagram

```
User sends message
  -> handleInput()
  -> sendMessage(text)
  -> setSendButtonToStop()        <- button becomes ■
  -> status = "Streaming..."

    [streaming active...]

  Option A: Response completed
    -> onResultReceived()
    -> setSendButtonToSend()      <- button returns to ↑
    -> status = "Ready"

  Option B: User clicks Stop / Escape
    -> handleStop()
    -> extract sessionId from model
    -> interruptCurrentQuery(sessionId)
    -> CLI killed -> auto-restart with --resume <sessionId>
    -> setSendButtonToSend()      <- button returns to ↑
    -> status = "Interrupted"
    -> next message continues with full memory ✓
```
