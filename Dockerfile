# syntax=docker/dockerfile:1.7
#
# Atlas — production container image (T-036: jlink custom runtime + AppCDS).
#
# Six stages:
#   1. web-build — npm-build the Vite/React frontend (→ /web/dist)
#   2. build     — Maven-build the Spring Boot 4 fat jar (frontend baked into static/)
#   3. layers    — explode the executable fat jar (JDK `jar -xf`) into a runnable tree
#   4. jlink     — build a minimal custom JRE (/opt/jre) tailored to the app's modules
#   5. appcds    — warm the AppCDS archive ON the jlink JRE (so -Xshare:on strict works)
#   6. runtime   — debian-slim + the jlink JRE + exploded app + app.jsa
#
# AppCDS-on-jlink invariant: stages 5 and 6 each pull the SAME jlink-built /opt/jre — the
# archive is dumped and consumed on the byte-identical custom JRE, so -Xshare:on (strict)
# maps it instead of silently falling back. See docs/jlink-runtime.md.

# Global ARG (re-declared bare in the jlink + runtime stages so both see this same default —
# Docker ARGs do not cross stages otherwise; the runtime LABEL would be empty without this).
ARG ADD_MODULES="java.base,java.compiler,java.desktop,java.instrument,java.logging,java.management,java.management.rmi,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.sql.rowset,java.transaction.xa,java.xml,java.xml.crypto,jdk.crypto.cryptoki,jdk.crypto.ec,jdk.httpserver,jdk.localedata,jdk.management,jdk.management.agent,jdk.naming.dns,jdk.naming.rmi,jdk.net,jdk.security.auth,jdk.security.jgss,jdk.unsupported,jdk.zipfs,jdk.charsets"

# === STAGE 1: web-build — compile the Vite frontend → /web/dist ===
# The production artifact is a single Spring Boot jar; the compiled bundle is copied
# into api/src/main/resources/static/ by stage 2 so Boot serves the UI directly.
FROM node:22-alpine AS web-build
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
# T-010: stage the committed OpenAPI spec so codegen (prebuild) finds it in this web-only context.
COPY api/src/main/resources/openapi/openapi.json ./openapi.json
RUN npm run build


# === STAGE 2: build — Maven build the Spring Boot fat jar (single /api module) ===
# Two-step layer cache: copy pom + run dependency:go-offline (cacheable), then copy the
# frontend bundle + sources and package. AOT is opt-in (the `aot` profile) and intentionally
# NOT run here (springdoc 2.6 + Spring Data 4 incompatibility) — AppCDS works on the runtime
# class graph regardless.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /api
COPY api/pom.xml ./pom.xml
COPY api/.mvn* ./.mvn
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp dependency:go-offline
COPY --from=web-build /web/dist ./src/main/resources/static/
COPY api/src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -DskipTests package \
 && cp target/atlas-api-*.jar /tmp/app.jar


# === STAGE 3: layers — explode the executable fat jar into a runnable tree ===
# This jar has no BOOT-INF/layers.idx (layering not enabled in the build), so `extract --layers`
# produces an empty loader layer. Instead explode the executable jar directly with the JDK `jar`
# tool → exploded/{BOOT-INF/lib, BOOT-INF/classes, org/springframework/boot/loader, META-INF}.
# That is exactly the runnable classpath JarLauncher expects, and stages 5/6 COPY it identically
# so the AppCDS dump and runtime see a byte-identical classpath.
FROM eclipse-temurin:21-jdk-jammy AS layers
WORKDIR /app
COPY --from=build /tmp/app.jar app.jar
RUN mkdir -p exploded && cd exploded && jar -xf ../app.jar


# === STAGE 4: jlink — minimal custom JRE tailored to the app's modules ===
# ADD_MODULES is the seed list (jdeps under-reports reflective/ServiceLoader modules — D2);
# missing modules surface at stage 5 (AppCDS boot) and are added here iteratively.
# jdk.localedata is REQUIRED by --include-locales=en (the plugin only trims jdk.localedata;
# without it jlink errors). --strip-native-commands is intentionally NOT used, so jlink emits
# a correct /opt/jre/bin/java launcher matched to this runtime (no fragile copy-from-JDK).
FROM eclipse-temurin:21-jdk-jammy AS jlink
ARG ADD_MODULES
RUN "$JAVA_HOME/bin/jlink" \
      --module-path "$JAVA_HOME/jmods" \
      --add-modules "$ADD_MODULES" \
      --strip-debug \
      --no-header-files \
      --no-man-pages \
      --compress=zip-6 \
      --include-locales=en \
      --output /opt/jre \
 && /opt/jre/bin/java -Xshare:dump \
 && /opt/jre/bin/java -version
# jlink does NOT ship the default static CDS archive; `-Xshare:dump` generates
# /opt/jre/lib/server/classes.jsa. The stage-5 dynamic dump (-XX:ArchiveClassesAtExit) layers
# the app classes ON this base, and stage-6 -Xshare:on maps both. Because /opt/jre (incl. this
# base archive) is COPYed byte-identically into stages 5 and 6, the base stays consistent.


# === STAGE 5: appcds — warm the AppCDS archive ON the jlink JRE ===
# Inline env on RUN — NEVER ENV directives (appcds_boot_safety): build-time placeholders for
# the no-DB context refresh must not be baked into image metadata. -Dspring.context.exit=onRefresh
# exits cleanly once refresh completes, flushing the archive. The >10 MiB gate hard-fails the
# build if the dump was empty/truncated (which would trip -Xshare:on at runtime).
FROM debian:12-slim AS appcds
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates \
 && rm -rf /var/lib/apt/lists/*
COPY --from=jlink /opt/jre /opt/jre
WORKDIR /app
# Exploded app (BOOT-INF + loader + classes) — identical layout to stage 6 so the AppCDS
# classpath matches byte-for-byte.
COPY --from=layers /app/exploded/ ./
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
    timeout 120 /opt/jre/bin/java \
      -XX:ArchiveClassesAtExit=/app/app.jsa \
      -Dspring.context.exit=onRefresh \
      org.springframework.boot.loader.launch.JarLauncher \
 && test "$(stat -c%s /app/app.jsa)" -gt 10485760 \
 && echo "AppCDS archive: $(stat -c '%s bytes' /app/app.jsa)"


# === STAGE 6: runtime — slim base + jlink JRE + exploded app + AppCDS archive ===
FROM debian:12-slim AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates tzdata \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system --gid 1000 atlas \
 && useradd  --system --uid 1000 --gid atlas --home-dir /app --shell /usr/sbin/nologin atlas \
 && mkdir -p /app && chown atlas:atlas /app
COPY --from=jlink /opt/jre /opt/jre
ARG ADD_MODULES
LABEL io.ngss.atlas.jlink-modules="${ADD_MODULES}"
WORKDIR /app
# SAME exploded layout + same jlink JRE as stage 5 → identical classpath for AppCDS mapping.
COPY --from=layers --chown=atlas:atlas /app/exploded/ ./
COPY --from=appcds --chown=atlas:atlas /app/app.jsa /app/app.jsa
USER atlas
ENV PATH="/opt/jre/bin:$PATH"
ENV SPRING_AOT_ENABLED=true
# NO ENV JWT_SECRET / APP_DATABASE_* — all runtime secrets are operator-injected; the
# container fails fast at startup if JWT_SECRET is absent.
EXPOSE 8080
ENTRYPOINT ["/opt/jre/bin/java", \
  "-XX:SharedArchiveFile=/app/app.jsa", \
  "-Xshare:on", \
  "-XX:+UseG1GC", \
  "org.springframework.boot.loader.launch.JarLauncher"]
