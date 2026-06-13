# Kanban board (T-027)

A four-column drag-and-drop board at `/projects/:projectIdOrKey/board` (also the
project index route). FRONTEND-ONLY — no backend changes; it reuses the existing
`listTickets` + `transitionTicket` endpoints.

## Columns (D1)

Four columns, fixed left-to-right order: **TODO → IN_PROGRESS → IN_REVIEW → DONE**
(`COLUMN_ORDER` in `features/board/statusOrder.ts`). Each header shows the label +
a live card count. Cards are grouped CLIENT-side (single-pass `useMemo`) from ONE
`boardListTickets` call (no status filter) pulling up to `BOARD_PAGE_SIZE = 500`
tickets. There is no server-side board endpoint.

## Within-column ordering — NOT supported

Cards appear in the server's return order (newest-ish per the list query). Dragging a
card to a different column changes its STATUS only; dragging WITHIN a column does
nothing (it is a same-column no-op). There is no manual ordering, no rank field — this
is intentional, not a bug.

## Optimistic transition (D2)

On drop into a different column the card moves IMMEDIATELY (optimistic): the mutation
`onMutate` awaits `cancelQueries(boardKey)` **before** snapshotting (E1 — so a
background refetch can't clobber the patch), then patches the cached page's ticket
status. It then calls `transitionTicket(ticket.id, toStatus)` — the **UUID**, never the
display key (`mutation_uuid_vs_route_key`). On error it restores the snapshot and
toasts; on settle it invalidates the board plus the ticket's detail/activity/list views
(the server wrote a `STATUS_CHANGED` activity row). A same-column drop fires no
mutation. This optimism is safe because the status workflow is UNRESTRICTED
(`ticket_status_unrestricted`) — any status → any other is legal, so the server never
rejects a transition on workflow grounds; only an infra/network error rolls back.

## Drag & keyboard (D3)

`@dnd-kit/core` with a `PointerSensor` (`activationConstraint: { distance: 8 }`) so a
click on the card body navigates to the ticket while a drag (past 8px, started on the
separate grip handle) moves it. A `KeyboardSensor` with a custom `coordinateGetter`
(`keyboardCoordinates.ts`) makes Left/Right jump the card to the adjacent column and
Up/Down nudge within it — full keyboard accessibility. `DndContext` sets
`measuring={{ droppable: { strategy: MeasuringStrategy.Always } }}` (E3) so droppable
column rects stay fresh during a virtualized-column scroll mid-drag.

## Performance (D4)

`useMemo` single-pass `groupByStatus`; each card is `React.memo` with an explicit
comparator (id/status/title/priority/assigneeId + derived chips). A column virtualizes
(`@tanstack/react-virtual`) ONLY past `VIRTUALIZE_THRESHOLD = 100` cards — flagged as a
YAGNI candidate for post-T-027 review (most columns are far smaller).

## Filters shared with the list (D5)

Filter state lives in the URL search params (`useTicketFilters`), shared with `/list`.
The board reuses the list's `FilterBar` with `hideStatus` — **status is the column
axis, not a filter**. A `ProjectViewToggle` (Board | List) preserves the query string
across the switch, so priority/assignee/label filters survive a Board↔List round-trip.

## `?status` stripping on mount (E2 — intentional)

Because status is the column axis, the board strips any `?status` (and `?page`) from
the URL on mount via `setSearchParams({ replace: true })` — and `boardListTickets`
defensively strips status at the wrapper boundary too. `FilterBar(hideStatus)` neither
renders nor reads the status param. So a hand-edited `?status=DONE` is removed and
ignored; all four columns always render.

## Testing

- RTL (`features/board/__tests__`): column grouping/counts, fetch-without-status,
  card-link-uses-key, XSS escape, `?status` strip, filter URL round-trip
  (`BoardPage.test`); optimistic patch / rollback+toast / UUID arg / cancelQueries-
  before-snapshot / invalidations (`useTransitionTicketOptimistic.test`); the
  same-column-no-op + drop decision (`statusOrder.test`); `hideStatus` (`FilterBar.test`).
- `@smoke` (`web/e2e/board.smoke.spec.ts`): backend-less, the board deep link redirects
  to `/login` with zero pageerror (the route chunk loads).
- The actual drag/keyboard INTERACTION is exercised by `web/e2e-local/board.dnd.local.spec.ts`
  (real backend) — jsdom can't reliably simulate a dnd-kit pointer/keyboard drag.
