#!/usr/bin/env bash
# push-preview.sh — Build the plugin and force-push it to the `preview` branch
# on VSTS so a tester can download the always-current ZIP from a stable URL.
#
# Usage:
#   ./scripts/push-preview.sh          # from a clean working tree on dev
#
# What it does:
#   1. Kills any stale java/gradle daemons that hold the JAR open.
#   2. Builds the plugin (`./gradlew buildPlugin`).
#   3. Saves the current branch name so it can return to it.
#   4. Checks out a fresh orphan `preview` branch (force-resets local).
#   5. Removes everything from the tree.
#   6. Copies the freshly-built ZIP as `claude-intellij-plugin-preview.zip`.
#   7. Commits and force-pushes to `azuredevops/preview`.
#   8. Returns to the original branch and discards intermediate state.
#
# Tester URL (unchanged across builds):
#   https://vstsleumi.visualstudio.com/AI-helper-extensions/_apis/git/repositories/claude-intellij-plugin/items?path=/claude-intellij-plugin-preview.zip&versionDescriptor.version=preview&versionDescriptor.versionType=branch&download=true

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

REMOTE="azuredevops"
BUILT_ZIP="build/distributions/claude-intellij-1.0.0.zip"
TARGET_ZIP="claude-intellij-plugin-preview.zip"
AUTHOR_EMAIL="${GIT_AUTHOR_EMAIL:-zvirot+claude@gmail.com}"
AUTHOR_NAME="${GIT_AUTHOR_NAME:-zvirot1}"

echo "==> Pre-flight: working tree must be clean"
if [[ -n "$(git status --porcelain)" ]]; then
    echo "    ✗ uncommitted changes — commit/stash first" >&2
    git status --short
    exit 1
fi

ORIGINAL_BRANCH="$(git symbolic-ref --short HEAD)"
echo "    current branch: $ORIGINAL_BRANCH"

echo "==> Kill stale java/gradle processes (Windows file lock workaround)"
taskkill //F //IM java.exe >/dev/null 2>&1 || true

echo "==> Build plugin"
./gradlew buildPlugin

if [[ ! -f "$BUILT_ZIP" ]]; then
    echo "    ✗ build did not produce $BUILT_ZIP" >&2
    exit 1
fi
SIZE="$(stat -c%s "$BUILT_ZIP")"
echo "    ✓ built $BUILT_ZIP ($SIZE bytes)"

echo "==> Stash the ZIP outside the work tree"
STASH="$(mktemp -d)/preview.zip"
cp "$BUILT_ZIP" "$STASH"

echo "==> Create fresh orphan preview branch"
# Drop any local preview to avoid mixed state
git branch -D preview 2>/dev/null || true
git checkout --orphan preview
git rm -rf . >/dev/null 2>&1 || true
git clean -fdx >/dev/null 2>&1 || true

echo "==> Stage preview ZIP"
cp "$STASH" "$TARGET_ZIP"
git add "$TARGET_ZIP"

TS="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
SHA_DEV="$(git rev-parse --short "$ORIGINAL_BRANCH")"
git -c user.email="$AUTHOR_EMAIL" -c user.name="$AUTHOR_NAME" \
    commit -m "Preview build $TS (from $ORIGINAL_BRANCH @ $SHA_DEV)"

echo "==> Force-push preview to $REMOTE"
git push -f "$REMOTE" preview

echo "==> Return to $ORIGINAL_BRANCH"
git checkout "$ORIGINAL_BRANCH"

# Tidy: drop the throwaway preview ref so the next run starts orphan-clean
git branch -D preview 2>/dev/null || true
rm -f "$STASH"

echo ""
echo "✅ preview ZIP live at:"
echo "   https://vstsleumi.visualstudio.com/AI-helper-extensions/_apis/git/repositories/claude-intellij-plugin/items?path=/$TARGET_ZIP&versionDescriptor.version=preview&versionDescriptor.versionType=branch&download=true"
