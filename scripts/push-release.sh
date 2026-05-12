#!/usr/bin/env bash
# push-release.sh — Cut an official release on `main`.
#
# Workflow:
#   1. Build the plugin.
#   2. Copy the ZIP into `releases/v1.0.0-<UTC-timestamp>.zip` on a fresh
#      commit on `main`.
#   3. Tag the commit `v1.0.0-<UTC-timestamp>`.
#   4. Push commit + tag to azuredevops/main.
#
# Run this AFTER you've already merged dev → main via a PR in Azure DevOps.
# The script never modifies code — it only adds the release artifact.
#
# Usage:
#   ./scripts/push-release.sh                       # uses default v1.0.0-<ts>
#   ./scripts/push-release.sh v1.1.0                # explicit version prefix
#
# What it does NOT do:
#   - It does NOT merge dev → main. Use a PR in Azure DevOps for that.
#   - It does NOT touch the preview branch. Run push-preview.sh separately.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

REMOTE="azuredevops"
BUILT_ZIP="build/distributions/claude-intellij-1.0.0.zip"
AUTHOR_EMAIL="${GIT_AUTHOR_EMAIL:-zvirot+claude@gmail.com}"
AUTHOR_NAME="${GIT_AUTHOR_NAME:-zvirot1}"

VERSION_PREFIX="${1:-v1.0.0}"
TS="$(date -u +%Y%m%d%H%M)"
TAG="${VERSION_PREFIX}-${TS}"
TARGET="releases/${TAG}.zip"

echo "==> Pre-flight: must be on main, clean tree"
CUR="$(git symbolic-ref --short HEAD)"
if [[ "$CUR" != "main" ]]; then
    echo "    ✗ not on main (currently $CUR). Run a PR first, then checkout main." >&2
    exit 1
fi
if [[ -n "$(git status --porcelain)" ]]; then
    echo "    ✗ uncommitted changes — commit/stash first" >&2
    git status --short
    exit 1
fi

echo "==> Pull latest main from $REMOTE"
git pull --ff-only "$REMOTE" main

echo "==> Kill stale java/gradle processes"
taskkill //F //IM java.exe >/dev/null 2>&1 || true

echo "==> Build plugin"
./gradlew buildPlugin

if [[ ! -f "$BUILT_ZIP" ]]; then
    echo "    ✗ build did not produce $BUILT_ZIP" >&2
    exit 1
fi

mkdir -p releases
cp "$BUILT_ZIP" "$TARGET"
git add "$TARGET"

echo "==> Commit + tag $TAG"
git -c user.email="$AUTHOR_EMAIL" -c user.name="$AUTHOR_NAME" \
    commit -m "Release $TAG"
git -c user.email="$AUTHOR_EMAIL" -c user.name="$AUTHOR_NAME" \
    tag -a "$TAG" -m "Release $TAG"

echo "==> Push to $REMOTE"
git push "$REMOTE" main
git push "$REMOTE" "$TAG"

echo ""
echo "✅ Release $TAG published."
echo "   Asset:  $TARGET"
echo "   Browse: https://vstsleumi.visualstudio.com/AI-helper-extensions/_git/claude-intellij-plugin?path=/$TARGET&version=GBmain"
