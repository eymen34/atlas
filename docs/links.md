# Ticket links (T-026)

Reciprocal relations between two tickets in the same project.

## Relation model — 5 stored, 3 user-facing

| user-facing (create) | stored on from→to | stored on to→from (inverse) |
| -------------------- | ----------------- | --------------------------- |
| `BLOCKS`             | `BLOCKS`          | `IS_BLOCKED_BY`             |
| `DUPLICATES`         | `DUPLICATES`      | `IS_DUPLICATED_BY`          |
| `RELATES_TO`         | `RELATES_TO`      | `RELATES_TO` (self-inverse) |

The V13 CHECK lists all five stored types. The API ACCEPTS only the three user-facing
ones on create; `IS_BLOCKED_BY` / `IS_DUPLICATED_BY` are server-derived and rejected
with **400** if requested directly (`LinkRelation.isUserFacing`). The UI offers only
the three.

## Reciprocal storage (D2)

Every create persists TWO `ticket_links` rows in ONE transaction — the relation and
its `inverse()` — sharing the same `created_by` + `created_at`, each with its own
surrogate UUID id. They are paired implicitly by `(from,to) ↔ (to,from)`; there is no
pair-id column, so deletion re-derives the partner via `inverse()`. A delete removes
BOTH rows in one transaction. Each create writes two `LINK_ADDED` activity rows (one
per ticket, from that ticket's perspective); each delete writes two `LINK_REMOVED`.

## One relation per pair (D4)

At most one link may exist between a given pair of tickets, in EITHER direction. A
second attempt — same direction, cross-direction, same or different relation — is
rejected with **409**. To change a relation, delete the link and re-add it. The
conflict is caught by an optimistic pre-check (`existsByFromTicketIdAndToTicketId` in
both directions), NOT by catching the `uq_link` `DataIntegrityViolation`
(`jpa_rollback_only_trap`), so no insert is attempted on conflict.

> **Known limitation — concurrent race:** two simultaneous creates for the same pair
> could both pass the pre-check and then one hits `uq_link` (surfacing as a 500 rather
> than 409). The window is tiny and self-heals (one link lands); `SERIALIZABLE`
> isolation is the mitigation path if it is ever observed in production.

## Same-project only (D5)

Both tickets must belong to the same project. The target key (e.g. `ENG-12`) is
resolved via the existing `findByProjectKeyAndNumberAndDeletedAtIsNull` finder and
then checked against the source ticket's `projectId`. A key that is malformed,
unknown, resolves into ANOTHER project, or whose project is soft-deleted ALL return
the SAME **400 "Unknown ticket key"** — a caller cannot probe another project's
existence. A self-link → 400 (`chk_no_self` is the DB backstop).

## Authorization

- Create / list: any member of the ticket's project (non-member → 404, existence-leak
  prevention). Unauthenticated → 401.
- Delete: **any project member**, regardless of who created the link (the link is a
  shared property of the pair, not owned by its creator).

## Soft-deleted target (D2 ADDENDUM / EC-13)

The list does NOT filter out links whose target ticket is soft-deleted. The row is
returned with `targetDeleted: true`; the UI marks it "(deleted)" but keeps it visible
and clickable (the target's detail page shows its own tombstone). Enrichment uses
`findAllById` (which includes soft-deleted rows), so a link's target is always
resolvable.

## Read enrichment (D6)

`GET /api/tickets/{id}/links` returns a BARE array (newest first), each row enriched
with the target's `targetTicketKey` / `targetTitle` / `targetStatus` / `targetDeleted`.
Enrichment is batched — links, then one `findAllById` for the targets, then one project
load (all targets are same-project) = a fixed 3 queries regardless of link count (no
N+1, asserted by a statistics test). Grouping into the five sections is CLIENT-side.

## Known limitations

- **Ticket soft-delete leaves link rows.** Soft-deleting a ticket does NOT remove its
  `ticket_links` rows (no cascade). The links remain and surface on the partner with
  `targetDeleted: true`. A future cleanup (with the T-029 outbox sweeper) may reap them.
- **No transitive cycle detection.** `A blocks B`, `B blocks C`, `C blocks A` is
  allowed — the system does not detect or prevent dependency cycles. Out of scope.

## Packages

`io.ngss.atlas.link` (service, controller, repository, exceptions) and
`io.ngss.atlas.link.dto` (request/response records). `TicketLink` + `LinkRelation`
live in `io.ngss.atlas.domain`. `LinkAddedPayload`/`LinkRemovedPayload` live with the
other activity payloads in `io.ngss.atlas.activity.payload`.
