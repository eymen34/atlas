# Testing

How the test layers run, and how to run the backend-dependent E2E suite locally.

## Layers

| Layer | What | Where | Backend? |
| --- | --- | --- | --- |
| Backend unit + IT | `mvn verify` (JUnit + Failsafe/Testcontainers) | `api/` | yes (Testcontainers; CI Linux) |
| Frontend unit | `npm test` (Vitest + RTL, MSW) | `web/` | no |
| **PR e2e-smoke** | route-render / zero-pageerror smoke | `web/e2e/*.smoke.spec.ts` (`smoke` project) | **no — Vite preview :4173 only** |
| **Nightly e2e-full** | full backend flows (auth, tickets, comments, watchers, attachments, links, board, search) | `auth-real-backend` + `e2e-local` projects | **yes — real compose stack** |

The PR pipeline (`.github/workflows/pr.yml`, 6 jobs, pull-request-only) runs everything except the
nightly e2e-full. The fast `e2e-smoke` job stays **Vite-preview-only** (no backend) — it is not
touched by the nightly. The backend-dependent E2E lives in its own workflow,
`.github/workflows/e2e-full.yml` (`separate_workflows_for_non_pr_events`).

## Nightly backend E2E — `.github/workflows/e2e-full.yml`

Triggers: `schedule` (03:00 UTC) + manual `workflow_dispatch` ONLY — never `push` / `pull_request`.
It brings up `deploy/docker-compose.yml` (app + Postgres + MinIO + MailHog), waits for the app's
`GET /ready` → `{"status":"READY"}`, then runs the `auth-real-backend` + `e2e-local` Playwright
projects via `npm run e2e:full`. Artifacts: `web/playwright-report/` (always) and `web/test-results/`
(on failure). The stack is always torn down (`docker compose down -v`).

No hand-rolled "zero-test guard": Playwright 1.60 exits non-zero on both a wrong `--project` name
("Project(s) … not found") and zero matching tests ("No tests found") — we do **not** pass
`--pass-with-no-tests`, so a mis-wired project can never produce a silent green.

## Running it locally

The dev compose image (`Dockerfile.dev`) serves the **API only** at `:8080` — it does not bundle the
SPA. The Vite **dev server** (`:5173`) serves the SPA and proxies `/api` → `:8080`
(`vite.config.ts` `server.proxy`), so the browser specs target `:5173`, not `:8080`. (The Vite
*preview* server at `:4173` has no `/api` proxy and is for the backend-less smoke suite only.)

### macOS / Linux

```sh
cp .env.example .env                                   # dev defaults; non-empty secrets
docker compose -f deploy/docker-compose.yml up --build # backend stack at :8080 (waits for /ready)
# in a second terminal:
cd web
npm ci && npm run codegen                              # generate the (gitignored) API client
npm run dev &                                          # Vite dev server at :5173, proxies /api -> :8080
E2E_BASE_URL=http://localhost:5173 npm run e2e:full
```

### Windows (PowerShell)

PowerShell has no `&&` — chain with `;` or separate lines, and set env vars with `$env:`.

```powershell
Copy-Item .env.example .env
docker compose -f deploy/docker-compose.yml up --build   # leave running; new terminal for the rest
cd web
npm ci; npm run codegen
Start-Process npm -ArgumentList 'run','dev'              # Vite dev server :5173 in a separate process
$env:E2E_BASE_URL = 'http://localhost:5173'; npm run e2e:full
```

`npm run e2e:full` runs the `auth-real-backend` + `e2e-local` projects and writes an HTML report to
`web/playwright-report/` (`npx playwright show-report` to open it).

## Secrets in CI

The nightly workflow writes an **ephemeral** `.env` for the compose stack: `cp .env.example .env`,
then overrides `JWT_SECRET`, `OUTBOX_DRAIN_SHARED_SECRET`, and the `OBJECT_STORAGE_*` / `MINIO_ROOT_*`
credentials with `${{ secrets.X || '<literal-fallback>' }}`. The literal fallbacks are **non-empty by
design** (an empty `JWT_SECRET` breaks token signing/verification so the auth specs can't log in; an
empty `OUTBOX_DRAIN_SHARED_SECRET` trips the `InternalSecretFilter` `isBlank()` short-circuit).

> ⚠️ These literal fallbacks are for the **ephemeral CI stack ONLY**. Never reuse them in any real
> environment, and never point the nightly at a shared/persistent database or bucket.

## Backlog

- **prod_image_nightly**: the nightly builds the app from source via the dev compose image
  (`Dockerfile.dev`, `mvn spring-boot:run`), not the published `ghcr.io/eymen34/atlas` production
  image. Running the suite against the real 4-stage AppCDS image (same-origin SPA at `:8080`, no Vite
  layer) is a separate future ticket.
