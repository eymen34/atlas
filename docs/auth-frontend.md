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

## Store contract (`src/store/authStore.ts`, persisted `atlas.auth.v1`) — T-048

The refresh token is **no longer in JS** — it is the backend-set HttpOnly
`atlas_refresh` cookie (see docs/security.md). The store keeps only the access
token:

- Fields: `accessToken`, `accessTokenExpiresAt`, `user`, `status` (**no
  `refreshToken`**).
- `setTokens({ accessToken, accessTokenExpiresAt, user })` — sets all three and
  `status='authenticated'`. (The legacy two-arg `setTokens(access, refresh)` form
  is removed — there is no refresh token to set.)
- `clearTokens()` — nulls the three, `status='unauthenticated'`.
- **Persist version `2`** + a `migrate()` that scrubs any legacy `refreshToken`
  key from a pre-T-048 blob on first load (so the old long-lived credential never
  lingers in `localStorage`).
- **Cross-tab logout**: a `storage` event whose new blob has no `accessToken`/
  `user` (or a `null` newValue = key removed) mirrors a local logout. (Previously
  keyed on `refreshToken`, which would now never fire.)

## Silent refresh (cookie transport)

`src/api/refreshSingleton.ts` exposes `getRefreshPromise()`: concurrent 401s
share **one** in-flight **body-less** `POST /api/auth/refresh` with
`credentials:'include'` (the browser sends the HttpOnly cookie; no token is read
from the store, no `Content-Type` — a body would be 415). `fetchWithAuth`
(`src/api/client.ts`) injects the Bearer header, and on a 401 **always** awaits
the singleton refresh and retries once (there is no store-token gate anymore — JS
cannot see the cookie, so the only way to know if a refresh is possible is to try
it); on refresh failure / malformed refresh it calls the `onUnauthorized` seam
(clear + redirect) and returns the original 401 — no infinite loop. The five
`AUTH_ENDPOINTS` stay excluded so a refresh 401 never recurses. Integration is via
a global `fetch` monkeypatch (idempotent, `Symbol.for('atlas.fetchPatched')`)
because openapi-typescript-codegen 0.29.0 exposes no response-interceptor hook.

### Lone-cookie-no-bootstrap

`AuthProvider` does **not** speculatively refresh at boot when there is no access
token in the store. The refresh cookie is HttpOnly, so JS can't detect whether one
exists; a blind boot-time `POST /api/auth/refresh` would race every reload and, for
a logged-out user, add a guaranteed 401. With no access token we render
unauthenticated; the user re-authenticates via `/login`, which sets a fresh cookie.

### Multi-tab simultaneous bootstrap (accepted UX)

If two tabs refresh the same cookie at once, exactly one wins (server-side
rotation lost-race, T-031) and the losing tab's `/api/auth/refresh` 401s → that
tab clears its store → the cross-tab `storage` signal may propagate the logout.
Accepted: the user simply logs in again. Concurrent legitimate refreshes are NOT
treated as token theft (no mass revoke — the lost-race path is distinct).

## Login / register / logout

`AuthResponse` now carries only `{accessToken, expiresIn}` (the refresh token is
the cookie), so login (`credentials:'include'` to receive the `Set-Cookie`)
stashes the access token and resolves the user with a single `GET /api/auth/me`
before marking the session authenticated. Register auto-logs-in (best effort, one
retry); on failure it sets `sessionStorage['atlas.justRegistered']` and redirects
to `/login`. Logout is a body-less `POST /api/auth/logout` with
`credentials:'include'` (cookie + Bearer); the store is cleared on completion OR
error.

Error mapping never echoes raw backend messages: login 401 → "Invalid email or
password"; any 400 → generic; register 409 → field-level email error.

## E2E

- `npm run e2e` → Playwright **smoke** project (built bundle on preview :4173,
  no backend). Runs in CI.
- `npm run e2e:auth` → Playwright **auth-real-backend** project
  (`playwright test --project=auth-real-backend`). LOCAL ONLY; requires a real
  compose stack with the T-012 backend at `E2E_BASE_URL` (default
  `http://localhost:5173`). Not wired to CI (deferred T-038).
