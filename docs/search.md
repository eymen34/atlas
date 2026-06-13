# Full-text search (T-028)

PostgreSQL-native full-text search over tickets, exposed as a project-scoped endpoint and
a global (cross-project) endpoint, with a ⌘K command palette and a per-project search box.

## Index — generated tsvector + GIN (V14)

`V14__ticket_search.sql` adds a STORED generated column and a GIN index on `tickets`:

```sql
ALTER TABLE tickets ADD COLUMN search_doc tsvector GENERATED ALWAYS AS (
    to_tsvector('english', coalesce(title, '') || ' ' || coalesce(description, ''))
) STORED;
CREATE INDEX ix_tickets_search_doc ON tickets USING GIN (search_doc);
```

- **Generated, not trigger-maintained.** Postgres recomputes `search_doc` on every
  insert/update of `title`/`description` — no application code, no trigger, no drift.
- **`search_doc` is NOT mapped on the `Ticket` entity.** The entity count stays **17**
  (asserted by a test). A DB-function column type would break the Dockerfile stage-3 no-DB
  AppCDS boot (`entity_appcds_hard_rule`), and the column is read-only anyway — a raw
  `INSERT` that lists it is rejected by Postgres (asserted).
- **English stemming.** `service` matches `services`, `deploy` matches `deployment`, etc.

## Query — `plainto_tsquery`, never string-concat

The repository (`TicketSearchRepository`, a `@Repository` over `EntityManager` native
queries — `search_doc` has no entity, so this is the established native pattern) binds the
raw user input as a parameter:

```sql
WHERE t.deleted_at IS NULL
  AND t.search_doc @@ plainto_tsquery('english', :q)
  AND <scope>
ORDER BY ts_rank_cd(t.search_doc, plainto_tsquery('english', :q)) DESC, t.updated_at DESC
```

- **`plainto_tsquery` (NOT `to_tsquery`)** treats `:q` as plain text — operator characters
  (`&`, `|`, `!`, `:*`, quotes) are literal, never parsed as tsquery syntax. The input is
  ALWAYS a bound `:q` parameter, NEVER concatenated into SQL. Adversarial payloads (`'`,
  `&`, `:*`, `!`, `<script>…`, a 5000-char string) all return **200** with zero
  `SQLException` (asserted in `TicketSearchInjectionIT`).
- **Ranking** is `ts_rank_cd` DESC, tie-broken by `updated_at` DESC (newest first).
- **Snippets** use `ts_headline` with `StartSel=[[, StopSel=]], MaxFragments=2, MaxWords=18,
  MinWords=5`. The `ts_headline` document expression is **byte-identical** to the V14
  generated-column expression (`coalesce(title,'') || ' ' || coalesce(description,'')`) so
  the highlighted text matches what was indexed. The `[[ ]]` sentinels are rendered as
  `<strong>` on the client (see below).
- **Soft-deleted tickets are excluded** (`deleted_at IS NULL`).

## Endpoints

| operationId            | route                                       | scope                                |
| ---------------------- | ------------------------------------------- | ------------------------------------ |
| `searchProjectTickets` | `GET /api/projects/{projectId}/tickets/search` | one project (any member)          |
| `searchAllTickets`     | `GET /api/search/tickets`                   | all of the caller's projects         |

Both are GET (no `consumes`), return `PagedResponse<TicketSearchResult>`, and take
`q` (required), `page` (default 0, floored ≥0), `size` (default 25, **clamped 1..100**).

`TicketSearchResult` = `{ ticketKey, ticketId, title, status, projectKey, projectId,
snippet, updatedAt, rank }`. `ticketKey` (`"ENG-12"`) is assembled in Java as
`projectKey + "-" + number`.

## Authorization — guarded differently per scope

- **Project-scoped:** `projectAccessGuard.requireMember(projectId)` runs FIRST, before the
  repository is touched. A non-member gets **404** (existence-leak prevention), asserted by
  an `InOrder` controller test (`requireMember` before the service).
- **Global:** the caller id comes from `CurrentUser.id()` (the established pattern, reads
  the JWT principal from the SecurityContext — not an inline read). Membership is enforced
  **entirely in SQL** via a `project_members` subquery keyed to `:callerId`:

  ```sql
  AND t.project_id IN (SELECT pm.project_id FROM project_members pm WHERE pm.user_id = :callerId)
  ```

  NEVER a Java post-filter — a ticket in a project the caller is not a member of never
  leaves the database (asserted in `TicketSearchGlobalIT`, SEC-2).

### Edge cases

- Missing `q` → **400** (a `MissingServletRequestParameterException` handler emits the app's
  canonical `{status,error,message,path}` body).
- Whitespace-only `q` → **200** with an empty list (`plainto_tsquery` yields an empty query
  that matches nothing).
- Unauthenticated → **401** (security chain).

## Frontend

- **Wrapper** `web/src/api/search.ts` (`searchAllTickets` / `searchProjectTickets`) maps the
  generated optional-field model to a strict `SearchHit`, and bridges TanStack Query's
  `AbortSignal` to the generated client's `CancelablePromise` so a superseded search aborts.
- **`GlobalSearchDialog`** (mounted in `AppShell`) — a `cmdk` `CommandDialog` opened by a
  header button or ⌘K / Ctrl+K. `shouldFilter={false}` so server-ranked, stemmed results are
  shown verbatim (no client substring re-filter). 250ms debounce; the query is enabled only
  while open and `q` is non-blank.
- **`ProjectSearchInput`** (mounted in the project List page) — a debounced input with a
  results popover scoped to the project.
- **`SnippetText`** splits the snippet on `/\[\[(.+?)\]\]/g` into plain text nodes and
  `<strong>` nodes. It NEVER uses `dangerouslySetInnerHTML`, so server text (including
  anything that looks like markup) can never inject DOM. Null/undefined/empty → renders
  nothing.

## Out of scope (deliberately)

No `search_settings` table, no `SEARCH_LANGUAGE` env (english is hardcoded), no
non-English configs, no `pg_trgm` fuzzy/typo matching, and no search over comments or
attachments. Tickets only.

## Packages

`io.ngss.atlas.search` (controller, service, repository, `TicketSearchRow`) and
`io.ngss.atlas.search.dto` (`TicketSearchResult`).
