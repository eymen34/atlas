# jlink custom runtime + AppCDS (T-036)

The production image (`/Dockerfile`) runs the app on a **custom minimal JRE built with `jlink`**,
with an **AppCDS archive generated on that same jlink JRE**. This shrank the image from a full-JRE
base to **~172 MiB** (budget 512 MiB) while keeping the standard HotSpot runtime (no GraalVM native).

## Stage layout (6 stages)

`web-build → build → layers → jlink → appcds → runtime`

- **web-build** (`node:22-alpine`) — Vite/React → `/web/dist`.
- **build** (`maven:3.9-eclipse-temurin-21`) — copies `dist` into `api/src/main/resources/static/`,
  then `mvn -DskipTests package` → the executable fat jar (the SPA is served by Boot).
- **layers** — explodes the fat jar (see "Explode, not extract --layers" below).
- **jlink** — builds `/opt/jre` (see "jlink command").
- **appcds** — warms `app.jsa` on the jlink JRE (see "AppCDS-on-jlink invariant").
- **runtime** (`debian:12-slim`) — slim base + `/opt/jre` + exploded app + `app.jsa`, non-root uid 1000.

> The canonical "5-stage jlink+AppCDS" wording describes the jlink *pipeline*
> (build→layers→jlink→appcds→runtime); the **web-build** prerequisite (the SPA must be baked into
> the jar) makes it 6 `FROM` stages. Dropping web-build would ship a UI-less image.

## jlink command

```
jlink --module-path "$JAVA_HOME/jmods" \
      --add-modules "$ADD_MODULES" \
      --strip-debug --no-header-files --no-man-pages \
      --compress=zip-6 \
      --include-locales=en \
      --output /opt/jre
java -Xshare:dump          # generate the base CDS archive (see below)
```

`--strip-native-commands` is **deliberately omitted**: jlink then emits a correct `/opt/jre/bin/java`
launcher matched to this runtime, avoiding a fragile copy-from-JDK workaround.

## `--add-modules` — per-module rationale

`ADD_MODULES` is a hand-maintained seed list (jdeps under-reports reflective / `ServiceLoader`
providers, so it cannot be derived automatically). The app **fully boots** on this list (Hikari, JPA,
Spring Security, actuator all initialize) — the auth-flow smoke is the end-to-end proof. Non-obvious
entries:

| module | why |
|---|---|
| `java.sql`, `java.sql.rowset` | PostgreSQL JDBC driver |
| `java.naming`, `jdk.naming.dns` | DNS resolution for the Postgres / S3 hosts |
| `java.security.sasl` | PostgreSQL **SCRAM-SHA-256** auth (SASL client) |
| `jdk.crypto.ec` | EC crypto for TLS (ECDHE) and EC-curve JWT |
| `jdk.crypto.cryptoki` | **PKCS#11** (HSM / smartcard). NOTE: this is *not* needed for Postgres SCRAM (that's `java.security.sasl` + java.base's `javax.crypto`); retained only for a future HSM-backed keystore. Safe to drop for a few more KB if no HSM is ever used. |
| `jdk.unsupported` | `sun.misc.Unsafe` (Hibernate / ByteBuddy / Reactor) |
| `jdk.zipfs` | zip/nested-jar filesystem provider (Boot loader) |
| `java.desktop` | `javax.imageio` — T-025 inline JPEG thumbnail generation |
| `java.management`, `jdk.management*` | JMX + actuator metrics |
| `java.xml`, `java.xml.crypto` | XML + XML-dsig providers pulled by Spring/security autoconfig |
| `jdk.localedata` | **REQUIRED by `--include-locales=en`** — the locale plugin only trims `jdk.localedata`; without it jlink errors out. |

`--include-locales=en` keeps en-only locale data. If non-en bundles are ever needed the symptom is a
runtime `MissingResourceException`; the fix is to widen `--include-locales` (jdk.localedata is already
present).

## AppCDS-on-jlink invariant

The AppCDS archive **must** be dumped and consumed on the **byte-identical** JRE, or `-Xshare:on`
(strict) refuses to map it and the JVM aborts. Two guarantees enforce this:

1. **Same `/opt/jre`.** Stages 5 (appcds) and 6 (runtime) **both** `COPY --from=jlink /opt/jre` — the
   exact same jlink output, including its base CDS archive.
2. **Base CDS archive.** A jlink runtime ships **without** the default static CDS
   (`lib/server/classes.jsa`), and the dynamic dump (`-XX:ArchiveClassesAtExit`) **requires a base
   archive** (else: `-XX:ArchiveClassesAtExit is unsupported when base CDS archive is not loaded`).
   So the jlink stage runs `java -Xshare:dump` to generate `classes.jsa`; the appcds stage's dynamic
   dump layers the app classes on top of it; the runtime maps both.

Verified locally:
```
Opened archive /opt/jre/lib/server/classes.jsa.   (base, static)
Opened archive /app/app.jsa.                       (app, dynamic ~102 MB)
Mapped static region #0..2 ... Mapped dynamic region #0..2
```
The stage-5 `RUN test "$(stat -c%s /app/app.jsa)" -gt 10485760` is a hard build-fail if the dump is
empty/truncated — catching a silent CDS failure before it trips `-Xshare:on` at runtime.

## Explode, not `extract --layers`

The fat jar has **no `BOOT-INF/layers.idx`** (Boot layering is not enabled in this build), so
`java -Djarmode=tools ... extract --layers` produces an empty `spring-boot-loader` layer and never
extracts the launcher. The `layers` stage therefore explodes the executable jar directly with the JDK
`jar` tool (`jar -xf`) into `exploded/{BOOT-INF/lib,BOOT-INF/classes,org/.../loader,META-INF}` — the
exact runnable classpath `org.springframework.boot.loader.launch.JarLauncher` expects. Stages 5 and 6
COPY this identically so the AppCDS classpath matches byte-for-byte.

## Module-discovery loop (if a future change breaks stage 5)

If stage 5 (appcds) fails with `ClassNotFoundException` / `NoClassDefFoundError`:
1. read the missing class from the build log;
2. map it to its module (`jdeps -s`, or the JDK module Javadoc);
3. add the module to the global `ADD_MODULES` ARG;
4. document it in the table above; rebuild. Repeat until stage 5 completes and `app.jsa` is written.

## Residual risk — S3 presigning not covered by the smoke

`S3Client` / `S3Presigner` are `@Lazy` (never constructed during the AppCDS no-DB boot **or** the
auth-flow smoke). So the auth smoke does **not** exercise the S3 module path. Follow-up: add a MinIO
presign round-trip to the smoke suite to prove the S3 + url-connection-client modules at runtime.

## Cold start

`cold_start_strategy` targets `/ready ≤ 5s` on 1 vCPU / 512 MB. Per the T-036 scope decision the cold
start is measured by the CI `docker-build` job (compose `--cpus=1 --memory=512m` smoke) rather than on
the Windows dev box; the median is recorded from that run. If it exceeds 5s, fall back from
`--compress=zip-6` to `--compress=zip-2` in the jlink stage (re-verify the image stays < 512 MiB).

## Windows/PowerShell note

Use `;` not `&&` to chain; for `docker run -v` use forward slashes with `${PWD}`
(e.g. `-v ${PWD}/x:/x`). `docker build`/`docker compose` are locally reproducible on the Windows dev box.
