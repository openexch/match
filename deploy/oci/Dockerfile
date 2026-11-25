# syntax=docker/dockerfile:1
# SPDX-License-Identifier: Apache-2.0
#
# The matching engine image: the published match-cluster.jar on a Java 21 runtime, started by
# deploy/bin/match-launch (the same launcher the systemd units run). Nothing is compiled here: the image wraps the
# published jar.
#
# Build context: a directory holding the published match-cluster.jar; deploy/ of this repo is a named context:
#
#   docker build -f deploy/oci/Dockerfile --build-context deploy=deploy -t openexchange/match <jar-dir>
#
# Run (read-only root, non-root, cluster state and ME journal on a volume; the argument is the driver mode):
#   docker run --read-only --tmpfs /tmp --shm-size 1g -v match-state:/var/lib/openexchange/match \
#       --env-file cluster.env --env-file member.env --env-file host.env --env-file secrets.env <image> embedded
# External driver: run the cluster-tools image's aeronmd as the member's driver, share its network namespace
# (--network container:<driver>) and its driver directory (a shared tmpfs volume, AERON_DIR in both), same uid,
# and pass `external`.

# Java 21 (Zulu, headless JRE), pinned by digest.
ARG JRE_IMAGE=azul/zulu-openjdk-debian:21-jre-headless-latest@sha256:3b45cab1e7092d81daede33a242871fd1110e208b760f75479c9495402feef7a
FROM ${JRE_IMAGE}
ARG OE_UID=10001
RUN groupadd --system --gid "${OE_UID}" openexchange \
    && useradd --system --uid "${OE_UID}" --gid "${OE_UID}" --no-create-home --home-dir /nonexistent \
        --shell /usr/sbin/nologin openexchange \
    && install -d -m 0755 /opt/openexchange/match/bin \
    && mkdir -p /var/lib/openexchange/match \
    && chown openexchange:openexchange /var/lib/openexchange/match
COPY --chmod=0444 match-cluster.jar /opt/openexchange/match/match-cluster.jar
COPY --from=deploy --chmod=0555 bin/match-launch /opt/openexchange/match/bin/match-launch
ENV BASE_DIR=/var/lib/openexchange/match/cluster \
    SETTLEMENT_JOURNAL_DIR=/var/lib/openexchange/match/journal
USER openexchange
VOLUME ["/var/lib/openexchange/match"]
# Stride 0 at the default base 9000: UDP 9001-9005 (archive control, client, member, log, transfer), TCP 9006 the
# node endpoint (/health, /ready, /metrics), UDP 9010 the ME journal archive control.
EXPOSE 9001-9005/udp 9006/tcp 9010/udp
# No default driver mode: match-launch refuses (78) a start that names none.
ENTRYPOINT ["/opt/openexchange/match/bin/match-launch"]
