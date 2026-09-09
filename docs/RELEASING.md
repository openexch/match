# Releasing the Open Exchange stack

The stack is five repos, versioned **together as one product** under a single tag (e.g. `v0.4.0-beta`
= match + oms + admin-gateway + trading-ui + assets all at that version). One version answers "what is Open
Exchange running?".

## Repos (GitHub → local dir)

| GitHub repo | Local dir | Role |
|---|---|---|
| `openexch/match` | `match/` | matching engine cluster (anchor repo) |
| `openexch/oms` | `order-management/` | order management service (dir ≠ repo; use `oms` for `gh`) |
| `openexch/admin` | `admin/` | Go process manager / ops API |
| `openexch/trading-ui` | `trading-ui/` | trading web UI (Cloudflare Pages, deploys from `main`) |
| `openexch/assets` | `assets/` | Assets Engine — deterministic money ledger (joined the train at v0.4.0-beta) |

The top-level `openexchange/` is **not** a git repo; each child is.

## Merge requirements

Use a feature branch and a pull request for contributions. Check the current
repository rules before merging (`gh api repos/openexch/<repo>/rules/branches/main`).
As of 2026-09-10, commit signing is optional on `match`, `admin`, `oms`, and `tools`.
Signed and unsigned contributions are welcome; a signing-agent outage does not
require rewriting the contribution's history.

Keep each PR focused on one logical change and squash-merge it. Review, tests,
and applicable performance checks remain part of acceptance. Removing the
commit-signature requirement does not change the other repository rules or CI
and publication workflows. Release tagging conventions below are unchanged.

## Release conventions

- **Tag-only releases.** A release = an **annotated git tag** + a **GitHub prerelease**. We do **not**
  bump a version string in `pom.xml` / `package.json` / `go.mod` — the tag is the version.
- **Annotated tag message:** `vX.Y.Z-<stage> — <one-line summary>`.
- **All alphas and betas are prereleases.**

## Procedure (per repo)

```sh
cd <local-dir>
git fetch origin

# 1. Land the work on main via a PR (direct push is rejected by the ruleset).
git push -u origin <feature-branch>
gh pr create -R openexch/<repo> --base main --head <feature-branch> --title "..." --body "..."
# Merge after the review and required checks pass. Keep PRs to one logical change each.
gh pr merge <n> -R openexch/<repo> --squash

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
