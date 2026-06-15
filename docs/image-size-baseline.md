# Production image size baseline (T-036)

Measured locally with `docker image inspect <tag> --format '{{.Size}}'` (the same metric the CI
`docker-build` size gate uses). Budget: **512 MiB** (`container_image_tags`).

| image-tag | MiB | delta-MiB | delta-pct |
|---|---|---|---|
| atlas:baseline (full JRE, 4-stage) | 198.5 | — | — |
| atlas:sdk-trim (baseline + AWS SDK trim) | — (deferred) | — | — |
| atlas:ci-test (jlink + sdk-trim, 6-stage) | 172.0 | -26.5 | -13.4% |

- **baseline** = `origin/main`'s `/Dockerfile` (`eclipse-temurin:21-jre-noble` + fat jar + AppCDS) with
  the original pom. Real build, 198.5 MiB.
- **ci-test** = this ticket's image: `debian:12-slim` + a jlink custom JRE + the exploded app + the
  AppCDS archive, with the AWS SDK transitive trim. Real build, 172.0 MiB — comfortably under budget.
- **sdk-trim (intermediate)** = baseline Dockerfile + the trimmed pom. Its standalone image was **not**
  built — per the T-036 scope decision (commit core + docs; defer measurement-heavy steps to CI). The
  AWS SDK trim removes the `apache-client` + `netty-nio-client` transitive jars (confirmed absent in the
  final image: `BOOT-INF/lib` has 0 apache/netty jars, 1 `url-connection-client`). The remainder of the
  −26.5 MiB is the jlink minimal JRE vs the full JRE.

## Note on the modest delta

The AppCDS archive (`app.jsa`, ~102 MB) is roughly half of **both** images and is unchanged by this
ticket, so it floors the achievable percentage. The win is real (−13.4%, well under budget) and comes
from the smaller jlink JRE + the SDK trim; a larger relative reduction would require shrinking the
AppCDS archive, which is out of scope (it is the cold-start mechanism, not bloat).
