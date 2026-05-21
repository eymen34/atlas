# Contributing

## Architecture overview

Atlas follows a **single-deployable** model. The production artifact is one
Spring Boot jar that bundles the compiled React frontend under
`src/main/resources/static/`. The **same artifact** ships to self-hosted
customers and to our SaaS — there is no separate "SaaS build" and no
"enterprise build". Anything that would differ between modes is driven by
configuration, not by code paths or build flavors.

There is no separate worker process. Background work (email, attachment
finalize, notifications, activity log) runs either inline in the request that
caused it or by draining a Postgres outbox table from an external cron caller.

## Configuration policy

Atlas is configured **env-var only**. The full environment variable reference
will be documented under `/docs/`.

Do not commit any `application-*.yml` profile file or override file to the
repository. Profiles, overrides, and per-environment YAML fragments are not
how this project is configured.

If a future ticket introduces a `.env.example` file, that file must contain
only comments and placeholder values. No real credentials, no real hostnames
pointing at internal infrastructure, no API keys — placeholders only.

## Branch and PR conventions

Detailed branch and PR conventions arrive in a later sprint. Until then:

- Branch off `main`.
- Use a short, imperative commit message subject (e.g., `chore: repo skeleton`).
- Open a PR against `main` and request review from a CODEOWNER.
