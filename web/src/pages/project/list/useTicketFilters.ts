import { useCallback, useMemo } from 'react';
import { useSearchParams } from 'react-router';
import {
  PRIORITY_OPTIONS,
  STATUS_OPTIONS,
  type TicketFilters,
  type TicketPriority,
  type TicketStatus,
} from '@/api/tickets';

/** The page sizes the footer offers; also the set the URL accepts. */
export const SIZE_OPTIONS = [10, 25, 50, 100] as const;
export const DEFAULT_SIZE = 25;
const DEFAULT_PAGE = 0;
const MAX_SIZE = 100;

function parsePage(raw: string | null): number {
  const n = Number(raw);
  return Number.isInteger(n) && n >= 0 ? n : DEFAULT_PAGE;
}

function parseSize(raw: string | null): number {
  const n = Number(raw);
  if (!Number.isInteger(n)) {
    return DEFAULT_SIZE;
  }
  if (n > MAX_SIZE) {
    return MAX_SIZE; // clamp oversized requests to the backend max
  }
  return (SIZE_OPTIONS as readonly number[]).includes(n) ? n : DEFAULT_SIZE;
}

/**
 * Reads/writes the ticket-list filter + pagination state from the URL query
 * string (React Router 7 useSearchParams) using the EXACT backend param names
 * (status, priority, assigneeId, label, page, size); multi-value params are
 * repeated keys, not CSV. Unknown enum values are dropped and page/size are
 * clamped defensively so a hand-edited URL never throws.
 *
 * <p>setFilters is a pure writer (strips defaults + empty arrays). It does NOT
 * auto-reset the page — the filter controls reset page to 0 themselves, while the
 * pager preserves the page it sets.
 */
export function useTicketFilters(): [TicketFilters, (next: TicketFilters) => void] {
  const [params, setParams] = useSearchParams();

  const filters = useMemo<TicketFilters>(() => {
    const status = params
      .getAll('status')
      .filter((v): v is TicketStatus => (STATUS_OPTIONS as readonly string[]).includes(v));
    const priority = params
      .getAll('priority')
      .filter((v): v is TicketPriority => (PRIORITY_OPTIONS as readonly string[]).includes(v));
    const label = params.getAll('label');
    const assigneeId = params.get('assigneeId') ?? undefined;
    return {
      status: status.length > 0 ? status : undefined,
      priority: priority.length > 0 ? priority : undefined,
      assigneeId: assigneeId && assigneeId.length > 0 ? assigneeId : undefined,
      label: label.length > 0 ? label : undefined,
      page: parsePage(params.get('page')),
      size: parseSize(params.get('size')),
    };
  }, [params]);

  const setFilters = useCallback(
    (next: TicketFilters) => {
      const sp = new URLSearchParams();
      (next.status ?? []).forEach((s) => sp.append('status', s));
      (next.priority ?? []).forEach((p) => sp.append('priority', p));
      (next.label ?? []).forEach((l) => sp.append('label', l));
      if (next.assigneeId) {
        sp.set('assigneeId', next.assigneeId);
      }
      if (next.page > 0) {
        sp.set('page', String(next.page));
      }
      if (next.size !== DEFAULT_SIZE) {
        sp.set('size', String(next.size));
      }
      setParams(sp);
    },
    [setParams]
  );

  return [filters, setFilters];
}
