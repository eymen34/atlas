# syntax=docker/dockerfile:1.4
#
# Atlas — production container image.
#
# Single Spring Boot jar; built in four explicit, auditable stages:
#   1. web-build  — npm-build the Vite/React frontend
#   2. api-build  — Maven-build the Spring Boot 4 fat jar (with AOT)
#   3. cds        — warm an AppCDS shared-class archive for cold-start
#   4. runtime    — minimal JRE image that runs the jar with -XX:SharedArchiveFile
#
# `# syntax=docker/dockerfile:1.4` (above) enables BuildKit features (cache
# mounts, etc.) and MUST stay on the first line. The build also requires
# BuildKit to be enabled (DOCKER_BUILDKIT=1 or `docker buildx build ...`);
# the docker-build CI job uses `docker buildx build`.

# === STAGE 1: web-build — compile the Vite frontend and emit /web/dist ===
#
# Rationale: the production artifact is a single Spring Boot jar; the
# compiled frontend bundle is copied into /api/src/main/resources/static/
# by stage 2 so Boot serves it directly. Running `npm ci` here (separate
# layer from `npm run build`) keeps the dependency-install layer cacheable
# across PRs that touch source but not package-lock.json.
FROM node:22-alpine AS web-build
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
# T-010: stage the committed OpenAPI spec into this otherwise web-only build
# context. `npm run build` -> prebuild -> codegen reads web/openapi.json; the
# tolerant scripts/copy-spec.mjs sees the canonical ../api source is absent here
# and uses this staged copy. Without this COPY, codegen ENOENTs in Docker even
# though it works locally (full repo on disk).
COPY api/src/main/resources/openapi/openapi.json ./openapi.json
RUN npm run build


# === STAGE 2: api-build — Maven build the Spring Boot fat jar with AOT ===
#
# Rationale: two-step layer-cache pattern. 2a copies ONLY pom.xml and (if
# present) /api/.mvn and runs `dependency:go-offline` so the Maven local
# repo is populated as a separate, cacheable layer that ONLY invalidates
# when pom.xml changes. 2b then copies the frontend bundle and api/src
# and packages the jar. The spring-boot-maven-plugin process-aot goal is
# OPT-IN AOT (T-009): the spring-boot-maven-plugin `process-aot` goal is
# no longer bound to prepare-package by default — it's behind the `aot`
# Maven profile because springdoc 2.6.0 + Spring Data 4.0.5 cannot
# co-exist under AOT introspection today (see the rationale block in
# api/pom.xml). Stage 2 therefore ships a jar WITHOUT AOT bean
# definitions; stage 3's AppCDS warm-up still works because AppCDS
# operates on the runtime class graph, not on AOT artifacts. Re-enable
# by appending `-P aot` to the mvn package command below when springdoc
# ships Boot-4-compatible bytecode.
#
# `api/.mvn*` uses a glob so the COPY succeeds whether or not api/.mvn
# exists; BuildKit COPY tolerates a zero-match glob as long as at least
# one source matches (api/pom.xml always does).
FROM maven:3.9-eclipse-temurin-21 AS api-build
WORKDIR /api

# 2a: warm the Maven cache as a discrete cacheable layer
COPY api/pom.xml ./pom.xml
COPY api/.mvn* ./.mvn
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp dependency:go-offline

# 2b: copy frontend bundle into static resources, then sources, then package
COPY --from=web-build /web/dist ./src/main/resources/static/
COPY api/src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -DskipTests package && \
    cp target/atlas-api-*.jar /tmp/app.jar


