import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Ticket } from '@/api/tickets';
import { renderWithProviders } from '@/test/test-utils';
import { AddLinkDialog } from '../AddLinkDialog';

vi.mock('@/api/links', async (importActual) => {
  const actual = await importActual<typeof import('@/api/links')>();
  return { ...actual, createTicketLink: vi.fn() };
});
vi.mock('@/api/tickets', async (importActual) => {
  const actual = await importActual<typeof import('@/api/tickets')>();
  return { ...actual, listTickets: vi.fn() };
});
vi.mock('@/api/errors', () => ({
  apiErrorStatus: vi.fn(),
  apiErrorMessage: vi.fn(() => 'Unknown ticket key'),
}));

import { createTicketLink } from '@/api/links';
import { apiErrorStatus } from '@/api/errors';
import { listTickets } from '@/api/tickets';

function ticket(over: Partial<Ticket> = {}): Ticket {
  return {
    id: 't2',
    key: 'ENG-2',
    title: 'Second ticket',
    status: 'TODO',
    priority: 'P2',
    reporterId: 'u1',
    labelIds: [],
    createdAt: '2026-06-12T10:00:00.000Z',
    updatedAt: '2026-06-12T10:00:00.000Z',
    projectId: 'p1',
    number: 2,
    ...over,
  };
}

function renderOpen() {
  return renderWithProviders(
    <AddLinkDialog open onClose={vi.fn()} ticketId="t1" idOrKey="ENG-1" projectId="p1" />
  );
}

afterEach(() => vi.clearAllMocks());

describe('AddLinkDialog', () => {
  it('shows a 409 conflict inline and keeps the dialog open', async () => {
    vi.mocked(listTickets).mockResolvedValue({ items: [ticket()], page: 0, size: 100, total: 1 });
    vi.mocked(apiErrorStatus).mockReturnValue(409);
    vi.mocked(createTicketLink).mockRejectedValue(new Error('conflict'));
    const user = userEvent.setup();
    renderOpen();

    await user.click(await screen.findByText('Second ticket')); // select via cmdk
    await user.click(await screen.findByTestId('add-link-submit'));

    expect(await screen.findByTestId('add-link-error')).toHaveTextContent(
      'A link already exists between these tickets'
    );
    expect(screen.getByTestId('add-link-submit')).toBeInTheDocument(); // still open
  });

  it('shows a 400 server message inline', async () => {
    vi.mocked(listTickets).mockResolvedValue({ items: [ticket()], page: 0, size: 100, total: 1 });
    vi.mocked(apiErrorStatus).mockReturnValue(400);
    vi.mocked(createTicketLink).mockRejectedValue(new Error('bad'));
    const user = userEvent.setup();
    renderOpen();

    await user.click(await screen.findByText('Second ticket'));
    await user.click(await screen.findByTestId('add-link-submit'));

    expect(await screen.findByTestId('add-link-error')).toHaveTextContent('Unknown ticket key');
  });

  it('submits the selected ticket key + relation on success', async () => {
    vi.mocked(listTickets).mockResolvedValue({ items: [ticket()], page: 0, size: 100, total: 1 });
    vi.mocked(createTicketLink).mockResolvedValue({
      id: 'l1',
      fromTicketId: 't1',
      toTicketId: 't2',
      relation: 'BLOCKS',
      targetTicketKey: 'ENG-2',
      targetTitle: 'Second ticket',
      targetStatus: 'TODO',
      targetDeleted: false,
      createdBy: 'u1',
      createdAt: '2026-06-12T10:00:00.000Z',
    });
    const user = userEvent.setup();
    renderOpen();

    await user.click(await screen.findByText('Second ticket'));
    await user.click(await screen.findByTestId('add-link-submit'));

    await waitFor(() =>
      expect(createTicketLink).toHaveBeenCalledWith('t1', 'ENG-2', 'BLOCKS')
    );
  });
});
