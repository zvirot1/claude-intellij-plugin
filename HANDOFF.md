# HANDOFF — Claude IntelliJ Plugin

Last updated: 2026-05-12

This document hands the project off to a new developer / new Claude session.
Read it once and you should be productive immediately.

---

## 1. Where the code lives

**Primary repo (Azure DevOps — VSTS):**
<https://vstsleumi.visualstudio.com/AI-helper-extensions/_git/claude-intellij-plugin>

**Mirror (read-only, may lag):** <https://github.com/zvirot1/claude-intellij-plugin>

Clone:
```bash
git clone https://vstsleumi.visualstudio.com/AI-helper-extensions/_git/claude-intellij-plugin
cd claude-intellij-plugin
git checkout dev   # default branch for active development
```

Authentication: needs a Personal Access Token (PAT) for
`vstsleumi.visualstudio.com` with `Code (Read & Write)` scope. Windows
Credential Manager will prompt on first push if `credential.helper=manager` is
set (`git config --global credential.helper manager`).

---

## 2. Branch model (mirrors the VS2022 + Eclipse plugins)

| Branch | Default | Purpose | What it holds |
|--------|---------|---------|---------------|
| **`dev`** | ✅ | Active development | Source code. **No ZIPs at all.** |
| **`release/v1.0.0-<UTC-ts>`** |   | Build candidate | Same code + a single `releases/claude-intellij-plugin-<ts>.zip` snapshot for testers. |
| **`main`** |   | Production | Code + accumulated `releases/claude-intellij-plugin-<ts>.zip` files for every released version. |

### Workflow

```
   dev  ─────►  release/v1.0.0-<UTC-ts>  ───PR───►  main
   (code only)   (snapshot + single ZIP)            (code + cumulative ZIPs)
```

1. Develop on `dev`, commit + push as usual.
2. When ready for a test build, run `./scripts/cut-release-branch.sh`
   (builds, creates `release/v1.0.0-<UTC-ts>`, pushes it).
3. Tester downloads the ZIP from the release branch's direct-download URL.
4. Once approved, open a PR `release/v1.0.0-<UTC-ts> → main` in the
   Azure DevOps web UI and merge it.
5. Run `./scripts/tag-release.sh v1.0.0-<UTC-ts>` to tag the merge commit
   on main and push the tag.

---

## 3. Where to download builds

**Latest release candidate** (per-release URL — read it from the script
output, or browse the `release/*` branches in Azure DevOps):
```
https://vstsleumi.visualstudio.com/AI-helper-extensions/_apis/git/repositories/claude-intellij-plugin/items?path=/releases/claude-intellij-plugin-<UTC-ts>.zip&versionDescriptor.version=release/v1.0.0-<UTC-ts>&versionDescriptor.versionType=branch&download=true
```

**Production / archive**: browse `main/releases/` in the web UI. Each file
is permanently downloadable.

Install in IntelliJ: **Settings → Plugins → ⚙ → Install Plugin from Disk…**
→ pick the ZIP → restart.

---

## 4. Build system

Plain Gradle, IntelliJ Platform Plugin SDK.

```bash
./gradlew buildPlugin   # produces build/distributions/claude-intellij-1.0.0.zip
./gradlew runIde        # launches a sandbox IntelliJ with the plugin loaded
```

**Pre-reqs on a fresh machine:**

- **JDK 17+** in `PATH` (`java -version`).
- **Gradle wrapper** is bundled — no separate install.
- **Claude CLI** authenticated (`claude --version` should work).
- Optional: `gh` CLI if you ever want to push to the GitHub mirror.

**Config:** `gradle.properties` sets `platformVersion=2025.1.2`,
`sinceBuild=241`, `untilBuild=262.*`.

---

## 5. Architecture pointers

Key packages under `src/main/java/com/anthropic/claude/intellij/`:

| Path | What it does |
|------|--------------|
| `ui/ClaudeChatPanel.java` | The Swing/JCEF tool-window panel — the brain. Owns the webview, the CLI manager, the conversation model, all message routing. |
| `ui/ClaudeToolWindowFactory.java` | Builds tool-window content, restores saved tabs (`openTabSessionIds`), persists tab session IDs back into settings. |
| `cli/ClaudeCliManager.java` | Per-tab `claude` subprocess: spawn, stop, restart with `--resume`, stream-json parsing pipeline. |
| `cli/CliProcessConfig.java` + `Builder` | All CLI flags (model, effort, permission mode, resume id). |
| `cli/NdjsonProtocolHandler.java` | Parses the stream-json events the CLI emits. |
| `model/ConversationModel.java` | Per-tab message store + listener fanout. |
| `model/MessageBlock.java` | A message + ordered list of segments (text, tool-use, tool-result, image). |
| `session/JsonlSessionScanner.java` | Reads `~/.claude/projects/<encoded-cwd>/<id>.jsonl`. Has `listSessionsFast()` + `fillSessionDetails()` for the Session History dialog, and full `listSessions()` for legacy callers. |
| `session/ClaudeSessionManager.java` | Local `SessionStore` wrapper. Curated summaries (renames) live here. |
| `settings/ClaudeSettings.java` | All persisted application settings. |
| `settings/ClaudeSettingsConfigurable.java` | The Settings page UI. |
| `ui/dialogs/SessionHistoryDialog.java` | The Resume picker. Lazy details + background filler. |
| `webview/` (under `resources/`) | HTML/CSS/JS for the JCEF chat. |

