#!/usr/bin/env bash
# tag-release.sh — After a `release/v1.0.0-<ts>` → `main` PR is merged in
# Azure DevOps, tag the merge commit on `main` with the matching version.
#
# Usage:
#   ./scripts/tag-release.sh v1.0.0-202605120832
#
# The script:
#   1. Switches to main, pulls latest.
#   2. Sanity-checks that releases/<tag>.zip exists at HEAD (the merge
#      brought it in).
#   3. Creates an annotated tag at HEAD.
#   4. Pushes the tag to azuredevops.
#
# This is intentionally minimal — by the time you run it, the PR merge
# has already done the heavy lifting (added the ZIP to releases/).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

REMOTE="azuredevops"
AUTHOR_EMAIL="${GIT_AUTHOR_EMAIL:-zvirot+claude@gmail.com}"
AUTHOR_NAME="${GIT_AUTHOR_NAME:-zvirot1}"

TAG="${1:-}"
if [[ -z "$TAG" ]]; then
    echo "Usage: $0 v1.0.0-<UTC-ts>" >&2
    exit 1
fi

if [[ ! "$TAG" =~ ^v ]]; then
    echo "    ✗ tag must start with 'v' (got: $TAG)" >&2
    exit 1
fi

ASSET="releases/${TAG}.zip"

echo "==> Checkout main + fast-forward"
git checkout main
git pull --ff-only "$REMOTE" main

if [[ ! -f "$ASSET" ]]; then
    echo "    ✗ $ASSET not present on main HEAD" >&2
    echo "      Make sure the release/$TAG → main PR was merged first." >&2
    exit 1
fi

if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
    echo "    ⚠ tag $TAG already exists locally — skipping create"
else
    git -c user.email="$AUTHOR_EMAIL" -c user.name="$AUTHOR_NAME" \
        tag -a "$TAG" -m "Release $TAG"
fi

echo "==> Push tag $TAG to $REMOTE"
git push "$REMOTE" "$TAG"

echo ""
echo "✅ Tag $TAG pushed."
echo "   Asset on main: $ASSET"
echo "   Direct DL:     https://vstsleumi.visualstudio.com/AI-helper-extensions/_apis/git/repositories/claude-intellij-plugin/items?path=/${ASSET}&download=true"
