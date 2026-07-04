# Contributing to match

Thanks for your interest in Open Exchange. `match` is the matching engine
cluster: a Raft-replicated (Aeron Cluster) deterministic matching core with
market-data and order gateways. It is the anchor repo of the four-repo stack
(`match`, `oms`, `admin-gateway`, `trading-ui`).

## Before you start

- For anything larger than a small fix, open an issue first and outline the
  approach. The matching core is deterministic, replicated state machine code;
  design constraints below apply to every change inside it.
- Bug reports: include the engine impl (`array` or `direct`), cluster size,
  and reproduction steps or a failing test.

## Development setup

- Java 21 (the project is built and tested on Temurin/Zulu 21).
- Maven 3.9+. Multi-module build: `match-common`, `match-cluster`,
  `match-gateway`, `match-loadtest`.

```bash
mvn -B clean verify
```

CI runs the same command, plus Trivy dependency and secret scanning. All of
it must pass before merge.

## Design constraints (the non-negotiables)

- **Determinism.** The clustered service must produce byte-identical state
  from the same input log on every node. No wall-clock reads, no randomness,
  no iteration over unordered collections inside the replicated path. The
  determinism corpus in `match-cluster` guards this; run it, and extend it
  when you add engine behavior.
- **Zero allocation on the hot path.** The matching path is allocation-free
  in steady state (see `docs/hot-path-allocations.md`). New code on the
  order-processing path must not allocate per event.
- **Performance claims need numbers.** For latency/throughput-sensitive
  changes, run `./run-load-test.sh` and include before/after results in the
  PR description.

## Pull requests

- **One logical change per PR.** History is linear and granular: each PR is
  squash-merged into exactly one commit on `main`.
- **Sign your commits.** `main` requires signed commits; unsigned PR heads
  cannot be merged. Rebase merges are not available on this repo (GitHub
  cannot sign them), so PRs are squash-merged.
- Commit/PR title style: `type: imperative summary` with types
  `feat|fix|docs|test|ci|chore|perf`, e.g.
  `fix: fail-fast on the archive-replay error hot-loop`.
- Reference issues in the body (`Closes #NN`).

See `docs/RELEASING.md` for how releases are cut.

## License

Apache-2.0. By contributing, you agree that your contributions are licensed
under the same terms as the project (inbound = outbound). No CLA.
