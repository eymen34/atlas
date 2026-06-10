# @mentions & comments (T-022)

How comments and `@mention` resolution work, and the invariants that keep them
safe and consistent.

## Comment body is HTML (D1)

A comment body is the HTML emitted by TipTap (`editor.getHTML()`) — never
markdown. It is stored verbatim in `comments.body` (`text`, CHECK length ≤ 16384)
and rendered READ-ONLY on the way out (see XSS below).

## mention_handle (D3) — global, lowercase, frozen

Every user gets a `mention_handle`: globally unique, lowercase, derived ONCE at
registration from the email local-part.

- **Derivation** (`MentionHandleGenerator`): lowercase the local-part, strip
  anything outside `[a-z0-9._-]`, truncate to **60 chars BEFORE** any collision
  suffix (so a `-N` suffix can never push it past the 64-char column), fall back
  to `user` if empty. If the base is taken, try `base-2`, `base-3`, … up to 5
  suffixes; exhausting them throws `HandleGenerationException` → 409.
- **Contention** is two-layered: the `existsByMentionHandle` pre-check is the
  fast path; the V9 `users_mention_handle_key` UNIQUE index is the hard backstop.
  A losing concurrent race surfaces as `DataIntegrityViolationException` → 409
  (exactly like the email race) and is deliberately NOT retried inside the
  registration transaction (a JPA save retry after a constraint violation hits
  the transaction's rollback-only state).
- **Frozen on email change**: no email-update path may re-derive the handle.
  Re-deriving would orphan existing mentions. Backfill (V9) assigns handles to all
  pre-existing users with a deterministic `ROW_NUMBER` collision suffix ordered by
  `(created_at, id)`.

## Server is the mention authority (D4)

`MentionParser` (server-side) is the ONLY thing that decides who was mentioned —
the client's mention metadata is never trusted.

1. Reject bodies over 16384 chars (no scan).
2. Strip HTML tags to spaces — so a TipTap
   `<span data-id="…" data-label="alice">@alice</span>` resolves from the VISIBLE
   `@alice` text, NOT the attribute.
3. Lowercase, then match `(?:^|\s)@([a-z0-9._-]{1,64})`.
4. Drop a single run of trailing punctuation (`@alice.` → `alice`).
5. Resolve the distinct handles against the ticket's project membership via an
   unassociated entity JOIN (Hibernate 6.6):
   `SELECT u.id FROM ProjectMember pm JOIN User u ON u.id = pm.userId
    WHERE pm.projectId = :p AND lower(u.mentionHandle) IN :handles`.

Non-members, bare emails (`foo@bar.com`), and unknown handles resolve to nothing.
Mentions are stored in `comment_mentions` / `ticket_mentions` (surrogate-UUID join
tables, `UNIQUE(parent_id, user_id)`), re-derived from scratch on every edit.

## Soft delete is server-redacted (D5)

`DELETE /api/comments/{id}` stamps `deleted_at`, removes the comment's mention
rows, and writes a `COMMENT_DELETED` activity row. The row is RETAINED: `GET`
returns it with `body=null`, `deleted=true`, and no mention ids, so the timeline
keeps its place. A second delete → 404.

## XSS guard (frontend)

Stored comment/description HTML is NEVER injected via `dangerouslySetInnerHTML`.
It is rendered read-only through a TipTap editor (`editable:false`) whose shared
`mentionConfig` Mention node parses/renders ONLY `data-id` and `data-label`. Any
other attribute on a stored `<span>` (onclick/onerror/style/href) is dropped on
parse; `<img>`/`<script>` are not in the StarterKit schema and are dropped
entirely. The composer and the read-only item import the SAME `mentionConfig`, so
an authored mention round-trips identically.

## Activity

Each comment mutation writes an activity row SYNCHRONOUSLY in the same transaction
(`activity_synchronous_writer`): `COMMENT_ADDED` / `COMMENT_EDITED` /
`COMMENT_DELETED`. These three `ActivityEventType` values (and the V8 CHECK
constraint) already existed from T-019, so V9 does NOT alter the activity schema.

## Packages (T-022)

`io.ngss.atlas.comment` (Comment aggregate: service, controller, repositories),
`io.ngss.atlas.comment.dto` (request/response records), `io.ngss.atlas.mention`
(`MentionParser`, `MentionHandleGenerator`). Entities (`Comment`,
`CommentMention`, `TicketMention`) live in `io.ngss.atlas.domain` with the others.

## Out of scope / future work

- User-initiated handle rename (handles are frozen at registration).
- A UUID-based author fallback so a comment by someone who has LEFT the project
  still shows their name (today: "Unknown user").
- Suppressing no-op `COMMENT_EDITED` rows; capping distinct mention candidates per
  body; a real autocomplete-popup polish pass.
