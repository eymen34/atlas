import { type UserSummaryResponse, UsersService } from './generated';

// Re-export the shared error helper so the actor-lookup fallback can branch on a
// 404 (departed user row genuinely gone) without importing './errors' separately.
export { apiErrorStatus } from './errors';

/**
 * T-044 app-facing user API. The single backend endpoint resolves a user id to a
 * display-only summary — used as the actor-lookup fallback (see
 * {@link import('@/hooks/useActorLookup')}) for authors who have left a project, so
 * historical activity/comments render their NAME instead of a raw UUID. The DTO is
 * deliberately display-only: {@code {id, displayName}}, never email/role/PII.
 */
export interface UserSummary {
  id: string;
  displayName: string;
}

/** TanStack Query key for a single user's display summary (one cache entry per id). */
export const userKeys = {
  summary: (id: string) => ['user', id, 'summary'] as const,
};

function toUserSummary(r: UserSummaryResponse): UserSummary {
  return { id: r.id ?? '', displayName: r.displayName ?? '' };
}

/**
 * Resolve one user's display name by id. 404 means the user row is genuinely gone
 * (rare — removal from a project deletes only the membership row, not the user), so
 * the caller renders a graceful "Former member" label rather than retrying.
 */
export async function getUserSummary(id: string): Promise<UserSummary> {
  return toUserSummary(await UsersService.getUserSummary(id));
}
