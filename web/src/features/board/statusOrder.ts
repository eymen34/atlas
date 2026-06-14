import type { Ticket, TicketStatus } from '@/api/tickets';

/** Fixed left-to-right Kanban column order (T-027, D1). */
export const COLUMN_ORDER: TicketStatus[] = ['TODO', 'IN_PROGRESS', 'IN_REVIEW', 'DONE'];

/** Human column-header labels (the enum values are SCREAMING_CASE). */
export const STATUS_LABEL: Record<TicketStatus, string> = {
  TODO: 'To Do',
  IN_PROGRESS: 'In Progress',
  IN_REVIEW: 'In Review',
  DONE: 'Done',
};

/** One large page so the whole project groups into the four columns in one call (D1). */
export const BOARD_PAGE_SIZE = 500;

/**
 * Decides the target status for a drag-end (T-027). Returns null — meaning NO mutation —
 * when the drop is over nothing, over a non-column, or over the ticket's CURRENT column
 * (same-column drop is a no-op, AC2c). Pure so the drag→transition decision is unit-tested
 * without simulating a dnd-kit drag (the real drag is exercised by the e2e-local spec).
 */
export function boardDropTarget(
  ticket: Ticket,
  overId: string | number | null | undefined
): TicketStatus | null {
  if (typeof overId !== 'string' || !COLUMN_ORDER.includes(overId as TicketStatus)) {
    return null;
  }
  if (overId === ticket.status) {
    return null;
  }
  return overId as TicketStatus;
}
