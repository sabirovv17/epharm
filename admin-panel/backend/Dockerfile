# ── Backend (Kotlin + Spring Boot) — multi-stage prod-образ ──────────────────
# Сборка исполняемого jar внутри образа (не нужен локальный JDK), затем лёгкий JRE-runtime.
# Контекст сборки: admin-panel/backend (см. docker-compose.prod.yml).

FROM eclipse-temurin:22-jdk AS build
ARG RELEASE_ID=dev
ARG RELEASE_COMMIT=unknown
WORKDIR /app
# Сначала только gradle-метаданные — слой кэша зависимостей переживает правки кода.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
# Затем исходники и сборка boot-jar (тесты гоняет CI, здесь -x test для скорости).
COPY src ./src
RUN chmod +x gradlew && ./gradlew --no-daemon clean bootJar verifyMerchandisingArtifact -x test

FROM eclipse-temurin:22-jre AS runtime
ARG RELEASE_ID=dev
ARG RELEASE_COMMIT=unknown
ARG UBUNTU_MIRROR=default
LABEL org.opencontainers.image.title="Epharm backend" \
      org.opencontainers.image.version="$RELEASE_ID" \
      org.opencontainers.image.revision="$RELEASE_COMMIT"
# curl нужен для HEALTHCHECK (в JRE-образе его нет).
RUN case "$UBUNTU_MIRROR" in \
      default) ;; \
      azure) \
        for source in /etc/apt/sources.list /etc/apt/sources.list.d/ubuntu.sources; do \
          if [ -f "$source" ]; then \
            sed -i -E 's#https?://(archive[.]ubuntu[.]com|security[.]ubuntu[.]com)/ubuntu#https://azure.archive.ubuntu.com/ubuntu#g' "$source"; \
          fi; \
        done ;; \
      *) echo "Unsupported Ubuntu mirror" >&2; exit 1 ;; \
    esac \
    && apt-get -o Acquire::Retries=3 -o APT::Update::Error-Mode=any update \
    && apt-get -o Acquire::Retries=3 install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
ENV RELEASE_ID="$RELEASE_ID" \
    RELEASE_COMMIT="$RELEASE_COMMIT"
COPY --from=build /app/build/libs/app.jar app.jar
EXPOSE 8080
# Старт может занять время (Flyway-миграции) → start-period.
HEALTHCHECK --interval=15s --timeout=5s --retries=10 --start-period=90s \
    CMD curl -fsS http://localhost:8080/api/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
