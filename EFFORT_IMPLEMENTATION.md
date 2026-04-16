# Effort Implementation Guide

## Concept
Effort is a CLI parameter (`--effort low|medium|high|max`) that controls how much effort Claude puts into responses. In VS Code each query spawns a new CLI process so effort always applies. In our plugins the CLI stays running, so **every effort change must restart the CLI with `--resume`**.

## 1. CliProcessConfig - Add effort field

```java
// New field
private final String effort;

// In Builder:
private String effort;
public Builder effort(String effort) { this.effort = effort; return this; }

// In withResume():
if (effort != null) b.effort(effort);
```

## 2. ClaudeCliManager.buildCommand - Add --effort flag

```java
if (config.getEffort() != null && !config.getEffort().isEmpty()) {
    command.add("--effort");
    command.add(config.getEffort());
}
```

## 3. Settings - Persist effort

```java
public String effortLevel = "medium";
```

## 4. UI - Effort slider inside Mode popup

5 dots: Auto (empty string) / Low / Medium / High / Max.
Click on dot sends `change_effort` with the value.

```html
<div class="effort-row">
    <span>Effort (<span id="effort-level-text">Medium</span>)</span>
    <div class="effort-dots">
        <span class="effort-dot" data-effort="" title="Auto"></span>
        <span class="effort-dot" data-effort="low" title="Low"></span>
        <span class="effort-dot active" data-effort="medium" title="Medium"></span>
        <span class="effort-dot" data-effort="high" title="High"></span>
        <span class="effort-dot" data-effort="max" title="Max"></span>
    </div>
</div>
```

## 5. Handler - Restart CLI with --resume (THE MOST IMPORTANT PART!)

```java
private void handleChangeEffort(String effort) {
    // 1. Save the value
    currentEffort = (effort != null && !effort.isEmpty()) ? effort : null;
    
    // 2. Persist to settings
    settings.effortLevel = (currentEffort != null) ? currentEffort : "";
    
    // 3. Restart CLI with --resume (like VS Code)
    if (cliManager.isRunning()) {
        String sessionId = model.getSessionInfo().getSessionId();
        cliManager.stop();
        
        CliProcessConfig.Builder builder = new CliProcessConfig.Builder(cliPath, projectPath)
            .permissionMode(currentMode)
            .effort(currentEffort)
            .resumeSessionId(sessionId);  // preserves conversation!
        
        cliManager.start(builder.build());
    }
}
```

## 6. startCli - Include effort on launch

```java
if (currentEffort != null && !currentEffort.isEmpty()) {
    builder.effort(currentEffort);
}
```

## Flow

```
User clicks on dot (High)
  -> JS: setEffort("high") + bridge.sendToJava("change_effort", {effort: "high"})
  -> Java: handleChangeEffort("high")
    -> saves currentEffort = "high"
    -> persists to settings
    -> stops CLI
    -> starts CLI with --effort high --resume <sessionId>
  -> New CLI runs with effort=high, conversation preserved
```

## Critical Point
**Without `--resume` the effort won't take effect** - because the old CLI is already running with the previous value. Must restart. This is what VS Code does (new CLI per query).

## VS Code Reference (from extension.js source)

```javascript
if(this.options.effort)a.push("--effort",this.options.effort);
```

VS Code passes effort as a command-line argument when spawning each CLI process.
The effort levels in the CLI are: `low`, `medium`, `high`, `max`.
Empty/null means "Auto" (don't pass the --effort flag).
