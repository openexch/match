# --- AŞAMA 1: Derleyici (Builder) ---
# Maven ve JDK içeren bir temel imaj kullan
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /app

# Önce sadece pom.xml'i kopyala (bağımlılık katmanını önbelleğe almak için)
COPY pom.xml .
RUN mvn dependency:go-offline

# Projenin geri kalan kaynak kodunu kopyala
COPY src ./src

# Uygulamayı derle
RUN mvn package -DskipTests

# --- AŞAMA 2: Çalıştırma (Final) ---
# Sadece Java Runtime (JRE) içeren daha küçük bir imaj kullan
FROM azul/zulu-openjdk-debian:21-jre

SHELL [ "/bin/bash", "-o", "pipefail", "-c" ]

# Gerekli kullanıcı ve dizinleri oluştur
RUN groupadd -r aeron && useradd --no-log-init -r -g aeron aeron
RUN mkdir -p /home/aeron/jar && chown -R aeron:aeron /home/aeron

COPY --chown=aeron:aeron --chmod=755 setup-docker.sh /home/aeron/dockerbuild/setup-docker.sh
RUN /home/aeron/dockerbuild/setup-docker.sh && rm --recursive --force "/home/aeron/dockerbuild"

# Derleme aşamasından sadece derlenmiş JAR dosyasını kopyala
COPY --from=builder /app/target/cluster-engine-1.0.jar /home/aeron/jar/cluster.jar

# Gerekli diğer dosyaları kopyala
COPY --chown=aeron:aeron --chmod=755 entrypoint.sh /home/aeron/jar/entrypoint.sh
# (Gerekliyse aeron-all.jar dosyasını da indir)

WORKDIR /home/aeron/jar/
USER aeron

ENTRYPOINT ["/home/aeron/jar/entrypoint.sh"]