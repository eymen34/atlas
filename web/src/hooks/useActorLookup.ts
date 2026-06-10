import { useMemo } from 'react';
import type { Member } from '@/api/projects';
import { type ActorLookup, makeActorLookup } from '@/features/tickets/activityMeta';

/**
 * Memoized actor resolver (T-022) — the reusable form of the member-based lookup
 * first written for the activity timeline (T-021), now shared by the comment
 * thread. Resolves an actor id to {name, avatarUrl}: null → "System", missing →
 * "Unknown user", a UUID → the member's name (or "Unknown user" if they have since
 * left the project — a known limitation; a UUID-based fallback is future work).
 */
export function useActorLookup(members: Member[]): ActorLookup {
  return useMemo(() => makeActorLookup(members), [members]);
}
