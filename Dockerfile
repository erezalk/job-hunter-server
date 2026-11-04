# syntax=docker/dockerfile:1

# ---- Build: Maven wrapper + JDK (nothing needs to be installed on the host) ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /build

COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY src src

# Tests are skipped here: they need Docker (Testcontainers) and run via ./mvnw verify.
# The cache mount keeps downloaded dependencies between builds.
RUN --mount=type=cache,target=/root/.m2 \
    sh ./mvnw -B -Dmaven.test.skip=true package \
    && cp target/job-hunter-server-*.jar /build/app.jar

# ---- Runtime: JRE only, non-root ----
FROM eclipse-temurin:25-jre
RUN groupadd --system app \
    && useradd --system --gid app --home-dir /app --shell /usr/sbin/nologin app \
    && mkdir -p /app /data/cv-storage \
    && chown -R app:app /app /data
WORKDIR /app
COPY --from=build --chown=app:app /build/app.jar app.jar

# The API must listen on all container interfaces; docker-compose.yml publishes it on the host's loopback only.
# Uploaded CVs go to /data/cv-storage, which docker-compose.yml mounts as a named volume.
ENV SERVER_ADDRESS=0.0.0.0 \
    SERVER_PORT=8080 \
    CV_STORAGE_DIR=/data/cv-storage

USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
