# T-016 — implementation notes

Project list page, create dialog, project shell + members/settings UI, with
backend `callerRole`/`memberCount` on `ProjectResponse`.

## Clarifying-question resolutions

- **Sidebar gating for non-admins** — Members + Settings nav items are **not
  rendered** (conditional JSX) for non-admin callers, not merely disabled. This
  matches the AC test expectation (`queryByRole('link', …)` is `null`). Gating is
  UX-only; the backend (`ProjectAccessGuard.requireAdmin`) remains the real
  boundary and returns 403/404 on every member/settings mutation.
- **Playwright AC #8 (real-backend register A+B → A adds B → B sees it)** —
  deferred (see below).

## Decisions / deviations from the original plan

1. **List projection (constraint 4).** The plan suggested a record-DTO
   constructor projection `SELECT new ProjectListRow(p, m.role, count)`. Hibernate
   6.6 does not support an **entity** argument in a constructor expression, and
   projecting `p.key` as an individual column collides with the reserved JPQL
   `KEY` word. Instead `ProjectRepository.findProjectListRowsForMember` selects the
   `Project` **root entity** plus `m.role` and a correlated `COUNT` subquery as a
   tuple (`Object[]`), mapped in `ProjectService`. This mirrors the proven
   `MemberResponse` `JOIN … ON` pattern, is a single SQL statement (PERF-1), and
   sidesteps both pitfalls. No `ProjectListRow` DTO was needed.

2. **Form libraries already present.** `react-hook-form`, `zod`, and
   `@hookform/resolvers` were already declared in `web/package.json` (the existing
   auth pages use them); no new dependency was added.

3. **Role pickers use a native `<select>`, not the shadcn `Select`.** The shadcn
   `Select` is a Radix portal component that needs pointer/`scrollIntoView`
   polyfills to drive in jsdom (the test setup has none). A label-associated
   native `<select>` is fully accessible and reliably testable
   (`getByLabelText` + `fireEvent.change`).

4. **API wrapper layer (`web/src/api/projects.ts`).** The generated client models
   every `ProjectResponse` field as optional and splits the backend `ProjectRole`
   into four namespaced enums. A thin wrapper normalizes responses into required
   app shapes and a single `ProjectRole = 'MEMBER' | 'ADMIN'` union so components
   never touch generated namespaces. `client.ts` still re-exports the raw
   services + compile-time drift probes (AC-2.4).

5. **Member endpoints require a UUID.** `/api/projects/{id}/members*` use
   `@PathVariable UUID id` (not id-or-key). So member operations use `project.id`
   (UUID) for the API and the members-query cache key, while the project **detail**
   query is keyed by the URL segment (`idOrKey`, which may be a key). Member
   mutations invalidate both `['project', <uuid>, 'members']` and
   `['project', <idOrKey>]` to refresh the header `memberCount`.

## Deferred: real-backend full E2E flow (AC #8) → T-038

The register-A+B → A-creates → A-adds-B → B-sees-it flow needs a running backend.
The repo's Playwright config scopes CI to `*.smoke.spec.ts` (the `smoke` project,
no backend) and keeps real-backend specs out of CI by file-name pattern
(`auth.spec.ts` → local-only `auth-real-backend` project). Rather than commit a
dormant spec that no project executes (bit-rot risk), this flow is recorded here
as a test-design artifact to be promoted into the nightly real-backend suite
(T-038), alongside `auth.spec.ts`:

1. Register user A and user B via the UI.
2. As A: create a project; assert redirect to `/projects/<key>` and
   `callerRole=ADMIN`, `memberCount=1`.
3. As A: open Settings → add B by email as MEMBER; assert `memberCount=2`.
4. Log in as B; assert the project appears in B's list with `callerRole=MEMBER`,
   and that B does **not** see the Members/Settings nav items.

The CI smoke spec `web/e2e/projects.smoke.spec.ts` covers the mocked-API render +
dialog path that does not need a backend.
