import { useMemo } from 'react';
import type { Member } from '@/api/projects';
import { Button } from '@/components/ui/button';
import { useTicketActivity } from './hooks';
import { TicketActivityTimeline } from './TicketActivityTimeline';

export interface ActivitySectionProps {
  /** Route identifier — the activity query is keyed by it (matches the cache key
   *  the ticket/comment mutations invalidate). */
  idOrKey: string;
  /** Resolved ticket UUID — the activity endpoint binds a UUID path var. */
  ticketId: string;
  members: Member[];
}

/**
 * Ticket activity feed with load-more pagination (T-045). Owns the infinite
 * activity query — mirroring {@link CommentsSection}'s self-contained pattern on
 * this same page — flattens the accumulated pages into the presentational
 * {@link TicketActivityTimeline}, and renders a "Load more" control while more
 * rows remain (hidden once all rows are loaded). The page cursor lives in the
 * query cache (ephemeral view state), never the URL: it is scroll state, not a
 * shareable filter.
 */
export function ActivitySection({ idOrKey, ticketId, members }: ActivitySectionProps) {
  const query = useTicketActivity(idOrKey, ticketId);

  // Pages are created_at DESC and non-overlapping, so a plain flatten never
  // duplicates a row (PRE-FLIGHT C). React keys each row by its event id.
  const events = useMemo(
    () => (query.data?.pages ?? []).flatMap((page) => page.items),
    [query.data]
  );

  return (
    <div className="space-y-3">
      <TicketActivityTimeline events={events} members={members} />
      {query.hasNextPage && (
        <div className="flex justify-center">
          <Button
            type="button"
            variant="outline"
            size="sm"
            data-testid="activity-load-more"
            onClick={() => void query.fetchNextPage()}
            disabled={query.isFetchingNextPage}
          >
            {query.isFetchingNextPage ? 'Loading…' : 'Load more'}
          </Button>
        </div>
      )}
    </div>
  );
}