# === STAGE 3: cds — produce the AppCDS shared-class archive (app.jsa) ===
#
# Rationale (why AppCDS, not GraalVM native image):
#   AppCDS (Application Class Data Sharing) writes a pre-parsed, pre-linked
#   archive of the classes the JVM loads up to a chosen execution point.
#   On subsequent boots the JVM memory-maps that archive instead of
#   classloading from scratch — Atlas's target cold-start budget is
#   /ready ≤ 5s on 1 vCPU / 512 MB, and AppCDS gives us a ~200–400 ms
#   improvement with zero runtime cost.
#
#   GraalVM native image would go further but at the cost of a multi-minute
#   build, a fragile reflect-config maintenance burden across Spring,
#   Hibernate, Flyway, and HikariCP, and incompatibilities with bytecode-
#   manipulating libraries we already depend on. AppCDS keeps the standard
#   HotSpot runtime — same observability, same flight recorder, same heap
#   dumps — and pays for itself in MVP.
#
# Critical no-DB-boot constraints honored here:
#   * APP_DATABASE_STARTUP_CHECK_ENABLED=false bypasses
#     DatabaseStartupValidator (the SmartLifecycle SELECT-1 ping that would
#     otherwise abort context refresh). This toggle is documented as
#     image-build-only.
#   * SPRING_FLYWAY_ENABLED=false stops Flyway from attempting migrations.
#   * SPRING_DATASOURCE_HIKARI_INITIALIZATION_FAIL_TIMEOUT=-1 is a
#     dual-defense so Hikari does not eagerly try to validate the pool.
#   * APP_DATABASE_URL / USERNAME / PASSWORD are throwaway placeholders so
#     DataSourceConfig can construct the HikariDataSource bean lazily
#     without ever opening a socket.
#   * -Dspring.context.exit=onRefresh tells Spring to exit the JVM cleanly
#     once context refresh completes (this is what triggers the JVM to
#     flush the AppCDS archive).
#   * JWT_SECRET (T-009) is supplied as a 48-char placeholder so the
#     security autoconfig + JwtAuthenticationFilter bean wire without
#     blowing up on a blank-secret edge path. The placeholder length
#     satisfies Nimbus HS256's ≥256-bit minimum if any future bean
#     constructs a MACVerifier eagerly. Real deployments override via the
#     stage-4 runtime env; the placeholder is throwaway.
#
# CRITICAL: these are inline RUN env vars, NOT ENV directives. Promoting
# them to ENV would bake the throwaway placeholder URL/credentials into
# the runtime image metadata where `docker inspect` could surface them.
#
# `timeout 120` is a HARD upper bound on the warm-up: AppCDS warm-up
# normally completes in ~20–60s. A future regression that exceeds this
# must be diagnosed (lingering non-daemon thread, blocking I/O during
# refresh) rather than papered over by raising the timeout.
#
# `test -s /build/app.jsa` is a fail-fast guard: if the JVM exits before
# writing the archive, this fails the build BEFORE stage 4 can copy an
# empty/missing file and ship a broken image.
FROM eclipse-temurin:21-jre-noble AS cds
WORKDIR /build
COPY --from=api-build /tmp/app.jar /build/app.jar
RUN APP_DATABASE_URL=jdbc:postgresql://cds-build-placeholder:5432/atlas \
    APP_DATABASE_USERNAME=cds \
    APP_DATABASE_PASSWORD=cds \
    APP_DATABASE_STARTUP_CHECK_ENABLED=false \
    SPRING_FLYWAY_ENABLED=false \
    SPRING_DATASOURCE_HIKARI_INITIALIZATION_FAIL_TIMEOUT=-1 \
    SPRING_AOT_ENABLED=true \
    JWT_SECRET=cds-build-placeholder-secret-min-32-characters-long \
    OBJECT_STORAGE_ENDPOINT=http://cds-build-placeholder:9000 \
    OBJECT_STORAGE_PUBLIC_ENDPOINT=http://cds-build-placeholder:9000 \
    OBJECT_STORAGE_REGION=us-east-1 \
    OBJECT_STORAGE_BUCKET=cds-placeholder \
    OBJECT_STORAGE_ACCESS_KEY=cds \
    OBJECT_STORAGE_SECRET_KEY=cds \
    timeout 120 java \
      -XX:ArchiveClassesAtExit=/build/app.jsa \
      -Dspring.context.exit=onRefresh \
      -jar /build/app.jar \
 && test -s /build/app.jsa \
 && echo "AppCDS archive written: $(stat -c '%s bytes' /build/app.jsa)"


# === STAGE 4: runtime — minimal JRE image that runs the jar with AppCDS ===
#
# Rationale:
#   * eclipse-temurin:21-jre-noble is the JRE-only Temurin 21 image (no
#     JDK, no Maven) — keeps the image small.
#   * Non-root user `atlas` (uid 1000): defense-in-depth against container
#     escape; the runtime needs neither root nor any uid > 0 capability.
#   * `app.jar` and `app.jsa` are copied in two separate COPY instructions
#     so the AppCDS archive is its OWN distinct layer in the final image
#     — visible to `docker history | grep app.jsa` and verifiable in CI.
#   * ENTRYPOINT flags:
#       -XX:SharedArchiveFile=/app/app.jsa  activates the AppCDS archive
#         produced by stage 3 (the ~200-400ms cold-start win).
#       -XX:+UseG1GC                        predictable low-pause collector
#         chosen for the target instance size (1 vCPU / 512 MB).
#   * SPRING_AOT_ENABLED=true is set via ENV (not the ENTRYPOINT) so it is
#     auditable in `docker inspect` and overridable for diagnostic boots
#     without rebuilding the image. AOT mode is what consumes the bean
#     metadata generated by the process-aot goal in stage 2.
FROM eclipse-temurin:21-jre-noble AS runtime
# Ubuntu 24.04 (Noble) pre-creates a default `ubuntu` user and group at
# uid/gid 1000, so a bare `groupadd --gid 1000 atlas` collides. Delete
# the default user first (idempotent — `|| true` covers future base
# image variants that drop it) so atlas can take uid/gid 1000. SEC-2
# and EC-9 assert `id -u` == 1000; do not switch to a different uid.
RUN userdel --remove ubuntu 2>/dev/null || true \
 && groupadd --system --gid 1000 atlas \
 && useradd  --system --uid 1000 --gid atlas --home-dir /app --shell /usr/sbin/nologin atlas \
 && mkdir -p /app \
 && chown atlas:atlas /app
WORKDIR /app
COPY --from=api-build --chown=atlas:atlas /tmp/app.jar /app/app.jar
COPY --from=cds       --chown=atlas:atlas /build/app.jsa /app/app.jsa
USER atlas
ENV SPRING_AOT_ENABLED=true
EXPOSE 8080
ENTRYPOINT ["java","-XX:SharedArchiveFile=/app/app.jsa","-XX:+UseG1GC","-jar","/app/app.jar"]
