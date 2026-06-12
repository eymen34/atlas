# Notifications & watchers (T-023 / T-024)

How ticket subscriptions and in-app notifications work. T-023 shipped **watchers**
(who is subscribed to a ticket); T-024 turns events into actual **notification**
rows, polling endpoints, and the AppShell bell. The watcher half is below; the
notification half is the **T-024** section at the end.

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

---

# Notifications (T-024)

## Data model (V11)

`notifications` — one row per (recipient, event) notification:

| column            | notes                                                              |
| ----------------- | ------------------------------------------------------------------ |
| `id`              | surrogate UUID PK (app-generated, no `@GeneratedValue`)            |
| `user_id`         | FK → users(id) — the RECIPIENT; every query is scoped to it        |
| `kind`            | `varchar(32)` CHECK ∈ {ASSIGNED, MENTIONED_TICKET, MENTIONED_COMMENT, WATCHED_STATUS_CHANGED} |
| `ticket_id`       | FK → tickets(id) — the subject ticket                              |
| `source_event_id` | FK → activity_events(id), **NULL**able (see below)                 |
| `payload`         | `text` NOT NULL — small JSON-in-text (D1), see payload schema      |
| `read_at`         | timestamptz NULL — null = unread                                   |
| `created_at`      | timestamptz NOT NULL                                               |

All FKs are **ON DELETE NO ACTION** (no cascade) — consistent with every other
table; the BaseIT teardown deletes `notifications` FIRST (it references users,
tickets, AND activity_events). Index `ix_notifications_user_unread (user_id,
read_at, created_at DESC)` backs the caller-scoped, newest-first list and the
unread filter. `Notification` is the 15th `@Entity` (`NotificationKind` enum lives
in `io.ngss.atlas.domain`, V8/V9 CHECK precedent).

`source_event_id` is **null** when the trigger wrote no activity row — notably a
**ticket description edit**, which produces no `DESCRIPTION_CHANGED` activity (there
is no such `ActivityEventType`). For comments it is the `COMMENT_ADDED`/
`COMMENT_EDITED` event id; for create-with-description it is the `CREATED` id; for
assignment it is the `CREATED`/`ASSIGNEE_CHANGED` id; for a transition it is the
`STATUS_CHANGED` id.

### Payload schema (D1)

`payload` is `text`, not `jsonb` (`json_payload_as_text` + `entity_appcds_hard_rule`
forbid JSON column types). Schema v1, serialized with Jackson 3 (`tools.jackson.*`):

```json
{ "actorId": "<uuid>", "fromStatus": "TODO?", "toStatus": "IN_PROGRESS?", "commentId": "<uuid>?" }
```

`actorId` is **denormalized into the payload, not an FK** — a notification survives
even if the actor later leaves the project or is removed. Only the fields relevant
to a kind are populated (ASSIGNED/MENTIONED_* carry `actorId`, MENTIONED_COMMENT
also `commentId`, WATCHED_STATUS_CHANGED carries `actorId`/`fromStatus`/`toStatus`).

## Kinds, triggers, and skip rules

Events are FACTS (published unconditionally, even self-assign); the
**`NotificationEventListener` owns ALL policy** — one place for every skip/dedup
rule. Each kind:

| kind                     | trigger                                               | skip rule                          |
| ------------------------ | ----------------------------------------------------- | ---------------------------------- |
| `ASSIGNED`               | assign on create OR assignee-change on update (D5)    | self-assign (`assignee == actor`)  |
| `MENTIONED_TICKET`       | @mention in ticket description, on create OR update   | self-mention (`mentioned == actor`)|
| `MENTIONED_COMMENT`      | @mention in a comment body, on create OR edit         | self-mention                       |
| `WATCHED_STATUS_CHANGED` | a ticket transition, fanned to every watcher          | the actor (even if they watch)     |

Self-skips happen **BEFORE** dedup. Notifications are **NOT** feature-flagged — when
the watchers flag is off there are simply no watcher rows, so WATCHED fan-out is
naturally empty (`feature_flag_public_config`).

## Edit re-mention: the DIFF is the primary guard (D4)

A comment edit or description edit notifies **only newly-added** mentions
(`newSet − oldSet`). The old set is captured (`findUserIdsBy…Id`) **BEFORE** the
delete+reinsert of mention rows, mirroring how mentions are re-derived. So
re-mentioning the **same** handle on edit creates **no** new notification; adding a
**new** handle notifies only that user. The diff — not the time window — is the
primary suppressor for edits.

## Dedup window: the (user_id, kind, ticket_id) 3-tuple within 60s

