# Architecture decisions — repo-local sign-offs

> The canonical, full decisions register is maintained in the team's planning
> handoff and pasted into each ticket's Lead session. This file records
> repo-local sign-offs that a ticket was asked to commit alongside code.

## toast_library (T-013 sign-off)

`sonner` is the toast library, mounted as a single `<Toaster richColors position="top-right" />`.

**Relocation (T-013):** the `Toaster` is mounted at the **App.tsx root** (above
`<Routes>`), NOT inside `AppShell`. Rationale: `/login` and `/register` render
outside `AppShell`, and they must be able to surface toasts (e.g. the
"Account created — please sign in" hint after a registration that could not
auto-login). Mounting at the root makes toasts available on every route,
authenticated or not. Do not move the `Toaster` back into `AppShell`.
