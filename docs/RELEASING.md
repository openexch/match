# Releasing the Open Exchange stack

The stack is four repos, versioned **together as one product** under a single tag (e.g. `v0.2.0-alpha`
= match + oms + admin-gateway + trading-ui all at that version). This is the cleanest deploy story:
one version answers "what is Open Exchange running?".

## Repos (GitHub → local dir)

| GitHub repo | Local dir | Role |
|---|---|---|
| `openexch/match` | `match/` | matching engine cluster (anchor repo) |
| `openexch/oms` | `order-management/` | order management service (dir ≠ repo; use `oms` for `gh`) |
| `openexch/admin-gateway` | `admin-gateway/` | Go process manager / ops API |
| `openexch/trading-ui` | `trading-ui/` | trading + admin web UI (Cloudflare Pages, deploys from `main`) |

The top-level `openexchange/` is **not** a git repo; each child is.

## Conventions

- **Tag-only releases.** A release = an **annotated git tag** + a **GitHub prerelease**. We do **not**
  bump a version string in `pom.xml` / `package.json` / `go.mod` — the tag is the version. (match pom
  stays `1.0-SNAPSHOT`; trading-ui `package.json` stays `1.0.0`.)
- **Annotated tag message:** `vX.Y.Z-alpha — <one-line summary>`.
- **All alphas are prereleases.**
- **Merge to `main` is a fast-forward** (`git merge --ff-only`) — no squash, no merge bubble — so history
  stays linear and granular. GitHub auto-marks the PR MERGED once its commits are on `main`.
  (admin-gateway disallows merge commits on the GitHub button; FF-push to `main` sidesteps it.)

## Procedure (per repo)

```sh
cd <local-dir>

# 1. Land the work on main as a fast-forward (preserves granular history).
git fetch origin
git merge --ff-only origin/<feature-branch>     # if not already on main
git push origin main

# 2. Tag the TRUE main tip. Verify HEAD == origin/main first (dependabot may be ahead).
git rev-parse HEAD; git rev-parse origin/main    # must match; ff local if behind
git tag -a vX.Y.Z-alpha -m "vX.Y.Z-alpha — <summary>" HEAD
git push origin vX.Y.Z-alpha

# 3. Cut the GitHub prerelease with tailored notes.
gh release create vX.Y.Z-alpha -R openexch/<repo> \
  --title "vX.Y.Z-alpha — <summary>" \
  --notes-file <notes>.md --prerelease --verify-tag
```

Write per-repo release notes (a short **Highlights** list of what actually changed since the prior tag —
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
