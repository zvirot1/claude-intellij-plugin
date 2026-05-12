# TRANSFER_SESSION — handing off the project to another machine

This document is written so a **Claude Code session on the receiving
machine can execute it directly**. Tell that Claude:

> "Read `TRANSFER_SESSION.md` and walk me through it."

It will run the steps for you.

---

## TL;DR

Two paths:

- **Path A (recommended — fast, clean):** clone the repo, read
  `CONTEXT.md` + `HANDOFF.md`, start a fresh Claude session. No 85 MB
  transcript transfer needed.
- **Path B (only if you must):** physically copy the previous Claude
  session JSONL to the new machine and `claude --resume` it.

Path A covers ~99% of cases. Path B is for byte-perfect continuity.

---

## Path A — fresh session with full context

### A1. Clone the code

```bash
# Pick a parent directory. The exact path does NOT matter for Path A —
# only Path B requires the encoded-path match.
mkdir -p /c/dev/claudecode && cd /c/dev/claudecode
git clone https://vstsleumi.visualstudio.com/AI-helper-extensions/_git/claude-intellij-plugin
cd claude-intellij-plugin
git checkout dev   # default branch
```

Authentication: the receiver needs a Personal Access Token (PAT) for
`vstsleumi.visualstudio.com` with scope `Code (Read & Write)`. Windows
Credential Manager will prompt on the first `git clone` if
`credential.helper=manager` is set:
```bash
git config --global credential.helper manager
```

### A2. Verify tooling

The receiver needs:

- **JDK 17 or 21** in `PATH`: `java -version`, `javac -version`.
- **Claude CLI** authenticated: `claude --version` should print without
  prompting.
- **Git-bash** (for the helper scripts).
- A working IntelliJ IDEA Community or Ultimate (2024.1.x – 2026.x).

No separate Gradle install needed — the wrapper is bundled.

### A3. Sanity-build

```bash
./gradlew buildPlugin
```

Expected output ends with:
```
> Task :buildPlugin
BUILD SUCCESSFUL in <N>s
```
and produces `build/distributions/claude-intellij-1.0.0.zip`.

If you want to test in a sandbox IntelliJ:
```bash
./gradlew runIde
```
(Exit code 2 when you close the sandbox is normal — that's just the user
closing the window, not a failure.)

### A4. Load context into a fresh Claude session

In the cloned repo, run `claude`. As the first message:

> Read `HANDOFF.md` and `CONTEXT.md`. After reading them, summarise what
> the project is, what was just done, and what's pending. Then wait for
> instructions.

The two files together give Claude:
- Repo layout, branch model, build, install (`HANDOFF.md`).
- Full session timeline, recent design decisions, architecture, open
  task list (`CONTEXT.md`).

### A5. (Optional) Cut a release-candidate to verify the pipeline

```bash
./scripts/cut-release-branch.sh
```

Pushes a fresh `release/v1.0.0-<UTC-ts>` to azuredevops with a built ZIP
under `releases/`. If this succeeds, your PAT + remote + branch model
are all healthy. The script prints the tester URL at the end.

### A6. Done

The receiver is now productive. Daily workflow:
- Code on `dev`.
- `./scripts/cut-release-branch.sh` when a tester needs a build.
- PR `release/v… → main` in Azure DevOps UI.
- `./scripts/tag-release.sh v1.0.0-<ts>` after the merge.

---

## Path B — physically resume the previous session

Only do this if you need byte-perfect continuation (the new Claude
session should see literally every previous message). Be aware:

- The session JSONL is **~85 MB**.
- Compressed (`7z`/`zip`) it is **~15–25 MB**.
- Claude Code encodes the **absolute project path** into the session
  directory name. The receiving machine must store the session under
  the matching encoded path.

### B1. Identify the files on the sending machine

```
%USERPROFILE%\.claude\projects\C--dev-claudecode-claude-intelij-plugin\
├── 2d529d76-7ae4-4246-a39a-d836e1599f29.jsonl   ← main session (~85 MB)
├── 2d529d76-7ae4-4246-a39a-d836e1599f29\        ← subagent + tool-result side-files
└── (other older session files in the same project)
```

### B2. Compress and transfer

