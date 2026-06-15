# Security notes

## Frontend token storage (T-013)

Access + refresh tokens (and the cached user profile + access-token expiry) are
persisted in **`localStorage`** under the key `atlas.auth.v1`
(`frontend_token_storage` decision).

### XSS trade-off

`localStorage` is readable by any JavaScript running on the origin, so a
successful XSS would expose both tokens. We accept this for the MVP because:

- The app is a same-origin SPA served by the Spring Boot jar; there is no
  third-party script surface beyond vetted npm dependencies.
- Refresh tokens are **rotated on use** and revocable server-side (T-012): a
  stolen refresh token is invalidated the moment the legitimate client next
  refreshes (reuse of a rotated token returns 401 — a theft signal).
- Access tokens are short-lived (15 min).

### Mitigations in place

- Strict dependency review; `npm audit` (HIGH/CRITICAL) gates CI.
- No `dangerouslySetInnerHTML` / no untrusted HTML rendering in auth flows.
- The raw refresh token is never logged (log discipline is asserted by
  `AuthLogDisciplineIT` on the backend and app loggers are scoped in tests).

### Future migration (backlog)

Move the **refresh token** to an `HttpOnly`, `Secure`, `SameSite=Strict` cookie
so it is unreadable by JS, keeping only the short-lived access token in memory.
This requires a backend `Set-Cookie` on login/refresh and a CSRF strategy for
the refresh endpoint. Tracked in the auth backlog (post-MVP).

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
