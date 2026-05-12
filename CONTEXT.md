# CONTEXT — Full-session summary

> Synthesised from the project's session JSONL
> (`~/.claude/projects/C--dev-claudecode-claude-intelij-plugin/2d529d76-7ae4-4246-a39a-d836e1599f29.jsonl`,
> ~85 MB, 6 731 entries, ~2 149 user turns, ~3 081 assistant turns).
> Period covered: **2026-04-14 → 2026-05-12** (~4 weeks of active work,
> on top of an initial scaffold from 2026-03-23).
> Working language: Hebrew, RTL throughout. Target platform: Windows.

---

## 1. Project & goal

**Repo:** `claude-intellij-plugin` — an IntelliJ Platform plugin (Kotlin DSL
+ Java + JCEF webview) that wraps the Claude Code CLI inside IntelliJ,
mirroring the official VS Code extension. Two sibling projects develop in
parallel and cross-pollinate fixes:

- `claude-eclipse-plugin` (SWT)
- `claude-vs2022-plugin` (WPF / VSIX)

**Top-level goal that ran through the whole session:** feature parity with
VS Code's official Claude Code extension, working correctly on Windows in
Hebrew, with multi-tab CLI isolation, restored sessions across restarts,
and a clean install flow. Toward the end of the period, also: migrate
hosting from GitHub to Azure DevOps (VSTS) and write a handoff package for
a second developer on another machine.

The user works partly on a corporate machine (Bank Leumi) behind an AIM
hook proxy with AWS Bedrock — several mid-period bugs trace back to that
environment.

---

## 2. Timeline of major work blocks

Roughly chronological. Each block is one or more commits — see
`git log --all --reverse` for the full sequence.

### 2.1 Initial scaffold and platform-version bumps (Apr 14)
Cloned from an early scaffold (`Initial commit 2026-03-23`). First serious
work day was Apr 14 — bumped `platformVersion` to `2025.1.2`, raised
`untilBuild` to `251.*` (later `262.*`), fought the `runIde` sandbox
through several JDK incompatibilities. Stabilised by end of day with the
chat panel rendering inside a tool window.

### 2.2 RTL input + duplicate-message fixes (Apr 14)
- "Fix RTL direction in input textarea for Hebrew/Arabic" — set
  `dir="auto"` on the textarea inside the webview and adjusted CSS for
  right-to-left flow.
- "Fix duplicate/triplicate assistant messages" — the streaming pipeline
  was sometimes re-emitting partial content as a new message. Fixed in
  `NdjsonProtocolHandler` + `ConversationModel`.

### 2.3 New-conversation-window UX (Apr 14)
- "Fix New Conversation Window to open fresh tab (matching Eclipse)" —
  earlier it replaced the current tab. Now adds a new Content to the tool
  window. Also fixed tooltip clipping in the header.

### 2.4 Per-tab CLI isolation, mode + effort selectors (Apr 16)
Major architectural change. Each tab now owns its own `ClaudeCliManager`
instance (no more singleton). Three new selectors landed:
- **Model selector** (header dropdown).
- **Mode selector** (default / acceptEdits / plan / bypassPermissions).
- **Effort selector** (low / medium / high / max) — initial implementation
  was visual-only; user explicitly asked to verify the model behaviour
  actually changes. Discovered the same lesson the Eclipse + VS Code plug
  taught us: **changing effort must restart the CLI** with
  `--effort <level> --resume <sessionId>`.

Files: `cli/ClaudeCliManager.java`, `cli/CliProcessConfig.java`,
`ui/ClaudeChatPanel.java` (`handleChangeMode`, `handleChangeEffort`).

### 2.5 Session persistence + tab persistence (Apr 16)
- `openTabSessionIds` setting added.
- `ClaudeToolWindowFactory.createToolWindowContent` reads it on tool
  window open; recreates each saved tab with `chatPanel.setResumeSessionId(sid)`.
- `Content.UserData(SESSION_ID_KEY)` carries the id; `saveOpenTabIds`
  re-serialises on add/remove.

### 2.6 VS-Code-parity session management (Apr 20)
Compared in detail against VS Code's `WebviewPanel` ↔ CLI relationship.
Removed time-based "freshness" heuristics. Persistence is now just the
list of open tab session IDs.

### 2.7 Stream-json fixes (10 commits backported from Eclipse) (Apr 28)
`FIXES-SUMMARY.md` was added. Covered: assistant_message_completed timing,
thinking-block separation, tool-use lifecycle markers, image rendering
fidelity, edge cases when the CLI re-emits the same ID, etc.

### 2.8 Inline image rendering + MCP env table (Apr 30)
- Pasted images render in user bubbles (`MessageBlock.ImageSegment` +
  `<img>` in the webview).
- MCP server dialog: env vars switched from a single comma field to a
  proper 2-column `JTable` with Add/Remove.

### 2.9 Active-file pin / Q-style chip — many iterations (May 3, ~10 commits)
Initially a toggle bar; user asked for the **Amazon Q look**: an icon
+ filename pill above the textarea inside the input frame. Several
back-and-forths on colour (orange suggested error → blue accent),
labelling ("current file" hint → keep only filename), and where the chip
sits (header → above textarea → inside input frame). Ended with: blue
filename-only pill, click-to-dismiss with per-path memory of
"don't include this file again".

