import { useQuery } from '@tanstack/react-query';
import { useMemo } from 'react';
import {
  boardListTickets,
  type Ticket,
  type TicketFilters,
  type TicketStatus,
} from '@/api/tickets';
import { BOARD_PAGE_SIZE, COLUMN_ORDER } from './statusOrder';

/**
 * Board query cache key (T-027). Encodes ONLY the non-status filters (status is the
 * column axis, not a filter) so the board caches per filter-combo. The optimistic
 * transition hook MUST use this same key to patch the right cache entry.
 */
export function boardKey(projectId: string, filters: TicketFilters) {
  return [
    'board',
    'tickets',
    projectId,
    {
      priority: filters.priority ?? [],
      assigneeId: filters.assigneeId ?? null,
      label: filters.label ?? [],
    },
  ] as const;
}

/** Single-pass group of tickets into the four columns (pre-seeds all four keys). */
function groupByStatus(tickets: Ticket[]): Record<TicketStatus, Ticket[]> {
  const groups = Object.fromEntries(COLUMN_ORDER.map((s) => [s, [] as Ticket[]])) as Record<
    TicketStatus,
    Ticket[]
  >;
  for (const ticket of tickets) {
    (groups[ticket.status] ?? (groups[ticket.status] = [])).push(ticket);
  }
  return groups;
}

/**
 * Fetches all of a project's tickets (one large page) and groups them into the four
 * board columns. Canonical optimistic-view query options (E1): no focus/reconnect
 * refetch, no retry, 15s stale — so a background refetch can't clobber an in-flight
 * optimistic patch.
 */
export function useBoardTickets(projectId: string, filters: TicketFilters) {
  const query = useQuery({
    queryKey: boardKey(projectId, filters),
    queryFn: () =>
      boardListTickets(projectId, {
        ...filters,
        status: undefined,
        page: 0,
        size: BOARD_PAGE_SIZE,
      }),
    staleTime: 15_000,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
    retry: false,
  });

  const columns = useMemo(() => groupByStatus(query.data?.items ?? []), [query.data]);

  return { columns, isLoading: query.isLoading, isError: query.isError, refetch: query.refetch };
}
