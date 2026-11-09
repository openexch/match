# Contributing to match

`match` is the Open Exchange matching engine: a deterministic, Raft-replicated (Aeron Cluster) matching core, plus a market-data gateway and a load generator. General project information is at https://openexchange.dev/docs.

## Before you start

- For anything larger than a small fix, open an issue first and outline the approach. The matching core is replicated state machine code, and the constraints below apply to every change inside it.
- Bug reports: include the engine implementation (`array` or `direct`), the cluster size, and reproduction steps or a failing test.
- Changes that touch shared cluster machinery (lifecycle, ports, auth, snapshot cadence) belong in [cluster-kit](https://github.com/openexch/cluster-kit), not here.

## Development setup

- Java 21, Maven 3.9+, Linux.
- cluster-kit is resolved from GitHub Packages and needs a token with `read:packages`:

```bash
export GITHUB_ACTOR=<your-github-user>
export GITHUB_TOKEN=<token with read:packages>
mvn -B -s .mvn-settings.xml clean verify
```

CI runs the same build, an SBE codec drift check (`mvn -pl match-common generate-sources` must leave the generated sources unchanged), the edge relay tests (`edge/market-relay`: `npm ci && npm run typecheck && npm test`) and a Trivy dependency and secret scan. All must pass before merge.

## Design constraints

- **Determinism.** The clustered service must produce identical state from the same log on every node. No wall-clock reads, no randomness, no iteration over unordered collections in the replicated path. Node-local configuration must not change what a command does. The determinism corpus (`make determinism`) guards this; extend it when you add engine behaviour, and regenerate goldens (`make update-goldens`) only for an intentional output change.
- **Zero allocation on the hot path.** The order-processing path is allocation-free in steady state, and tests such as `ZeroAllocDurablePathTest` check it. New code on that path must not allocate per event.
- **Wire changes.** Edit the schema XML under `match-common/src/main/resources/sbe/`, regenerate, and commit the generated codecs in the same change.
- **Performance claims need numbers.** For latency or throughput sensitive changes, run `./run-load-test.sh` and put before and after results in the PR description.

## Pull requests

- One logical change per PR. Each PR is squash-merged into one commit on `main`.
- Title style: `type: imperative summary`, with `feat|fix|docs|test|ci|build|chore|perf`.
- Reference issues in the body (`Closes #NN`).
- Commit signing is optional.

## Releases

A release is an annotated git tag plus a GitHub prerelease with notes; versions are not bumped in `pom.xml`. One version spans all v1 repos. Release notes live in [GitHub Releases](https://github.com/openexch/match/releases).

## License

Apache-2.0. By contributing, you agree that your contributions are licensed under the same terms (inbound = outbound). No CLA.
