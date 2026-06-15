import { useQueries } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { apiErrorStatus } from '@/api/errors';
import type { Member } from '@/api/projects';
import { getUserSummary, userKeys } from '@/api/users';
import { type ActorInfo, type ActorLookup, makeActorLookup } from '@/features/tickets/activityMeta';

/** Display names rarely change, so a resolved fallback is cached for an hour. */
const FALLBACK_STALE_TIME = 60 * 60 * 1000;

/** Shown for an actor who has left the project until/unless their name resolves. */
const FORMER_MEMBER: ActorInfo = { name: 'Former member' };

/**
 * Memoized actor resolver (T-022) with a backend fallback for departed members (T-044).
 *
 * The base lookup resolves an id from the project MEMBER list: null → "System",
 * absent → "Unknown user", a member id → their name + avatar. An id that is NOT in
 * the member list belongs to someone who has LEFT the project (their membership row
 * was removed; the user row persists), which previously rendered as "Unknown user".
 * Now each such distinct id is resolved once via GET /api/users/{id} (TanStack Query,
 * keyed by id with a long staleTime so the request is shared across every row), and
 * the actor renders with their real displayName. While loading — or if the user row
 * is genuinely gone (404) — it shows "Former member", never a raw UUID.
 *
 * The lookup runs during the consumer's render and discovers the departed ids it is
 * asked about (including assignee UUIDs nested in event payloads), so no consumer
 * needs to pre-collect them. A first sighting requests the id via a guarded
 * render-phase state update (React's supported "adjust state while rendering"
 * pattern): it adds each id once and converges, so it never loops, and unlike a
 * mount-only effect it keeps picking up ids revealed by later renders (e.g. activity
 * "load more").
 */
export function useActorLookup(members: Member[]): ActorLookup {
  const base = useMemo(() => makeActorLookup(members), [members]);
  const memberIds = useMemo(() => new Set(members.map((m) => m.userId)), [members]);

  // The distinct departed ids seen so far → one fallback query each.
  const [requestedIds, setRequestedIds] = useState<string[]>([]);

  // A 404 (user row gone) is a fixed answer — never retry into it; transient/network
  // errors get a couple of retries.
  const results = useQueries({
    queries: requestedIds.map((id) => ({
      queryKey: userKeys.summary(id),
      queryFn: () => getUserSummary(id),
      staleTime: FALLBACK_STALE_TIME,
      retry: (failureCount: number, error: unknown) =>
        apiErrorStatus(error) !== 404 && failureCount < 2,
    })),
  });

  // id → resolved display name (200) or the "Former member" marker (404). Loading /
  // transient-error ids are deliberately absent so the lookup keeps showing the
  // transient label until they settle.
  const byId = useMemo(() => {
    const map = new Map<string, ActorInfo>();
    requestedIds.forEach((id, i) => {
      const result = results[i];
      if (result?.data) map.set(id, { name: result.data.displayName });
      else if (apiErrorStatus(result?.error) === 404) map.set(id, FORMER_MEMBER);
    });
    return map;
    // results is a fresh array each render; requestedIds is the stable driver.
  }, [requestedIds, results]);

  return useMemo<ActorLookup>(
    () => (actorId) => {
      // System (null) / omitted (undefined) / current members resolve as before.
      if (actorId == null || memberIds.has(actorId)) return base(actorId);
      // Departed actor: serve the resolved name (or 404 marker) once settled, else
      // request a fetch and show the transient label — never the raw UUID. The
      // setter is called ONLY for an id not already queued, so once it is queued no
      // further render-phase update is scheduled and the render converges instead of
      // looping; the inner guard dedupes the same id seen on multiple rows this render.
      const resolved = byId.get(actorId);
      if (resolved) return resolved;
      if (!requestedIds.includes(actorId)) {
        setRequestedIds((prev) => (prev.includes(actorId) ? prev : [...prev, actorId]));
      }
      return FORMER_MEMBER;
    },
    [base, memberIds, byId, requestedIds]
  );
}
