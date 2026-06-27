# Releasing the Open Exchange stack

The stack is four repos, versioned **together as one product** under a single tag (e.g. `v0.2.0-alpha`
= match + oms + admin-gateway + trading-ui all at that version). One version answers "what is Open
Exchange running?".

## Repos (GitHub → local dir)

| GitHub repo | Local dir | Role |
|---|---|---|
| `openexch/match` | `match/` | matching engine cluster (anchor repo) |
| `openexch/oms` | `order-management/` | order management service (dir ≠ repo; use `oms` for `gh`) |
| `openexch/admin-gateway` | `admin-gateway/` | Go process manager / ops API |
| `openexch/trading-ui` | `trading-ui/` | trading + admin web UI (Cloudflare Pages, deploys from `main`) |

The top-level `openexchange/` is **not** a git repo; each child is.

## `main` is protected — everything lands via a PR

All four `main` branches are protected by rulesets; **you cannot push to `main` directly (or FF-push)**.
Push a feature branch, open a PR, and merge the PR. Gates differ per repo
(`gh api repos/openexch/<repo>/rules/branches/main`):

| Repo | PR | Linear history | Signed commits | Copilot review |
|---|---|---|---|---|
| match | ✅ | ✅ | ✅ | — |
| admin-gateway | ✅ | ✅ | ✅ | ✅ |
| oms | ✅ | — | ✅ | ✅ |
| trading-ui | ✅ | — | — | — |

**Signed + linear interaction (match, admin-gateway).** GitHub signs the *squash* / *merge* commits it
creates, but it **cannot auto-sign a rebase merge** (rebase replays your commits). So:

- To **keep granular linear history** (multiple distinct commits), **sign your commits** (1Password SSH
  agent) and **rebase-merge** the PR. This is how v0.2.0-alpha's 4 commits landed on match `main`.
- If your commits are **unsigned**, **squash-merge** — GitHub signs the single squashed commit. This
  collapses a multi-commit PR to one (fine for a single-commit PR).

## Release conventions

- **Tag-only releases.** A release = an **annotated git tag** + a **GitHub prerelease**. We do **not**
  bump a version string in `pom.xml` / `package.json` / `go.mod` — the tag is the version.
- **Annotated tag message:** `vX.Y.Z-alpha — <one-line summary>`.
- **All alphas are prereleases.**

## Procedure (per repo)

```sh
cd <local-dir>
git fetch origin

# 1. Land the work on main via a PR (direct push is rejected by the ruleset).
git push -u origin <feature-branch>
gh pr create -R openexch/<repo> --base main --head <feature-branch> --title "..." --body "..."
# Merge: rebase if your commits are signed (keeps granular history); else squash (GitHub signs the
# result). On oms / admin-gateway the Copilot review must pass first.
gh pr merge <n> -R openexch/<repo> --rebase     # signed commits  -> linear + granular
#   or: gh pr merge <n> -R openexch/<repo> --squash   # unsigned   -> GitHub signs one commit

# 2. Tag the TRUE main tip AFTER the merge (verify HEAD == origin/main; ff local if behind).
git switch main && git pull --ff-only
git tag -a vX.Y.Z-alpha -m "vX.Y.Z-alpha — <summary>" HEAD
git push origin vX.Y.Z-alpha          # tag pushes are NOT blocked by the branch ruleset

# 3. Cut the GitHub prerelease with tailored notes.
gh release create vX.Y.Z-alpha -R openexch/<repo> \
  --title "vX.Y.Z-alpha — <summary>" \
  --notes-file <notes>.md --prerelease --verify-tag
```

Write per-repo release notes (a short **Highlights** list of what changed since the prior tag —
`git log --oneline <prevTag>..HEAD`). Verify the whole stack afterwards:

```sh
for r in match oms admin-gateway trading-ui; do
  gh release view vX.Y.Z-alpha -R openexch/$r --json tagName,isPrerelease,name
done
```

## Notes / decisions

- For repos that have **never been tagged**, you can either start them at their honest-first
  `v0.1.0-alpha` or align them to the current stack version. v0.2.0-alpha chose **stack alignment**
  (admin-gateway + trading-ui jumped straight to v0.2.0-alpha). A never-referenced first tag is cheap to
  delete and re-cut if you later prefer per-repo-honest numbering.
- `article/` and `brand/` under `openexchange/` are intentionally untracked (internal-only).
