import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { apiErrorStatus } from '@/api/errors';
import {
  getTicket,
  listTicketActivity,
  setTicketLabels,
  type Ticket,
  ticketKeys,
  type TicketPatch,
  type TicketStatus,
  transitionTicket,
  unassignTicket,
  updateTicket,
} from '@/api/tickets';

/** Context returned by the optimistic onMutate handlers for rollback. */
interface OptimisticContext {
  prev: Ticket | undefined;
}

/**
 * Fetch a ticket by its route identifier (UUID or display key). A 404 is NOT
 * retried so the not-found state renders promptly; transient errors retry twice.
 */
export function useTicketDetail(idOrKey: string) {
  return useQuery({
    queryKey: ticketKeys.detail(idOrKey),
    queryFn: () => getTicket(idOrKey),
    retry: (failureCount, error) => apiErrorStatus(error) !== 404 && failureCount < 2,
  });
}

/** Page size for the ticket activity feed (T-045 load-more). */
const ACTIVITY_PAGE_SIZE = 20;

/**
 * Fetch a ticket's activity as a load-more (infinite) query (T-045). Pages are
 * fetched in order (0, 1, …) and accumulated; {@link getNextPageParam} returns
 * the next page index only while the loaded rows are fewer than the server's
 * total, so the caller can hide the "load more" control once everything is in.
 * Keyed by the route identifier but fetched by the resolved ticket UUID, so it
 * only runs once the detail query has produced an id.
 */
export function useTicketActivity(idOrKey: string, ticketId: string | undefined) {
  return useInfiniteQuery({
    queryKey: ticketKeys.activity(idOrKey),
    queryFn: ({ pageParam }) => listTicketActivity(ticketId!, pageParam, ACTIVITY_PAGE_SIZE),
    initialPageParam: 0,
    getNextPageParam: (lastPage, allPages) => {
      const loaded = allPages.reduce((sum, page) => sum + page.items.length, 0);
      return loaded < lastPage.total ? allPages.length : undefined;
    },
    enabled: !!ticketId,
  });
}

/** Invalidate the three views a ticket mutation can affect. */
function invalidateTicketViews(
  qc: ReturnType<typeof useQueryClient>,
  idOrKey: string,
  ticket: Ticket | undefined
): void {
  void qc.invalidateQueries({ queryKey: ticketKeys.detail(idOrKey) });
  void qc.invalidateQueries({ queryKey: ticketKeys.activity(idOrKey) });
  if (ticket) {
    void qc.invalidateQueries({ queryKey: ticketKeys.lists(ticket.projectId) });
  }
}

/**
 * Optimistic field PATCH (title/description/priority/assignee-SET). Snapshots the
 * cached ticket, writes the patch, rolls back + toasts on error, and invalidates
 * detail/activity/lists on settle. PATCH only SETS the assignee (a UUID); CLEARING
 * is {@link useUnassignTicket} (T-041), so no null is handled here.
 */
export function useUpdateTicket(idOrKey: string, ticket: Ticket | undefined) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (patch: TicketPatch) => updateTicket(ticket!.id, patch),
    onMutate: async (patch): Promise<OptimisticContext> => {
      await qc.cancelQueries({ queryKey: ticketKeys.detail(idOrKey) });
      const prev = qc.getQueryData<Ticket>(ticketKeys.detail(idOrKey));
      if (prev) {
        qc.setQueryData<Ticket>(ticketKeys.detail(idOrKey), { ...prev, ...patch });
      }
      return { prev };
    },
    onError: (_err, _patch, context) => {
      if (context?.prev) {
        qc.setQueryData(ticketKeys.detail(idOrKey), context.prev);
      }
      toast.error('Could not save change');
    },
    onSettled: () => invalidateTicketViews(qc, idOrKey, ticket),
  });
}

/**
 * Optimistic unassign (DELETE /tickets/{id}/assignee, T-041). Mirrors
 * {@link useUpdateTicket}'s shape — cancelQueries → snapshot → optimistically clear
 * the cached assignee → restore + toast on error → invalidate on settle — but clears
 * rather than sets, and needs no payload (the verb itself means "clear").
 */
export function useUnassignTicket(idOrKey: string, ticket: Ticket | undefined) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => unassignTicket(ticket!.id),
    onMutate: async (): Promise<OptimisticContext> => {
      await qc.cancelQueries({ queryKey: ticketKeys.detail(idOrKey) });
      const prev = qc.getQueryData<Ticket>(ticketKeys.detail(idOrKey));
      if (prev) {
        qc.setQueryData<Ticket>(ticketKeys.detail(idOrKey), { ...prev, assigneeId: undefined });
      }
      return { prev };
    },
    onError: (_err, _vars, context) => {
      if (context?.prev) {
        qc.setQueryData(ticketKeys.detail(idOrKey), context.prev);
      }
      toast.error('Could not unassign');
    },
    onSettled: () => invalidateTicketViews(qc, idOrKey, ticket),
  });
}

/**
 * Status transition (POST /transition). NOT optimistic — the server owns the
 * status state machine and writes the activity row, so we only invalidate on
 * success (and surface a toast on failure).
 */
export function useTransitionTicket(idOrKey: string, ticket: Ticket | undefined) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (toStatus: TicketStatus) => transitionTicket(ticket!.id, toStatus),
    onError: () => {
      toast.error('Status change failed');
    },
    onSuccess: () => invalidateTicketViews(qc, idOrKey, ticket),
  });
}

/** Optimistic label replace (PUT). Same snapshot/rollback/invalidate shape. */
export function useSetTicketLabels(idOrKey: string, ticket: Ticket | undefined) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (labelIds: string[]) => setTicketLabels(ticket!.id, labelIds),
    onMutate: async (labelIds): Promise<OptimisticContext> => {
      await qc.cancelQueries({ queryKey: ticketKeys.detail(idOrKey) });
      const prev = qc.getQueryData<Ticket>(ticketKeys.detail(idOrKey));
      if (prev) {
        qc.setQueryData<Ticket>(ticketKeys.detail(idOrKey), { ...prev, labelIds });
      }
      return { prev };
    },
    onError: (_err, _labelIds, context) => {
      if (context?.prev) {
        qc.setQueryData(ticketKeys.detail(idOrKey), context.prev);
      }
      toast.error('Could not update labels');
    },
    onSettled: () => invalidateTicketViews(qc, idOrKey, ticket),
  });
}
