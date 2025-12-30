# --- Stage 1: Builder ---
# Use Maven and JDK base image
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /app

# Copy pom.xml first for dependency caching
COPY pom.xml .
RUN mvn dependency:go-offline

# Copy source code
COPY src ./src

# Build the application
RUN mvn package -DskipTests

# --- Stage 2: Runtime ---
# Use smaller JRE-only image
FROM azul/zulu-openjdk-debian:21-jre

SHELL [ "/bin/bash", "-o", "pipefail", "-c" ]

# Create user and directories
RUN groupadd -r aeron && useradd --no-log-init -r -g aeron aeron
RUN mkdir -p /home/aeron/jar && chown -R aeron:aeron /home/aeron

COPY --chown=aeron:aeron --chmod=755 setup-docker.sh /home/aeron/dockerbuild/setup-docker.sh
RUN /home/aeron/dockerbuild/setup-docker.sh && rm --recursive --force "/home/aeron/dockerbuild"

# Copy the compiled JAR from builder stage
COPY --from=builder /app/target/cluster-engine-1.0.jar /home/aeron/jar/cluster.jar

# Copy backup entrypoint script
COPY --chown=aeron:aeron --chmod=755 entrypoint-backup.sh /home/aeron/jar/entrypoint-backup.sh

WORKDIR /home/aeron/jar/
USER aeron

ENTRYPOINT ["/home/aeron/jar/entrypoint-backup.sh"]
