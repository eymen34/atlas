// AC2/AC3 NOTE: the optimistic move + transitionTicket(UUID) + rollback + invalidation
// + cancelQueries-before-snapshot are asserted HERE by driving the mutation directly.
// The actual drag/keyboard INTERACTION is exercised by web/e2e-local/board.dnd.local.spec.ts
// (real backend) — jsdom can't reliably simulate a dnd-kit pointer/keyboard drag
// (frontend_test_layers: full DnD flows belong in e2e-local).
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/api/tickets', async (importActual) => {
  const actual = await importActual<typeof import('@/api/tickets')>();
  return { ...actual, transitionTicket: vi.fn() };
});
vi.mock('sonner', () => ({ toast: { error: vi.fn(), success: vi.fn() } }));

import { type Ticket, type TicketFilters, type TicketPage, ticketKeys, transitionTicket } from '@/api/tickets';
import { toast } from 'sonner';
import { boardKey } from '../useBoardTickets';
import { useTransitionTicketOptimistic } from '../useTransitionTicketOptimistic';

const FILTERS: TicketFilters = { page: 0, size: 25 };
const PROJECT = 'p1';

function ticket(): Ticket {
  return {
    id: 'uuid-1',
    key: 'ENG-1',
    title: 'First',
    status: 'TODO',
    priority: 'P2',
    reporterId: 'u1',
    labelIds: [],
    createdAt: '',
    updatedAt: '',
    projectId: PROJECT,
    number: 1,
  };
}

function setup() {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const page: TicketPage = { items: [ticket()], page: 0, size: 500, total: 1 };
  qc.setQueryData(boardKey(PROJECT, FILTERS), page);
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={qc}>{children}</QueryClientProvider>
  );
  const { result } = renderHook(() => useTransitionTicketOptimistic(PROJECT, FILTERS), { wrapper });
  const cached = () => qc.getQueryData<TicketPage>(boardKey(PROJECT, FILTERS));
  return { qc, result, cached };
}

afterEach(() => vi.clearAllMocks());

describe('useTransitionTicketOptimistic', () => {
  it('optimistically patches the cached status before the server responds', async () => {
    vi.mocked(transitionTicket).mockReturnValue(new Promise(() => {})); // never resolves
    const { result, cached } = setup();

    await act(async () => {
      result.current.mutate({ ticket: ticket(), toStatus: 'IN_PROGRESS' });
    });

    await waitFor(() => expect(cached()?.items[0].status).toBe('IN_PROGRESS'));
  });

  it('rolls back and toasts on error', async () => {
    vi.mocked(transitionTicket).mockRejectedValue(new Error('network'));
    const { result, cached } = setup();

    await act(async () => {
      result.current.mutate({ ticket: ticket(), toStatus: 'IN_PROGRESS' });
    });

    await waitFor(() => expect(cached()?.items[0].status).toBe('TODO')); // restored
    expect(toast.error).toHaveBeenCalledTimes(1);
  });

  it('calls transitionTicket with the ticket UUID, not the display key', async () => {
    vi.mocked(transitionTicket).mockResolvedValue(ticket());
    const { result } = setup();

    await act(async () => {
      result.current.mutate({ ticket: ticket(), toStatus: 'DONE' });
    });

    await waitFor(() => expect(transitionTicket).toHaveBeenCalledWith('uuid-1', 'DONE'));
  });

  it('awaits cancelQueries(boardKey) before patching, and invalidates board + activity on settle', async () => {
    vi.mocked(transitionTicket).mockResolvedValue(ticket());
    const { qc, result } = setup();
    const cancelSpy = vi.spyOn(qc, 'cancelQueries');
    const invalidateSpy = vi.spyOn(qc, 'invalidateQueries');

    await act(async () => {
      result.current.mutate({ ticket: ticket(), toStatus: 'IN_PROGRESS' });
    });

    await waitFor(() => expect(transitionTicket).toHaveBeenCalled());
    expect(cancelSpy).toHaveBeenCalledWith({ queryKey: boardKey(PROJECT, FILTERS) });
    await waitFor(() => {
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: boardKey(PROJECT, FILTERS) });
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ticketKeys.activity('ENG-1') });
    });
  });
});