The webview communicates with Java through a tiny postMessage bridge:
JS posts `{type, payload}`, Java dispatches via `handleWebviewMessage` in
`ClaudeChatPanel`.

---

## 6. Recent significant fixes (May 2026)

1. **CLI 2.1.107+ JSONL format compatibility.** New CLI stopped writing
   top-level `"type":"assistant"`. Both `loadSessionHistoryFromJsonl`
   (in `ClaudeChatPanel`) and `JsonlSessionScanner.buildSessionInfo`
   fall back to `message.role` when top-level type is missing. Without
   this, replayed sessions were silently empty.
2. **Resume actually switches the CLI.** Earlier, Resume only re-rendered
   webview messages from JSONL — the underlying CLI process still held the
   previous tab's context, so the next answer came back about the wrong
   topic. Now `resumeSession()` stops the CLI unconditionally, pre-sets
   `conversationModel.sessionInfo` to the target id before start, surfaces
   `IOException` from `start()` instead of swallowing it, and continues
   history loading even when the CLI fails (so the user at least sees
   past messages).
3. **Tab title strategy setting.** New setting `tabTitleStrategy` (default
   `self_generated`) decides how new-tab titles are produced: truncated
   first message / wait for CLI auto-summary / run `claude -p` ourselves
   to get a 3-5 word topic title / hybrid. The `claude -p` call runs
   from `user.home` (not the project CWD, to avoid picking up CLAUDE.md
   context), and the prompt is piped through stdin in UTF-8 (Windows argv
   mangles Hebrew otherwise).
4. **Session History dialog is now instant.** Split into `listSessionsFast`
   (file enumeration only) + per-row background `fillSessionDetails`.
   The 500-line cap + cheap text pre-filter in `buildSessionInfo` keeps
   scans fast even with multi-GB transcripts.
5. **Real tab titles restored on startup.** `ClaudeToolWindowFactory` was
   reading titles from the local `SessionStore` which often has no summary
   for sessions created after `3233fd4`. It now falls back to
   `JsonlSessionScanner.findSessionById` in a background thread; tabs come
   up labelled "Chat N" for a fraction of a second, then snap to their
   real titles.
6. **VSTS migration + dev/release/main workflow** (this commit batch).
   The repo now lives on Azure DevOps; GitHub is a stale mirror. ZIPs no
   longer live at repo root — they are kept in `releases/` on `main` and
   on each `release/*` snapshot branch.

---

## 7. Scripts directory

| Script | When | What it does |
|--------|------|--------------|
| `scripts/cut-release-branch.sh` | Want a tester build | Builds, creates `release/v1.0.0-<UTC-ts>` with the new ZIP under `releases/`, pushes it to azuredevops, returns to dev. |
| `scripts/tag-release.sh v1.0.0-<ts>` | After PR `release/v… → main` is merged | Pulls main, verifies the asset is present, tags the merge commit, pushes the tag. |
| `scripts/README.md` | Reading | Same content as this section, expanded with copy-paste examples. |

---

## 8. Conventions

- Java source/target: **17**.
- Commit messages: 1-2 sentences explaining the **why**. Co-authored
  trailer for Claude collaborations.
- ZIPs **never** committed to `dev`. Only to `release/*` snapshots and
  `main` under `releases/`. `.gitignore` has
  `claude-intellij-plugin-*.zip` (no exceptions).
- VSIX-style "preview" branch (force-pushed orphan) was dropped in favour
  of per-version `release/*` snapshot branches — same approach the
  VS2022 plugin landed on.
- Diagnostic logging: gated by `state.diagnosticLogging` (Settings →
  Tools → Claude Code → checkbox). Use `[DIAG-…]` prefix when adding logs.

---

## 9. If you're continuing the previous Claude Code session

The session JSONL is at:
```
%USERPROFILE%\.claude\projects\C--dev-claudecode-claude-intelij-plugin\2d529d76-7ae4-4246-a39a-d836e1599f29.jsonl
```

Either:
- **Path A** (recommended): open Claude Code in the cloned repo and tell it
  *"Read `HANDOFF.md` and `CONTEXT.md`"*. That gives it the same picture
  the original session had without copying the 85 MB transcript.
- **Path B**: copy the JSONL directory to the new machine under the
  matching encoded path and run `claude --resume 2d529d76-7ae4-4246-a39a-d836e1599f29`.

Details for Path B are in [`TRANSFER_SESSION.md`](TRANSFER_SESSION.md).

---

## 10. Sibling projects (for cross-pollination)

The same author maintains two parallel ports of the same plugin:

- **`claude-eclipse-plugin`** — Eclipse SWT version. Often the source of
  backported fixes; the IntelliJ port frequently picks up Eclipse commits
  like `096bfc8`.
- **`claude-vs2022-plugin`** — Visual Studio 2022 version. The
  dev/release/main workflow here was modelled on that repo.

All three live under the same `AI-helper-extensions` Azure DevOps project.
