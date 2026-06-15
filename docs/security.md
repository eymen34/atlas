# Security notes

## Frontend token storage (T-013 → superseded by T-048)

The **refresh token** is delivered as a backend-set **`HttpOnly` cookie**
(`atlas_refresh`); **only the short-lived access token** (15 min) + the cached
user profile + access-token expiry are persisted in `localStorage` under
`atlas.auth.v1`. The long-lived (30-day) credential is therefore **out of JS
reach** — the XSS-exfiltration risk of the old localStorage scheme is closed.

This **supersedes** the original T-013 trade-off (both tokens in `localStorage`)
and lands the previously-backlogged HttpOnly-cookie migration.

### Refresh cookie attributes (T-048)

`AuthCookieFactory` sets `atlas_refresh` on login, rotates it on refresh, and
clears it (Max-Age=0) on logout / logout-all:

- **HttpOnly** — unreadable by JavaScript (`document.cookie` never shows it); the
  primary XSS-exfiltration defence.
- **Secure** — config `app.auth.cookie.secure` (env `APP_AUTH_COOKIE_SECURE`),
  **true by default**; only dev/test over plain `http://localhost` set it false.
- **SameSite=Lax** — see the CSRF rationale below.
- **Path=/api/auth** — the cookie is sent only to the auth endpoints that need it
  (login/refresh/logout/logout-all), never on ordinary `/api/**` calls.
- **Max-Age = `REFRESH_TOKEN_TTL_DAYS`** (30 days) — tracks the refresh-token TTL.

Plain cookie name (no `__Host-`/`__Secure-` prefix) by design: the `__Host-`
prefix forbids a `Path` attribute, but we deliberately scope to `/api/auth`.

### CSRF: why `csrf().disable()` stays

CSRF protection for the cookie-authenticated refresh/logout endpoints relies on
**`SameSite=Lax`**, NOT Spring Security's CSRF token machinery (`SecurityConfig`
remains `csrf().disable()`, byte-unchanged by T-048). `SameSite=Lax` means the
browser does **not** attach `atlas_refresh` to cross-site **POST** requests (a
forged `POST /api/auth/refresh` from `evil.example` carries no cookie → the
endpoint sees no token → 401). Lax (not Strict) is chosen so a normal top-level
navigation back into the app still works; the refresh/logout endpoints are POST
(state-changing), which Lax does not expose cross-site. The access token remains
a `Authorization: Bearer` header (not a cookie), so the rest of the API is immune
to CSRF by construction (no ambient credential).

CORS: `allowCredentials=true` with an **explicit, non-wildcard** origin list
(`app.cors.allowed-origins`) is required for the browser to send the cookie
cross-origin; the wildcard `*` is incompatible with credentialed requests and is
asserted absent (`CorsConfigIT`).

### Mitigations in place

- Strict dependency review; `npm audit` (HIGH/CRITICAL) gates CI.
- No `dangerouslySetInnerHTML` / no untrusted HTML rendering in auth flows.
- The raw refresh token is never logged (log discipline is asserted by
  `AuthLogDisciplineIT` on the backend and app loggers are scoped in tests).
- A persist-version bump + `migrate()` scrubs any legacy `refreshToken` left in a
  pre-T-048 `atlas.auth.v1` blob on first load.

### Residual / future

- An XSS can still read the **access token** (15-min TTL) from `localStorage` and
  ride the session until it expires; moving the access token to memory-only is a
  possible future hardening (out of scope here).
- The refresh endpoint relies on `SameSite=Lax` rather than a CSRF token; if a
  future requirement needs `SameSite=None` (true cross-site cookie use), a CSRF
  token strategy would have to be reintroduced.

## Login Brute-Force Protection (T-033)

`POST /api/auth/login` is throttled by two independent counters in the native-only
`login_attempts` table (no JPA entity), keyed by `(attempt_key, key_type)`:

- **Per-account** — keyed by the normalized submitted email (`trim().toLowerCase()`). After
  `LOGIN_MAX_ATTEMPTS` (default 5) consecutive failures within `LOGIN_LOCKOUT_WINDOW_MINUTES`
  (default 15), the email is locked (429) until the window elapses. A successful login clears the
  account counter; a stale window (first attempt older than the window) resets it to 1.
- **Per-IP** — keyed by the client IP. After `LOGIN_IP_MAX_ATTEMPTS` (default 20) failures across
  *any* emails from one source, that IP is locked. The IP counter is **not** cleared on a
  successful login (a single success amid spraying must not reopen the floodgates).

The throttle check runs **before** any user lookup or credential verification, so:

- **D4 — a correct password during an active lockout still returns 429** (no access token). This is
  intentional: allowing the correct password through mid-lockout would be a timing/behaviour oracle
  revealing that the email+password pair is valid. Verified by an integration test.
- **Anti-enumeration** — unknown emails throttle identically to known emails (same bucket keyed by
  the submitted email), and the dummy-hash BCrypt compare on the unknown-email path is retained, so
  unknown vs. wrong-password responses stay structurally identical (same 401 body, same 429 body).

**X-Forwarded-For spoofing caveat.** Client IP defaults to the direct peer's `remoteAddr`.
`X-Forwarded-For` is honoured **only** when `LOGIN_TRUSTED_PROXY_CIDRS` is configured **and** the
direct peer is within one of those CIDRs — then the **leftmost** XFF value is used. The default is
**empty**, which never trusts XFF (safe). Misconfiguring this (trusting a CIDR an attacker can reach
directly) would let a client forge its IP bucket key — set it only for reverse proxies you control.

**Maintenance.** The T-053 maintenance sweep (`POST /internal/tasks/run-maintenance`) prunes
`login_attempts` rows whose window has fully elapsed and whose lockout (if any) has expired, so the
table self-bounds. (Pruning runs under `@Transactional(NEVER)` via `NamedParameterJdbcTemplate`.)

**Known limitation.** True timing-indistinguishability between known and unknown emails relies on the
BCrypt dummy-hash compare being constant-*work*; it is not a measurable-latency guarantee. Out of
scope: CAPTCHA, account-lockout email, IP allow/deny lists.
