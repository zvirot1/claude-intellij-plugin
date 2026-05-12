# Build & publish scripts

Branch model (same as the VS2022 plugin):

```
   dev  ─────►  release/v1.0.0-<UTC-ts>  ───PR───►  main
   (code only)   (snapshot: code + releases/v…zip)   (code + every release ZIP)
```

| Branch | Default | Holds |
|--------|---------|-------|
| `dev`  | ✅      | Source code. No ZIPs. |
| `release/v1.0.0-<UTC-ts>` |         | Snapshot of dev + a single new `releases/v….zip` for testers. |
| `main` |         | Code + accumulated `releases/v….zip` files across every release. |

## Scripts

| Script | When to run | What it does |
|--------|------------|--------------|
| `cut-release-branch.sh` | When you want a tester build | Builds, creates `release/v1.0.0-<UTC-ts>` from current `dev` with the ZIP under `releases/`, pushes the branch. |
| `tag-release.sh v1.0.0-…` | After the `release/v… → main` PR is merged in Azure DevOps | Pulls main, verifies the asset is present, creates an annotated tag, pushes it. |

Both run from the repo root and assume an `azuredevops` remote at
`https://vstsleumi.visualstudio.com/AI-helper-extensions/_git/claude-intellij-plugin`.

## Tester URLs

**Per-release** (URL changes each cut — share it with whoever is testing):
```
https://vstsleumi.visualstudio.com/AI-helper-extensions/_apis/git/repositories/claude-intellij-plugin/items?path=/releases/v1.0.0-<UTC-ts>.zip&versionDescriptor.version=release/v1.0.0-<UTC-ts>&versionDescriptor.versionType=branch&download=true
```

**Latest released** (after merge to main + tag):
```
https://vstsleumi.visualstudio.com/AI-helper-extensions/_apis/git/repositories/claude-intellij-plugin/items?path=/releases/v1.0.0-<UTC-ts>.zip&download=true
```

## Typical workflow

```bash
# 1. Work on dev — commit + push code as usual:
git push azuredevops dev

# 2. Cut a release candidate (creates release/v1.0.0-<ts>):
./scripts/cut-release-branch.sh
# prints the tester URL + the PR-create URL

# 3. Tester downloads, tries the build. If OK:
#    Open the PR in Azure DevOps UI, merge release/v1.0.0-<ts> → main.

# 4. Tag the merged release commit on main:
./scripts/tag-release.sh v1.0.0-<ts>
```
