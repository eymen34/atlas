# Frontend auth (T-013)

## Route map

| Path | Guard | Renders |
|------|-------|---------|
| `/login` | `AuthRedirect` (→ `/projects` if already authed) | `LoginPage` (outside AppShell) |
| `/register` | `AuthRedirect` | `RegisterPage` (outside AppShell) |
| `/projects`, `/projects/:projectId` | `ProtectedRoute` (→ `/login` if not authed) | inside `AppShell` |
| `*` | — | redirect to `/projects` |

`ProtectedRoute` shows a spinner while `status === 'authenticating'` so the UI
never flashes to `/login` during boot.

## Store contract (`src/store/authStore.ts`, persisted `atlas.auth.v1`)

- Fields: `accessToken`, `refreshToken`, `accessTokenExpiresAt`, `user`, `status`.
- `setTokens(accessToken, refreshToken)` — back-compat (T-010); leaves
  `accessTokenExpiresAt` + `user` untouched.
- `setTokens({ accessToken, refreshToken, accessTokenExpiresAt, user })` — full
  form; sets all four and `status='authenticated'`.
- `clearTokens()` — nulls all four, `status='unauthenticated'`.
- Cross-tab: a `storage` event that clears or drops the refresh token mirrors a
  local logout.

## Silent refresh

`src/api/refreshSingleton.ts` exposes `getRefreshPromise()`: concurrent 401s
share **one** in-flight `POST /api/auth/refresh` (singleton dedup). `fetchWithAuth`
(`src/api/client.ts`) injects the Bearer header, and on a 401 (with a refresh
token present) awaits the singleton and retries once; on no token / refresh
failure / malformed refresh it calls the `onUnauthorized` seam (clear + redirect)
and returns the original 401 — no infinite loop. Integration is via a global
`fetch` monkeypatch (idempotent, `Symbol.for('atlas.fetchPatched')`) because
openapi-typescript-codegen 0.29.0 exposes no response-interceptor hook.

## Login / register

`AuthResponse` carries no user (`{accessToken, refreshToken, expiresIn}`), so
login stashes the tokens and resolves the user with a single `GET /api/auth/me`
before marking the session authenticated. Register auto-logs-in (best effort,
one retry); on failure it sets `sessionStorage['atlas.justRegistered']` and
redirects to `/login`, which shows a one-shot toast.

Error mapping never echoes raw backend messages: login 401 → "Invalid email or
password"; any 400 → generic; register 409 → field-level email error.

## E2E

- `npm run e2e` → Playwright **smoke** project (built bundle on preview :4173,
  no backend). Runs in CI.
- `npm run e2e:auth` → Playwright **auth-real-backend** project
  (`playwright test --project=auth-real-backend`). LOCAL ONLY; requires a real
  compose stack with the T-012 backend at `E2E_BASE_URL` (default
  `http://localhost:5173`). Not wired to CI (deferred T-038).
