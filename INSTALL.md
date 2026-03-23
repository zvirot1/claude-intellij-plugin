# Claude Code Plugin for JetBrains IDEs — Installation Guide

## Prerequisites

1. **JetBrains IDE** (version 2024.1 or later):
   - IntelliJ IDEA (Community / Ultimate)
   - WebStorm
   - PyCharm (Community / Professional)
   - PhpStorm
   - GoLand
   - CLion
   - Rider
   - RubyMine
   - DataGrip
   - Android Studio (2024.1+)

2. **Claude CLI** installed and authenticated:
   ```bash
   # Install via npm
   npm install -g @anthropic-ai/claude-code

   # Or via Homebrew (macOS)
   brew install claude-code

   # Authenticate
   claude auth login
   ```

3. **Java 17+** runtime (bundled with JetBrains IDEs 2024.1+)

---

## Offline Installation (from ZIP)

### Step 1 — Locate the Plugin File

The plugin distribution file is:
```
build/distributions/claude-intellij-1.0.0.zip
```

> **Do NOT extract the ZIP file.** Install it as-is.

### Step 2 — Install in Your IDE

1. Open your JetBrains IDE
2. Go to **Settings / Preferences**:
   - macOS: `Cmd + ,`
   - Windows/Linux: `Ctrl + Alt + S`
3. Navigate to **Plugins**
4. Click the **⚙️ gear icon** (top right) → **Install Plugin from Disk...**
5. Select `claude-intellij-1.0.0.zip`
6. Click **OK**
7. Click **Restart IDE** when prompted

### Step 3 — Verify Installation

After restart:
1. Go to **View → Tool Windows** — you should see **Claude** in the list
2. Click on it to open the Claude panel
3. The status bar at the bottom should show "Claude Code: Disconnected"

### Step 4 — Configure CLI Path (if needed)

The plugin auto-detects Claude CLI from standard locations:
- macOS/Linux: `~/.local/bin/claude`, `/usr/local/bin/claude`, `~/.npm/bin/claude`
- Windows: `%LOCALAPPDATA%\npm\claude.cmd`, `%APPDATA%\npm\claude.cmd`

If your CLI is elsewhere:
1. Go to **Settings → Tools → Claude Code**
2. Set the **CLI Path** to the full path of the `claude` executable

### Step 5 — Start Using

1. Open any project
2. Open the Claude panel (**View → Tool Windows → Claude** or `Alt+Shift+C`)
3. Type a message and press **Enter** to start a conversation
4. Claude will connect automatically

---

## Features

### Chat Panel
- Full conversational interface with Claude Code CLI
- Markdown rendering with syntax-highlighted code blocks
- Copy / Apply to Editor / Insert at Cursor buttons on code blocks
- Image drag-and-drop and clipboard paste
- File attachment with `@` mentions

### Slash Commands
Type `/` in the input field to see available commands:
- `/model <name>` — Switch model (sonnet, opus, haiku)
- `/rules` — Edit rules and permissions
- `/mcp` — Manage MCP servers
- `/hooks` — Configure hooks
- `/memory` — Edit memory and context
- `/skills` — Browse skills and plugins
- `/history` — View and resume session history
- `/clear` — Clear conversation
- `/new` — Start new session
- `/resume [id]` — Resume a previous session

### Settings Menu
Click the **⚙️ gear** button in the header for quick access to:
- Preferences
- Rules & Permissions
- MCP Servers
- Hooks
- Memory & Context
- Skills & Plugins
- Session History

### Keyboard Shortcuts
| Shortcut | Action |
|----------|--------|
| `Enter` | Send message |
| `Shift+Enter` | New line |
| `Escape` | Clear input |
| `Alt+Shift+C` | Toggle Claude panel |
| `Alt+Shift+N` | New session |
| `Ctrl+Shift+Enter` | Focus toggle |

### Context Menu (Editor)
Right-click in any editor to access:
- **Send Selection to Claude** — Send highlighted code
- **Explain This Code** — Get code explanation
- **Review This Code** — Get code review
- **Refactor This Code** — Get refactoring suggestions
- **Analyze This File** — Analyze the entire file

### Status Bar
Bottom of the Claude panel shows:
- Connection status (Connected / Disconnected)
- Session ID
- Active model name
- Token usage (input / output)
- Session cost

---

## Troubleshooting

### Plugin doesn't appear in Tool Windows
- Ensure the IDE version is 2024.1 or later
- Check **Settings → Plugins** that Claude Code is enabled
- Restart the IDE

### "CLI not found" error
- Verify Claude CLI is installed: `claude --version`
- Set the full path in **Settings → Tools → Claude Code → CLI Path**
- On macOS: check if `/usr/local/bin/claude` or `~/.local/bin/claude` exists

### Connection fails
- Ensure you're authenticated: `claude auth login`
- Check the IDE log (**Help → Show Log in Finder/Explorer**) for errors
- Try clicking the **Reconnect** button in the Claude panel

### File changes not appearing in editor
- The plugin automatically refreshes the VFS after tool completion
- If files still appear stale, press `Cmd+Shift+A` → "Reload All from Disk"

---

## Uninstalling

1. Go to **Settings → Plugins**
2. Find **Claude Code** in the Installed tab
3. Click **⚙️** → **Uninstall**
4. Restart the IDE

---

## Building from Source

```bash
# Clone the repository
git clone <repository-url>
cd cloude-intelij

# Build the plugin
./gradlew buildPlugin

# The distribution ZIP will be at:
# build/distributions/claude-intellij-1.0.0.zip

# Run in sandbox for testing
./gradlew runIde
```

## Compatibility

| Property | Value |
|----------|-------|
| Plugin ID | `com.anthropic.claude.intellij` |
| Version | 1.0.0 |
| Platform | All JetBrains IDEs |
| Min IDE Version | 2024.1 (build 241) |
| Max IDE Version | 2025.1.x (build 251.*) |
| Java | 17+ |
| CLI Dependency | Claude Code CLI (`@anthropic-ai/claude-code`) |
