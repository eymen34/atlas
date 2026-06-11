# Notifications & watchers (T-023)

Seed doc for the notification system. T-023 ships **watchers** (who is subscribed
to a ticket); T-024 will turn watcher rows into actual notifications.

## Watcher data model (V10)

`ticket_watchers` — one row per (ticket, user) subscription:

| column      | notes                                              |
| ----------- | -------------------------------------------------- |
| `id`        | surrogate UUID PK (app-generated, no `@GeneratedValue`) |
| `ticket_id` | FK → tickets(id), ON DELETE NO ACTION              |
| `user_id`   | FK → users(id), ON DELETE NO ACTION                |
| `created_at`| timestamptz                                        |

`UNIQUE (ticket_id, user_id)` makes watching idempotent; `ix_ticket_watchers_user_id`
backs the reverse lookup ("every ticket a user watches") that T-024 fan-out needs.
Entity: `io.ngss.atlas.domain.TicketWatcher` (14th entity).

## Idempotency

- **Watch** (`PUT /api/tickets/{id}/watch`): native `INSERT … ON CONFLICT
  (ticket_id, user_id) DO NOTHING`. A second PUT is a 204 no-op (one row). This is
  the explicit alternative to catching `DataIntegrityViolationException` to retry,
  which would poison the surrounding transaction (`jpa_rollback_only_trap`).
- **Unwatch** (`DELETE …/watch`): `DELETE … WHERE ticket_id=? AND user_id=?` —
  removing a non-watch affects 0 rows, a 204 no-op, and only the caller's row.

## Auto-watch triggers (exhaustive)

A user becomes a watcher automatically when, with the feature flag ON:

1. **Ticket create** — the **creator** is watched. If the ticket is created with an
   **assignee**, that assignee is also watched (creator == assignee → exactly ONE
   row, no error).
2. **Assignee change on update** — the **new assignee** (non-null) is watched.
3. **Comment create** — the **commenter** is watched.

All auto-watch rows share the originating change's `Instant` (so a watcher row and
its triggering activity row carry the same timestamp). All auto-watch helpers use
`@Transactional(propagation = MANDATORY)` — they run inside the
ticket/comment write transaction and are atomic with it.

## Explicit non-triggers

- The **actor** who performs an assignee change is NOT auto-watched (only the new
  assignee is).
- **Unassigning** adds nobody.
- Editing **labels**, **priority**, **title**, or **description** does NOT auto-watch.
- Watch/unwatch themselves write **no activity event** (out of scope).

## Feature flag

- `app.feature.watchers.enabled` ← `FEATURE_WATCHERS_ENABLED` (default **true**;
  NOT `APP_`-prefixed, same as `JWT_SECRET`).
- Exposed unauthenticated at `GET /api/config/public` →
  `{"features":{"watchers":true|false}}` so the SPA can render/hide the toggle
  before login.
- **Flag OFF**: all three watcher endpoints return **404** (existence-leak parity —
  a disabled feature leaks no ticket existence); auto-watch helpers are no-ops, so
  ticket/comment create still succeed writing zero watcher rows; the watch toggle
  is **absent** from the UI (not merely disabled), and the watcher list is never
  fetched.

## Soft-deleted tickets (note for T-024)

Watcher rows are NOT removed when a ticket is soft-deleted — `ticket_watchers` has
no `deleted_at` and the watch endpoints load by `findById` (not live-only). T-024
notification fan-out MUST therefore filter on the ticket's `deleted_at` (or join
live tickets) so it does not notify watchers of a soft-deleted ticket. Retaining
the rows means watchers survive an undelete.

## T-024 outline (not in this ticket)

- A `notifications` table (recipient, ticket, kind, read_at, created_at).
- On a watched-ticket change (transition, comment, mention, …), fan out a
  notification row per watcher (minus the actor), using the `user_id` index.
- The notification bell polls (HTTP polling per `realtime`); no WebSocket/SSE.
- Per-user notification preferences and a watcher-management UI are later tickets.
