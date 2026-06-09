import { fireEvent, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Ticket } from '@/api/tickets';
import { createTicket, ticketKeys } from '@/api/tickets';
import { NewTicketDialog } from '@/pages/project/list/NewTicketDialog';
import { createTestQueryClient, renderWithProviders } from '@/test/test-utils';

// Per T-020: unit-test against the app wrapper (vi.mock), not MSW. importOriginal
// keeps the real ticketKeys / enums / types and only stubs the async calls.
vi.mock('@/api/tickets', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/tickets')>();
  return { ...actual, createTicket: vi.fn(), listTickets: vi.fn(), listLabels: vi.fn() };
});

// TipTap is heavy + contenteditable-based; swap it for a plain textarea that keeps
// the data-testid wrapper so the EC-15 Enter-guard still has something to match.
vi.mock('@/pages/project/list/TipTapDescriptionEditor', () => ({
  TipTapDescriptionEditor: ({
    value,
    onChange,
  }: {
    value: string;
    onChange: (v: string) => void;
  }) => (
    <div data-testid="description-editor">
      <textarea aria-label="Description" value={value} onChange={(e) => onChange(e.target.value)} />
    </div>
  ),
}));

const createTicketMock = vi.mocked(createTicket);

function ticket(overrides: Partial<Ticket> = {}): Ticket {
  return {
    id: 't-9',
    key: 'ALPHA-9',
    title: 'Created',
    status: 'TODO',
    priority: 'P2',
    reporterId: 'me',
    labelIds: [],
    createdAt: '',
    updatedAt: '',
    projectId: 'p-uuid',
    number: 9,
    ...overrides,
  };
}

function renderDialog(onOpenChange = vi.fn(), queryClient = createTestQueryClient()) {
  renderWithProviders(
    <NewTicketDialog
      open
      onOpenChange={onOpenChange}
      projectId="p-uuid"
      projectKey="ALPHA"
      members={[]}
    />,
    { queryClient }
  );
  return { onOpenChange, queryClient };
}

beforeEach(() => {
  createTicketMock.mockReset();
});

describe('NewTicketDialog', () => {
  it('AC-4.1: requires a title and does not submit when empty', async () => {
    renderDialog();
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));
    expect(await screen.findByText('Title is required')).toBeInTheDocument();
    expect(createTicketMock).not.toHaveBeenCalled();
  });

  it('AC-4.3: on success invalidates ticket lists, toasts the key, and closes', async () => {
    createTicketMock.mockResolvedValue(ticket({ key: 'ALPHA-42' }));
    const { onOpenChange, queryClient } = renderDialog();
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');

    fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'Fix the bug' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));

    await waitFor(() =>
      expect(createTicketMock).toHaveBeenCalledWith(
        'p-uuid',
        expect.objectContaining({ title: 'Fix the bug', priority: 'P2' })
      )
    );
    expect(await screen.findByText('ALPHA-42 created')).toBeInTheDocument();
    await waitFor(() =>
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ticketKeys.lists('p-uuid') })
    );
    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false));
  });

  it('EC-4b: omits assigneeId entirely when left unassigned (never sends the sentinel)', async () => {
    createTicketMock.mockResolvedValue(ticket());
    renderDialog();

    fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'No assignee' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));

    await waitFor(() => expect(createTicketMock).toHaveBeenCalled());
    const req = createTicketMock.mock.calls[0][1];
    expect('assigneeId' in req).toBe(false);
  });

  it('EC-15: Enter inside the description editor does not submit the form', async () => {
    createTicketMock.mockResolvedValue(ticket());
    const { onOpenChange } = renderDialog();

    fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'Has a title' } });
    // The description editor is a contenteditable region (textarea in this mock):
    // pressing Enter there inserts a newline, it never submits the surrounding form.
    fireEvent.keyDown(screen.getByLabelText('Description'), { key: 'Enter', code: 'Enter' });

    expect(createTicketMock).not.toHaveBeenCalled();
    expect(onOpenChange).not.toHaveBeenCalled();
  });
});