```bash
# Sending machine. Substitute <user> for your username.
cd /c/Users/<user>/.claude/projects/
7z a -mx=5 claude-session-intellij.7z C--dev-claudecode-claude-intelij-plugin/
```

Expected compressed size: ~15–25 MB. Move via OneDrive / network share /
Azure Artifacts. **Do not commit it to the repo** — that's why we ship it
out-of-band.

### B3. Place on the receiving machine

```bash
# Receiving machine. Substitute <user> for the receiver's username.
cd /c/Users/<user>/.claude/projects/
7z x claude-session-intellij.7z
```

You should now have
`/c/Users/<user>/.claude/projects/C--dev-claudecode-claude-intelij-plugin/...`.

### B4. Make the project path match

Claude Code expects the project directory at the absolute path **encoded
in** the session folder name. Decode by reversing `--` → `\`:
```
C--dev-claudecode-claude-intelij-plugin   →   C:\dev\claudecode\claude-intelij-plugin
```

> NB: the typo `intelij` (single `l`) is intentional and historical —
> the receiver MUST match it exactly.

So the receiver must clone the code to **exactly** that path:
```bash
mkdir -p /c/dev/claudecode && cd /c/dev/claudecode
git clone https://vstsleumi.visualstudio.com/AI-helper-extensions/_git/claude-intellij-plugin claude-intelij-plugin
```

(Note: the directory name `claude-intelij-plugin` differs from the repo
name `claude-intellij-plugin` — match the *directory* name to the encoded
session, regardless of what the repo is named in Azure DevOps.)

If the receiver wants the code under a different path, they need to
rename the `~/.claude/projects/C--...` directory accordingly — encode the
new absolute path by replacing path separators with `--`.

### B5. Resume

```bash
cd /c/dev/claudecode/claude-intelij-plugin/claude-intellij-plugin
claude --resume 2d529d76-7ae4-4246-a39a-d836e1599f29
```

Or run `claude --resume` with no ID and pick from the list — the
transferred session should appear with its summary.

### B6. Sanity check

After Claude loads, ask:

> "What were the last 3 things we did in this session?"

The response should mention:
- VSTS migration + dev/release/main workflow setup.
- Backport from Eclipse 096bfc8 (JSONL CLI 2.1.107 format + Session
  History performance).
- Tab title strategy setting + `claude -p` from `user.home` + stdin.

---

## Why not commit the JSONL into VSTS?

We considered it. It is a bad idea because:

- 85 MB single file → enormous repo bloat permanently.
- The JSONL grows with every interaction; keeping it in git means
  re-pushing huge deltas constantly.
- The JSONL contains the entire raw transcript including file contents
  the model has read and any secrets that were typed in early. Git
  history makes that permanent.
- The textual summary in `CONTEXT.md` is what's actually useful for a
  successor; the raw transcript almost never is.

If you want a persistent backup of the JSONL, upload the `.7z` once to
**Azure Artifacts as a Universal Package** (separate from the source
repo) — versioned, access-controlled, doesn't pollute clones.

---

## Common issues

### "claude --resume: session not found"
Most likely the encoded-path doesn't match. Check both:
- `~/.claude/projects/` directory name (decoded path).
- Your current working directory's actual absolute path.

If they don't match, either move the project directory or rename the
session directory under `.claude/projects/`.

### "git push: authentication failed"
Re-issue the PAT in Azure DevOps:
`https://vstsleumi.visualstudio.com/_usersSettings/tokens` →
make sure scope is **Code (Read & Write)**. Then run the failing `git`
command — Credential Manager prompts and stores the new token.

### "gradle: BUILD FAILED — Could not resolve com.jetbrains.intellij.platform:gradle-plugin"
Likely a JDK mismatch. The plugin's Gradle setup needs JDK 17 or 21.
Check `JAVA_HOME` and `gradle.properties` (look for `org.gradle.java.home`).

### Hebrew text in CLI argv prints as garbage
Known Windows hazard. We piped the title-gen prompt through stdin in
UTF-8 to avoid this in `kickoffSelfGeneratedTitle`. If you add new CLI
invocations carrying Hebrew, **do not** pass them as argv on Windows.