### 2.10 README + Skills folder (May 4)
- Comprehensive `README.md` covering features, install, architecture,
  settings.
- "Remove 13 historical plugin ZIPs from repo root" — cleanup;
  `.gitignore` rule pinning only the newest.
- Skills dialog: configurable folder (default `~/.claude/skills/`) +
  cross-platform "Open Folder" via Desktop API → `rundll32` →
  `xdg-open` fallback chain.

### 2.11 Stop-button visibility + Reconnect (May 5, ~6 commits)
- "Show 'Thinking...' indicator until first chunk arrives" — closes the
  UX gap between Send and first streamed token.
- "Strip file-XML on JSONL replay + true Reconnect" — JSONL replay was
  showing the prepended `<file path=…>…</file>` block as visible bubble
  text; new `stripPrependedFileBlocks` static helper.
- "Auto-connect on tab open" — synthetic `connecting` state with a
  pulsing yellow dot replaces the bare "Disconnected" pill at startup.
- "Keep Stop button visible while tools are running" —
  `assistant_message_completed` only flips the button when
  `!hasRunningToolCalls()`.
- "Fix Ctrl+V duplicate paste" — Java handler now bails out when JCEF
  has focus (JCEF's native paste already runs).
- "Add visible completion footer below the final assistant bubble" —
  animated `✓ tokens · duration · cost`.
- "Clean noise prefixes out of Session History summaries (and bubbles)" —
  `cleanForSummary` strips `<file path=…>` blocks and
  `[Active editor context:…]` prefixes.

### 2.12 LLM-written tab titles (May 5)
- "Use Claude CLI's auto-generated session summary as the history title" —
  read `{"type":"summary",…}` JSONL entries (the CLI's own summary), use
  them when present.
- "Configurable tab-title strategy: self-generated topic by default" —
  new setting with 4 options (`self_generated` / `first_message` /
  `cli_summary` / `hybrid`).
- "Fix off-topic tab titles: run claude -p from user.home" — the
  self-generated path was inheriting the IDE's CWD, so `claude -p` was
  reading the surrounding project's CLAUDE.md and producing topic titles
  like "IntelliJ IDEA Transformed Cache Directory" for a Lake-Garda
  question. Fixed by setting `ProcessBuilder.directory(user.home)` and
  tightening the prompt.
- "Pipe title-gen prompt via stdin so Hebrew/Unicode survives on Windows" —
  Windows argv is encoded in the system ANSI code page (cp1252/1255),
  which mangles non-Latin text. STDIN is fed as UTF-8 and survives.

### 2.13 Resume actually switches the CLI (May 5)
A subtle but very visible bug. Earlier:
- Session History `Resume` only re-rendered messages from the chosen
  session's JSONL into the webview.
- The CLI process **stayed on the previous tab's session**, so the next
  user message was answered with the wrong topic in context.

Verified by `tail`-ing the active session's JSONL and seeing
"Lake Garda — 65m" replies arriving on what should have been a Tirol
session.

Fix in `resumeSession`:
- Always call `cliManager.stop()` (no `isRunning()` guard).
- New helper `ConversationModel.resetSessionInfo(SessionInfo)` to
  pre-seed the target id **before** start, so racing Reconnect doesn't
  revive the wrong session.
- Surface `IOException` from `start()` (was being silently swallowed).
- Reset `tabNameSet`, `resumeSessionId`, `eagerSnapshotDone`,
  `stagedEditDone`.
- Update tab title from the resumed session's stored summary; mark
  `tabNameSet = true` so the next message doesn't trigger the
  self-generated title strategy and overwrite it.

### 2.14 Backport from Eclipse 096bfc8 (May 7)
Five ported fixes:
1. **CLI 2.1.107+ JSONL format**: top-level `"type":"assistant"` no longer
   present — both replay parser and scanner fall back to `message.role`.
2. **Resume keeps loading history even if CLI start fails.**
3. **`findSessionById` helper** in `JsonlSessionScanner`.
4. **`listSessionsFast` + `fillSessionDetails`** — Session History dialog
   opens instantly even with multi-GB transcripts.
5. **`buildSessionInfo` performance**: cap at 500 lines/file, cheap text
   pre-filter (`indexOf("type":"user"|"assistant"|"summary"`) before
   JSON parse.

### 2.15 Real tab titles on startup (May 7)
`ClaudeToolWindowFactory` was reading titles from the local SessionStore
which often has no summary after `3233fd4`. Tabs were coming up as
"Chat 2", "Chat 3" and only fixing themselves on click. Now a background
pass calls `JsonlSessionScanner.findSessionById` for each nameless tab
and updates `Content.setDisplayName` on the EDT.

### 2.16 VSTS migration + dev/release/main workflow (May 12)
- New remote `azuredevops` at
  `vstsleumi.visualstudio.com/AI-helper-extensions/_git/claude-intellij-plugin`.
- Created repo via Azure DevOps API.
- `dev` set as default branch; `main` reorganised so ZIPs live in
  `releases/<filename>`.
- Dropped the orphan `preview` branch in favour of per-version
  `release/v1.0.0-<UTC-ts>` snapshot branches (same model the VS2022
  plugin uses).
- `.gitignore` strengthened: `claude-intellij-plugin-*.zip` (no
  negation) — ZIPs only ever land inside `releases/`.
- Two scripts under `scripts/`:
  - `cut-release-branch.sh` — build + push a new `release/v*` snapshot.
  - `tag-release.sh v1.0.0-<ts>` — tag a merged release commit on main.
- ZIP filename convention: `claude-intellij-plugin-<UTC-ts>.zip` (same
  as the legacy GitHub builds, for installer-script stability).

### 2.17 Handoff documents (May 12 — this batch)
- `HANDOFF.md`, `CONTEXT.md`, `TRANSFER_SESSION.md` for the new developer.

---

## 3. Architecture as it stands today

### 3.1 Process model
- One JVM (the IntelliJ instance).
- For each open Claude tab: one `ClaudeChatPanel` instance,
  one `ClaudeCliManager` instance, one `claude` subprocess.
- Each subprocess is invoked with stream-json output, optionally
  `--resume <id>`, `--model`, `--effort`, `--permission-mode`.

### 3.2 Webview ↔ Java bridge
JCEF browser inside the panel loads `webview/index.html`. JS posts
`{type, payload}` JSON via a query handler; Java dispatches on `type`
in `ClaudeChatPanel.handleWebviewMessage`. Java pushes events back via
`sendToWebview(type, jsonPayload)` which runs a tiny JS dispatcher.

### 3.3 JSONL is the source of truth
The plugin's local `SessionStore` (`~/.claude/sessions/<id>.json`) holds
only curated metadata (renames, permission mode overrides). Real
content — every message, the auto-summary, the per-turn timestamps —
lives in the CLI-written `~/.claude/projects/<encoded-cwd>/<id>.jsonl`.

The Session History dialog therefore reads from JSONL via
`JsonlSessionScanner.listSessionsFast` and lazy-fills per row.

### 3.4 Tab persistence
Three layers:
- **`Content.UserData(SESSION_ID_KEY)`** — the session id tagged onto each
  IntelliJ tab.
- **`ClaudeSettings.State.openTabSessionIds`** — the comma-separated list
  of all open tabs, persisted to `ClaudeCodeSettings.xml`.
- **JSONL on disk** — restores conversation content on Resume.

### 3.5 Tab titles
Driven by the `tabTitleStrategy` setting. Default `self_generated`:
on the first user message in a new tab, fork `claude -p` in `user.home`
with a topic-title prompt fed over stdin, then `updateTabDisplayName`
when it returns. The strategies have been tuned to handle the
Hebrew-on-Windows argv encoding hazard.

---

## 4. Known good states / live URLs (May 12)

- `dev` HEAD: latest scripts + handoff docs.
- `main` HEAD: cumulative releases under `releases/`.
- Latest release-candidate branch: `release/v1.0.0-<UTC-ts>` (see
  `git ls-remote azuredevops` for the current one).
- Latest tag: `v1.0.0-202605070849` (last tagged release on main).

---

## 5. Currently open / pending work

- **No active bugs flagged in JSONL as of 2026-05-12 13:00 UTC.** The
  most recent batches all landed green.
- **First message after Reconnect sometimes not rendered (bubble missing).**
  Confirmed via "כפי שציינתי" follow-ups indicating the model did
  answer — but the bubble never appeared. Suspected race in
  post-reconnect streaming-event delivery; flagged for separate
  investigation; the JSONL CLI 2.1.107 backport (§2.14) was the leading
  candidate fix but full verification is pending.
- The orphan-cleanup of historical GitHub ZIPs (~13 of them) was done
  on 2026-05-04; GitHub mirror is now considered stale and will not be
  pushed to from VSTS workflow.

---

## 6. Conventions and footguns

- **Working language is Hebrew, RTL.** Any user-facing string set from
  Java must survive UTF-8 round-trip; CLI argv on Windows DOES NOT
  preserve Hebrew — use stdin where possible.
- **`runIde` exit code 2** is normal when the user closes the sandbox.
  Don't treat it as a build failure.
- **JAR file locks on Windows.** `taskkill //F //IM java.exe` before
  every gradle build that follows a `runIde`.
- **JSONL files are the system-of-record.** Don't fight them — read them.

---

## 7. If you're the new developer

1. Read `HANDOFF.md` first (15 minutes).
2. Skim `README.md` (the user-facing feature list).
3. Skim this file (you are doing it).
4. Open Claude Code in the cloned repo and ask it to read
   `HANDOFF.md` + `CONTEXT.md`. It will reproduce the state you'd have
   had if you'd been in the original session.
5. Run `./gradlew runIde` to see the plugin in a sandbox IntelliJ.
6. Run `./scripts/cut-release-branch.sh` to verify the build+publish
   pipeline against your machine and your PAT.
