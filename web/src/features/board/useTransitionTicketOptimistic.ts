import { useMutation, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import {
  type Ticket,
  type TicketFilters,
  type TicketPage,
  type TicketStatus,
  ticketKeys,
  transitionTicket,
} from '@/api/tickets';
import { boardKey } from './useBoardTickets';

export interface TransitionVars {
  ticket: Ticket;
  toStatus: TicketStatus;
}

/**
 * Optimistic status transition for the board (T-027, D2). On drop: cancelQueries
 * (AWAITED before the snapshot — E1, so a background refetch can't clobber the patch),
 * snapshot the cached page, patch the ticket's status in place; then call
 * transitionTicket with ticket.id (the UUID, never the key — mutation_uuid_vs_route_key)
 * and toStatus. On error: restore the snapshot + toast. On settle: invalidate the board
 * plus the ticket's detail/activity/list views (the server wrote a STATUS_CHANGED
 * activity row). Same-column drops are short-circuited in the drag handler, never here.
 */
export function useTransitionTicketOptimistic(projectId: string, filters: TicketFilters) {
  const qc = useQueryClient();
  const key = boardKey(projectId, filters);

  return useMutation({
    mutationFn: ({ ticket, toStatus }: TransitionVars) => transitionTicket(ticket.id, toStatus),
    onMutate: async ({ ticket, toStatus }: TransitionVars) => {
      await qc.cancelQueries({ queryKey: key }); // MUST precede the snapshot (E1)
      const prev = qc.getQueryData<TicketPage>(key);
      if (prev) {
        qc.setQueryData<TicketPage>(key, {
          ...prev,
          items: prev.items.map((t) => (t.id === ticket.id ? { ...t, status: toStatus } : t)),
        });
      }
      return { prev };
    },
    onError: (_err, _vars, context) => {
      if (context?.prev) {
        qc.setQueryData(key, context.prev);
      }
      toast.error('Could not move ticket');
    },
    onSettled: (_data, _err, { ticket }) => {
      void qc.invalidateQueries({ queryKey: key });
      // The detail/activity views key by the DISPLAY key (mutation_uuid_vs_route_key).
      void qc.invalidateQueries({ queryKey: ticketKeys.detail(ticket.key) });
      void qc.invalidateQueries({ queryKey: ticketKeys.activity(ticket.key) });
      void qc.invalidateQueries({ queryKey: ticketKeys.lists(ticket.projectId) });
    },
  });
}
