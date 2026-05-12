# Build & publish scripts

| Script | When to run | What it does |
|--------|------------|--------------|
| `push-preview.sh` | Anytime you want a tester to grab a fresh ZIP from the same URL | Builds, force-pushes an orphan `preview` branch with `claude-intellij-plugin-preview.zip` |
| `push-release.sh` | After a PR `dev → main` has been merged in Azure DevOps | Builds, drops the ZIP into `releases/v1.0.0-<UTC-ts>.zip`, tags it, pushes commit + tag to `main` |

Both run from the repo root, both assume an `azuredevops` remote pointing at
`https://vstsleumi.visualstudio.com/AI-helper-extensions/_git/claude-intellij-plugin`.

## Tester URL (constant across builds)
```
https://vstsleumi.visualstudio.com/AI-helper-extensions/_apis/git/repositories/claude-intellij-plugin/items?path=/claude-intellij-plugin-preview.zip&versionDescriptor.version=preview&versionDescriptor.versionType=branch&download=true
```

## Branch model

| Branch | Default | Tracks | Holds |
|--------|---------|--------|-------|
| `dev`  | ✅      | source code | no ZIPs |
| `preview` |       | orphan, force-push | single `claude-intellij-plugin-preview.zip` |
| `main` |         | merge target via PR | `releases/<version>.zip` |
