#!/usr/bin/env bash
# cut-release-branch.sh — Cut a release-candidate branch from the current
# dev HEAD and push it to VSTS. Mirrors the VS plugin's workflow:
#
#   dev  ────►  release/v1.0.0-<UTC-ts>  ───PR───►  main (accumulates VSIX)
#  (code only)   (snapshot: code + releases/v…zip)   (code + every ZIP ever)
#
# Usage:
#   ./scripts/cut-release-branch.sh
#
# The script:
#   1. Builds the plugin from the current dev HEAD.
#   2. Creates a new branch `release/v1.0.0-<UTC-ts>` from that commit.
#   3. Adds the freshly-built ZIP as `releases/v1.0.0-<UTC-ts>.zip` on the
#      release branch (same path main uses, so the PR merge is trivial).
#   4. Pushes the release branch to azuredevops.
#   5. Returns to dev and discards the local release branch (you can always
#      recreate it from the remote with `git fetch && git checkout release/…`).
#
# Tester URL for this release:
#   https://vstsleumi.visualstudio.com/AI-helper-extensions/_apis/git/repositories/claude-intellij-plugin/items?path=/releases/v1.0.0-<UTC-ts>.zip&versionDescriptor.version=release/v1.0.0-<UTC-ts>&versionDescriptor.versionType=branch&download=true
#
# After tester approval: open a PR `release/v1.0.0-<UTC-ts> → main` in
# Azure DevOps. Merging adds the ZIP to main's `releases/` permanently and
# the tag `v1.0.0-<UTC-ts>` should be created on main (push-release.sh
# helper for that step).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

REMOTE="azuredevops"
BUILT_ZIP="build/distributions/claude-intellij-1.0.0.zip"
AUTHOR_EMAIL="${GIT_AUTHOR_EMAIL:-zvirot+claude@gmail.com}"
AUTHOR_NAME="${GIT_AUTHOR_NAME:-zvirot1}"

TS="$(date -u +%Y%m%d%H%M)"
RELEASE_TAG="v1.0.0-${TS}"
RELEASE_BRANCH="release/${RELEASE_TAG}"
# ZIP filename mirrors the legacy GitHub convention so installers/scripts
# that grep for "claude-intellij-plugin-*.zip" keep working.
TARGET="releases/claude-intellij-plugin-${TS}.zip"

echo "==> Pre-flight: working tree must be clean"
if [[ -n "$(git status --porcelain)" ]]; then
    echo "    ✗ uncommitted changes — commit/stash first" >&2
    git status --short
    exit 1
fi

ORIGINAL_BRANCH="$(git symbolic-ref --short HEAD)"
if [[ "$ORIGINAL_BRANCH" != "dev" ]]; then
    echo "    ⚠ not on dev (on $ORIGINAL_BRANCH). Continuing anyway." >&2
fi

echo "==> Kill stale java/gradle processes"
taskkill //F //IM java.exe >/dev/null 2>&1 || true

echo "==> Build plugin from $ORIGINAL_BRANCH @ $(git rev-parse --short HEAD)"
./gradlew buildPlugin

if [[ ! -f "$BUILT_ZIP" ]]; then
    echo "    ✗ build did not produce $BUILT_ZIP" >&2
    exit 1
fi
SIZE="$(stat -c%s "$BUILT_ZIP")"
echo "    ✓ built $BUILT_ZIP ($SIZE bytes)"

echo "==> Stash ZIP outside work tree"
STASH="$(mktemp -d)/release.zip"
cp "$BUILT_ZIP" "$STASH"

echo "==> Create release branch $RELEASE_BRANCH"
git branch -D "$RELEASE_BRANCH" 2>/dev/null || true
git checkout -b "$RELEASE_BRANCH"

echo "==> Add $TARGET"
mkdir -p releases
cp "$STASH" "$TARGET"
# Force-add — .gitignore excludes claude-intellij-plugin-*.zip but not under releases/.
git add -f "$TARGET"

git -c user.email="$AUTHOR_EMAIL" -c user.name="$AUTHOR_NAME" \
    commit -m "Release candidate ${RELEASE_TAG}"

echo "==> Push $RELEASE_BRANCH to $REMOTE"
git push "$REMOTE" "$RELEASE_BRANCH"

echo "==> Return to $ORIGINAL_BRANCH and drop local release branch"
git checkout "$ORIGINAL_BRANCH"
git branch -D "$RELEASE_BRANCH" 2>/dev/null || true
rm -f "$STASH"

echo ""
echo "✅ Release candidate $RELEASE_TAG published."
echo ""
echo "   Tester direct URL:"
echo "   https://vstsleumi.visualstudio.com/AI-helper-extensions/_apis/git/repositories/claude-intellij-plugin/items?path=/${TARGET}&versionDescriptor.version=${RELEASE_BRANCH}&versionDescriptor.versionType=branch&download=true"
echo ""
echo "   Once approved, open PR in Azure DevOps:"
echo "   https://vstsleumi.visualstudio.com/AI-helper-extensions/_git/claude-intellij-plugin/pullrequestcreate?sourceRef=${RELEASE_BRANCH}&targetRef=main"
echo ""
echo "   After merge, tag the merge commit on main with: $RELEASE_TAG"
