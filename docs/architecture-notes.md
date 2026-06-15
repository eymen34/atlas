# Architecture notes

## Dockerfile stage layout (T-036)

As of T-036 the production image (`/Dockerfile`) is built in **six stages**:

```
web-build → build → layers → jlink → appcds → runtime
```

| stage | base | does |
|---|---|---|
| web-build | node:22-alpine | builds the Vite/React SPA → `/web/dist` |
| build | maven:3.9-eclipse-temurin-21 | bakes `dist` into `static/`, `mvn package` → executable fat jar |
| layers | eclipse-temurin:21-jdk-jammy | explodes the fat jar (`jar -xf`) into a runnable tree |
| jlink | eclipse-temurin:21-jdk-jammy | builds the minimal custom JRE `/opt/jre` (+ base CDS via `-Xshare:dump`) |
| appcds | debian:12-slim | warms the AppCDS archive `app.jsa` ON the jlink JRE |
| runtime | debian:12-slim | slim base + `/opt/jre` + exploded app + `app.jsa`, non-root uid 1000 |

This **supersedes the prior "4-stage AppCDS" description** in the `container_image_tags` decision
(web-build → api-build → cds → runtime on a full JRE). The jlink pipeline proper is 5 stages
(build → layers → jlink → appcds → runtime); the **web-build** prerequisite (the SPA must be compiled
into the jar so Spring Boot serves it) makes the file 6 `FROM` stages — dropping it would ship a
UI-less image.

Key invariants (details in [`docs/jlink-runtime.md`](./jlink-runtime.md)):

- **AppCDS-on-jlink:** stages `appcds` and `runtime` `COPY --from=jlink /opt/jre` (byte-identical JRE,
  including its base CDS archive), so `-Xshare:on` (strict) maps `app.jsa` instead of falling back.
- **No build-time secrets in image metadata:** the AppCDS warm-up's placeholder env is inline on the
  `RUN` (never `ENV`), so `docker inspect` never surfaces them (`appcds_boot_safety`).

References: T-006 (AppCDS), T-025 (AWS SDK v2 / S3), T-036 (jlink runtime + SDK trim).
