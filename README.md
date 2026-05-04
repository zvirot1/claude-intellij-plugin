# Claude Code IntelliJ Plugin

An unofficial IntelliJ Platform plugin that brings the [Claude Code CLI](https://docs.claude.com) into JetBrains IDEs as a native chat tool window — with multi-tab conversations, mode/effort controls, session persistence, file pinning, and image paste.

This is the **JetBrains-side counterpart** of the [Claude Eclipse plugin](https://github.com/zvirot1/claude-eclipse-plugin) and follows the same protocol shape and UX patterns as the official Claude Code VS Code extension.

---

## Features

### Conversations
- **Multi-tab conversations** — every tab gets its own CLI subprocess and its own conversation context (no cross-tab leakage). New tabs open via the **New Conversation Window** button or **Open in New Tab** menu.
- **Tab persistence across restarts** — open tabs are restored on the next IDE launch with their saved names and full conversation history (read straight from `~/.claude/projects/<encoded-cwd>/<session-id>.jsonl` — same source of truth as VS Code).
- **Rename tabs** — `Settings (gear)` → `Rename Tab…` to give a tab a custom title.
- **Close tabs** — trash-can icon in the chat header. Closing the last tab clears its conversation and resets the session.
- **Auto-resume** — first message in a tab starts the CLI with `--continue` so the session ID is preserved.

### Permission mode & effort
- **Mode selector** below the input — *Ask before edits / Edit automatically / Plan mode*.
  - Switching mode restarts the CLI with `--permission-mode <mode> --resume <sessionId>`, preserving the conversation.
- **Effort slider** (5 dots) — *Auto / Low / Medium / High / Max*. Same restart-with-`--resume` mechanism so the new effort takes effect immediately, matching VS Code where every query spawns a fresh CLI process.
- **`Shift+Tab`** keyboard shortcut cycles permission modes.

### Context attachment
- **`@`-mention** files in the input — autocompletes from the project tree and attaches the file (with optional line range).
- **Active-file pin** — a chip inside the input frame shows the file currently open in the editor. Auto-attaches its contents to every message (Amazon Q parity). Click `×` to dismiss for the current message; the chip re-appears as soon as you switch to another tab.
- **Image attachments** — drag-drop or **Ctrl/Cmd+V** to paste an image (e.g. a screenshot) into the input. Attached images render as inline thumbnails in the user's bubble.
- **Clean user bubble** — file context is sent to Claude but never rendered as raw `<file>` XML in the conversation transcript.

### Session history
- **Session History dialog** (`Settings (gear)` → `Session History` or `/history`) lists every Claude session for the project, including ones created from VS Code, Eclipse, or the bare `claude` CLI. Powered by `JsonlSessionScanner` which reads JSONL files directly.

### Diagnostics & robustness
- Per-turn detection of **silent-empty** results (a typical signature of `UserPromptSubmit` hooks blocking the prompt) with one automatic retry and a user-friendly explanation if the retry also fails.
- Surfacing of **hook errors** (e.g. expired AWS SSO tokens) with remediation hints in the chat.
- Detection of all `system` message subtypes (`init`, `hook_started`, `hook_progress`, `hook_response`, `compact_boundary`).
- **Diagnostic logging toggle** — `Settings → Tools → Claude Code → Enable diagnostic logging` emits verbose `[DIAG-*]` entries (`[DIAG-RAW-SYSTEM]`, `[DIAG-STDERR]`, `[DIAG-NOTIFICATION]`). Also enableable via the JVM flag `-Dclaude.diag=true`.
- **MCP servers** — read/written from `~/.claude.json` `mcpServers` (the user-scoped CLI location), with an in-place edit dialog and a 2-column env-var table.
- IntelliJ 2025.1 → 2026.2 supported (`sinceBuild=241`, `untilBuild=262.*`).

### UI niceties
- **Auto-show** the Claude tool window when a project opens.
- **Send/Stop button toggle** — the send arrow becomes a red stop square while the model is streaming, and `Esc` cancels.
- **RTL detection** in the input (Hebrew / Arabic auto-flip).
- **CSS tooltips** on header buttons (JCEF doesn't render native `title` tooltips reliably).
- **Calm IDE-blue accent** for focus/active-file chip (instead of orange that read as an error).

---

## Installation

### From a downloaded ZIP (recommended)
1. Grab the latest `claude-intellij-plugin-YYYYMMDDHHMM.zip` from the [Releases page](https://github.com/zvirot1/claude-intellij-plugin/releases) or from the repo root.
2. In IntelliJ: `File → Settings → Plugins → ⚙ → Install Plugin from Disk…`
3. Pick the ZIP, then restart the IDE.

### Building from source
```bash
git clone https://github.com/zvirot1/claude-intellij-plugin.git
cd claude-intellij-plugin/claude-intellij-plugin
./gradlew buildPlugin                  # produces build/distributions/claude-intellij-1.0.0.zip
./gradlew runIde                       # launches a sandboxed IDE with the plugin loaded
```

---

## Prerequisites
- **Claude CLI** installed and on your PATH. The plugin auto-detects `claude.cmd` / `claude` from common npm install locations and PATH; you can also set the path explicitly under `Settings → Tools → Claude Code → CLI Path`.
- An **Anthropic account** logged in via `claude` (one-time, in a terminal). The CLI handles OAuth/refresh automatically; if a refresh token expires, the plugin shows the error in the chat and points you to re-run `claude` in a terminal.
- **JCEF support** in your IDE (default in IntelliJ 2024.1+). The plugin shows a fallback message if JCEF is unavailable.

---

## How it works (high level)

```
┌──────────────────────────────────────────────────────────────────────┐
│  IntelliJ Tool Window                                                │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │  ClaudeChatPanel (one per tab)                                 │  │
│  │  ┌──────────────────────────────────────────────────────────┐  │  │
│  │  │  JCEF webview (HTML/CSS/JS chat UI)                      │  │  │
│  │  │  ←  events via WebviewBridge (JS ↔ Java)  →              │  │  │
│  │  └──────────────────────────────────────────────────────────┘  │  │
│  │   ↑                       ↓                                    │  │
│  │   │  ConversationModel    │  ClaudeCliManager                  │  │
│  │   │  (one per panel)      │  (one CLI subprocess per panel)    │  │
│  │   └──────── NDJSON ───────┘                                    │  │
│  └──────────────────────┬─────────────────────────────────────────┘  │
└──────────────────────────┼─────────────────────────────────────────  │
                          ↓ stdin/stdout (stream-json)
                ┌─────────────────────┐
                │  claude (Node CLI)  │
                └─────────────────────┘
```

- Each tab is fully isolated — separate CLI subprocess, separate `ConversationModel`, separate session ID.
- Conversation transcripts are owned by the CLI (`~/.claude/projects/.../<session>.jsonl`); the plugin caches lightweight metadata in `~/.claude/sessions/<session>.json` (summary, working directory, last-active time).
- Switching mode/effort, or stopping a query mid-stream, restarts the CLI with `--resume <sessionId>` so the new flag takes effect without losing context.

---

## Repository layout
```
claude-intelij-plugin/
└── claude-intellij-plugin/                  # the plugin itself
    ├── build.gradle.kts
    ├── gradle.properties
    ├── plugin.xml
    ├── README.md                            # ← you are here
    ├── EFFORT_IMPLEMENTATION.md             # porting guide for effort flag
    ├── STOP_BUTTON_SPEC.md                  # send/stop toggle + resume spec
    ├── claude-intellij-plugin-YYYYMMDDHHMM.zip   # latest installable ZIPs
    └── src/main/
        ├── java/com/anthropic/claude/intellij/
        │   ├── cli/                         # ClaudeCliManager, NdjsonProtocolHandler, CliMessage, CliProcessConfig
        │   ├── model/                       # ConversationModel, MessageBlock (TextSegment, ImageSegment, ToolCallSegment, ToolResultSegment), SessionInfo, UsageInfo
        │   ├── service/                     # ClaudeApplicationService (DIAG flag), ClaudeProjectService
        │   ├── session/                     # ClaudeSessionManager, SessionStore, JsonlSessionScanner
        │   ├── settings/                    # ClaudeSettings + Configurable, secure API-key store
        │   ├── ui/                          # ClaudeChatPanel, ClaudeToolWindowFactory, AttachmentManager, dialogs/
        │   ├── handlers/                    # SlashCommandHandler
        │   ├── diff/                        # CheckpointManager, EditDecisionManager, DiffViewerDialog
        │   ├── statusbar/                   # ClaudeStatusBarFactory + Widget
        │   └── actions/                     # right-click "Send Selection / Explain / Review / Refactor / Analyze File" + main-menu entries
        └── resources/
            ├── META-INF/plugin.xml
            ├── icons/                       # claude_13.svg, claude_16.svg (light + dark)
            └── webview/                     # HTML/CSS/JS for the chat UI
                ├── index.html
                ├── css/chat.css
                └── js/{app.js, bridge.js, highlight.js}
```

---

## Settings (`File → Settings → Tools → Claude Code`)

| Setting | What it does |
|---|---|
| CLI Path | Override the auto-detected `claude` binary path. |
| Selected Model | `default` / `claude-sonnet-4-6` / `claude-opus-4-6` / `claude-haiku-4-5` (and aliases `sonnet` / `opus` / `haiku`). Custom values are remembered. |
| Permission Mode | Default mode for new sessions: `default` / `acceptEdits` / `plan` / `bypassPermissions`. |
| Auto-save files after Claude edits them | Trigger IntelliJ auto-save once a tool finishes. |
| Use Ctrl+Enter to send | Otherwise, plain Enter sends and Shift+Enter inserts a newline. |
| Respect .gitignore when searching files | Used by `@`-mention and the file picker. |
| Show cost in status bar | Adds the `$x.xx` cumulative cost field. |
| Show streaming output in real time | Disables to receive full responses in one render (faster on slow machines). |
| Auto-save dirty editors before tool execution | Saves modified files before Claude runs `Edit` / `Write` / `Bash`. |
| Enable diagnostic logging | Emits verbose `[DIAG-*]` entries to the IDE log (use only when investigating bugs). |

---

## Slash commands inside the chat

Local commands handled by the plugin (do not reach the CLI):

| Command | Action |
|---|---|
| `/clear` | Clears the current conversation. |
| `/history` | Opens the Session History dialog. |
| `/rules`, `/mcp`, `/hooks`, `/memory`, `/skills` | Open the matching configuration dialog. |
| `/model <id>` | Switches the model and persists custom values. |
| `/compact` | Asks Claude to summarise the current thread. |

Anything else is forwarded to the CLI verbatim (e.g. `/plan`, `/review`).

---

## Keyboard shortcuts

| Shortcut | Where | Action |
|---|---|---|
| `Enter` | Input | Send (or insert newline if `Ctrl+Enter to send` is enabled). |
| `Shift+Enter` | Input | Insert a newline. |
| `Esc` | Input | Stop generation / clear input. |
| `Shift+Tab` | Input | Cycle permission mode. |
| `Ctrl/Cmd+V` | Input | Paste text or image from clipboard (handled at the Swing layer to bypass IntelliJ's tool-window key absorption). |
| `Double Shift` | Anywhere | IntelliJ Search Everywhere — type "Claude" to find the tool window. |

---

## Where things get stored

| Path | What |
|---|---|
| `~/.claude/projects/<encoded-cwd>/<session-id>.jsonl` | Conversation transcripts (owned by the CLI — same files VS Code / Eclipse read). |
| `~/.claude/sessions/<session-id>.json` | Plugin-side metadata (summary, model, last-active, message count). |
| `~/.claude/.credentials.json` | OAuth tokens (managed by the CLI; the plugin never writes to this file). |
| `~/.claude.json` | User-scoped MCP server config (`mcpServers` field). The plugin reads/writes this and merges with project-scoped `.mcp.json`. |
| IntelliJ settings (`ClaudeCodeSettings.xml`) | UI prefs: open tab session IDs, mode, effort level, custom models, diag flag. |
| `.idea/` of the project | Per-project IntelliJ memento (used as a fallback when the global settings are wiped). |

---

## Related projects

- **[claude-eclipse-plugin](https://github.com/zvirot1/claude-eclipse-plugin)** — sister plugin for Eclipse / SWT.
- **[anthropics/claude-code](https://github.com/anthropics/claude-code)** — the CLI this plugin drives.
- The official VS Code extension (`anthropic.claude-code`) — used as the reference UX target.

---

## License

This is an **unofficial** community plugin. It is provided as-is, with no warranty or affiliation with Anthropic.