The secondary guard (mainly for create-path races) suppresses an insert when a row
with the SAME **`(user_id, kind, ticket_id)`** already exists with
`created_at > now − 60s`. Note the tuple is a **3-tuple** — `actor_id` is **NOT**
part of it (it lives only in the payload). Consequence (accepted behavior):
**same-kind triggers from different actors in the same 60s window collapse to one
notification.** Different KINDS for the same (user, ticket) never dedup against each
other (e.g. being both assigned and @mentioned in one create → two rows).

## AFTER_COMMIT fan-out (and the test trap)

Each handler is `@TransactionalEventListener(phase = AFTER_COMMIT)` **AND**
`@Transactional(propagation = REQUIRES_NEW)` on the method, with a
`try/catch(Exception)` + ERROR log around the body (`after_commit_requires_new`).
REQUIRES_NEW is mandatory: at AFTER_COMMIT there is no active transaction, so the
inserts would otherwise no-op. The try/catch guarantees a fan-out failure can never
fail the originating request — **notification loss is the accepted tradeoff; the
core write is sacred** (the design lesson from T-023's flag-off 500 incident).

**TEST TRAP:** AFTER_COMMIT listeners never fire inside an `@Transactional`
rollback test. The fan-out ITs therefore drive **real HTTP commits** (RestAssured
over `RANDOM_PORT`); the listener runs SYNCHRONOUSLY on the request thread during
commit, before the response returns, so notification rows are assertable
immediately after the call. Fault isolation is proved without `@SpyBean` (removed in
Spring Framework 7) by registering an extra throwing AFTER_COMMIT listener via
`@TestConfiguration` and asserting the real row still lands while the framework logs
the failure.

## Read API — caller-scoped, batch-enriched

- `GET /api/notifications?unread=&page=&size=` → `PagedResponse<NotificationResponse>`
  (`items/page/size/total`), newest first, size clamped 1..100. `unread=true` filters
  `read_at IS NULL`. Rows are enriched (batch-loaded tickets → projects → actors, **no
  N+1**) with `projectKey`, `ticketKey` (`"{project.key}-{ticket.number}"`, e.g.
  `ENG-12`), `ticketTitle`, and `actorDisplayName`. **Caller-scoped:** `WHERE user_id
  = caller` (from the SecurityContext, never a request param) — there is **no project
  guard**, because notifications are the caller's own data and **outlive project
  membership by design**.
- `POST /api/notifications/{id}/read` → idempotent 204 (already-read stays 204). A
  notification whose `user_id != caller` → **404** (existence-hiding / IDOR-safe: the
  UPDATE's `WHERE id = :id AND user_id = :caller` affects 0 rows → 404).
- `POST /api/notifications/read-all` → 204 always (`UPDATE … SET read_at = now WHERE
  user_id = caller AND read_at IS NULL`).
- operationIds: `listNotifications`, `markNotificationRead`,
  `markAllNotificationsRead`. There is deliberately **NO unread-count endpoint** — the
  badge reuses the list with `unread=true&size=1` and reads `total`.

## Frontend — bell + 30s polling

The `NotificationBell` (AppShell topbar) is a gate/inner split
(`hooks_gate_inner_split`): the always-mounted gate runs a cheap unread BADGE query
(`unread=true&size=1` → `total`) and renders the bell + badge; the inner panel
(mounted only while the dropdown is open) runs the full LIST query, so the heavier
poll only runs while visible. Both poll on `refetchInterval =
Number(import.meta.env.VITE_NOTIFICATION_POLL_INTERVAL_MS ?? 30000)` (default 30s,
HTTP polling per `realtime`; no WebSocket/SSE). A row click marks it read
(invalidating both the list and the badge) and navigates to
`/projects/{projectKey}/tickets/{ticketKey}`; "Mark all read" clears the badge.
Wrapper at `web/src/api/notifications.ts` (`toNotification`, `notificationKeys`,
`listNotifications`/`markNotificationRead`/`markAllNotificationsRead`) + 3 codegen
drift probes in `client.ts`.

## Soft-deleted tickets

Watcher rows survive a ticket soft-delete (above). The notification list enriches
via `ticketRepository.findAllById` (not live-only), so a notification for a
soft-deleted ticket still renders with whatever ticket row exists; deep-linking to a
soft-deleted ticket is governed by the ticket detail page, not the bell.

## Out of scope / future work

- Email notifications (outbox/SMTP — later ticket).
- Per-user notification preferences; notification deletion/retention policy.
- SSE/WebSocket realtime (HTTP polling is the realtime decision).
- Pagination UI beyond the first page in the bell ("view all" deferred).
