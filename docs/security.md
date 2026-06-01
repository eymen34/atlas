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
