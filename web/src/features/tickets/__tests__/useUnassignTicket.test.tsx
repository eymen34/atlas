// T-041: the unassignTicket wrapper + the optimistic useUnassignTicket mutation.
// We spy the GENERATED TicketsService.unassignTicket (not the wrapper), so the real
// wrapper runs — covering both the wrapper's DELETE-by-UUID + toTicket mapping AND the
// optimistic snapshot/rollback/invalidate, mirroring useTransitionTicketOptimistic.test.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { TicketsService } from '@/api/generated';
import { type Ticket, ticketKeys, unassignTicket } from '@/api/tickets';
import { toast } from 'sonner';
import { useUnassignTicket } from '../hooks';

vi.mock('sonner', () => ({ toast: { error: vi.fn(), success: vi.fn() } }));

const ID_OR_KEY = 'ENG-1';
const PROJECT = 'p1';

function ticket(): Ticket {
  return {
    id: 'uuid-1',
    key: 'ENG-1',
    title: 'First',
    status: 'TODO',
    priority: 'P2',
    assigneeId: 'u1',
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
  qc.setQueryData(ticketKeys.detail(ID_OR_KEY), ticket());
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={qc}>{children}</QueryClientProvider>
  );
  const { result } = renderHook(() => useUnassignTicket(ID_OR_KEY, ticket()), { wrapper });
  const cached = () => qc.getQueryData<Ticket>(ticketKeys.detail(ID_OR_KEY));
  return { qc, result, cached };
}

afterEach(() => vi.restoreAllMocks());

describe('unassignTicket wrapper', () => {
  it('DELETEs via the generated client (ticket UUID) and maps the cleared ticket', async () => {
    const spy = vi
      .spyOn(TicketsService, 'unassignTicket')
      .mockResolvedValue({ id: 'uuid-1', key: 'ENG-1', title: 'First', assigneeId: undefined } as never);

    const t = await unassignTicket('uuid-1');

    expect(spy).toHaveBeenCalledWith('uuid-1');
    expect(t.id).toBe('uuid-1');
    expect(t.assigneeId).toBeUndefined();
  });
});

describe('useUnassignTicket', () => {
  it('calls the DELETE with the ticket UUID, not the display key', async () => {
    const spy = vi
      .spyOn(TicketsService, 'unassignTicket')
      .mockResolvedValue({ ...ticket(), assigneeId: undefined } as never);
    const { result } = setup();

    await act(async () => {
      result.current.mutate();
    });

    await waitFor(() => expect(spy).toHaveBeenCalledWith('uuid-1'));
  });

  it('optimistically clears the cached assignee before the server responds', async () => {
    vi.spyOn(TicketsService, 'unassignTicket').mockReturnValue(new Promise(() => {}) as never);
    const { result, cached } = setup();

    await act(async () => {
      result.current.mutate();
    });

    await waitFor(() => expect(cached()?.assigneeId).toBeUndefined());
  });

  it('rolls back the assignee and toasts on error', async () => {
    vi.spyOn(TicketsService, 'unassignTicket').mockRejectedValue(new Error('network'));
    const { result, cached } = setup();

    await act(async () => {
      result.current.mutate();
    });

    await waitFor(() => expect(cached()?.assigneeId).toBe('u1')); // restored
    expect(toast.error).toHaveBeenCalledTimes(1);
  });

  it('awaits cancelQueries(detail) before patching, and invalidates detail+activity+lists on settle', async () => {
    vi.spyOn(TicketsService, 'unassignTicket').mockResolvedValue({
      ...ticket(),
      assigneeId: undefined,
    } as never);
    const { qc, result } = setup();
    const cancelSpy = vi.spyOn(qc, 'cancelQueries');
    const invalidateSpy = vi.spyOn(qc, 'invalidateQueries');

    await act(async () => {
      result.current.mutate();
    });

    expect(cancelSpy).toHaveBeenCalledWith({ queryKey: ticketKeys.detail(ID_OR_KEY) });
    await waitFor(() => {
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ticketKeys.detail(ID_OR_KEY) });
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ticketKeys.activity(ID_OR_KEY) });
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ticketKeys.lists(PROJECT) });
    });
  });
});
