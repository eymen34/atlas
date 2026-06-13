import { fireEvent, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { TicketLink } from '@/api/links';
import { renderWithProviders } from '@/test/test-utils';
import { LinksSection } from '../LinksSection';

vi.mock('@/api/links', async (importActual) => {
  const actual = await importActual<typeof import('@/api/links')>();
  return { ...actual, listTicketLinks: vi.fn(), deleteTicketLink: vi.fn() };
});

import { deleteTicketLink, listTicketLinks } from '@/api/links';

function link(over: Partial<TicketLink> = {}): TicketLink {
  return {
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
    ...over,
  };
}

function render(props?: Partial<Parameters<typeof LinksSection>[0]>) {
  return renderWithProviders(
    <LinksSection ticketId="t1" idOrKey="ENG-1" projectId="p1" projectKey="ENG" {...props} />
  );
}

afterEach(() => vi.clearAllMocks());

describe('LinksSection', () => {
  it('renders the empty state when there are no links', async () => {
    vi.mocked(listTicketLinks).mockResolvedValue([]);
    render();
    expect(await screen.findByTestId('links-empty')).toBeInTheDocument();
  });

  it('groups links into the five relation sections with anchor + status', async () => {
    vi.mocked(listTicketLinks).mockResolvedValue([
      link({ id: 'a', relation: 'BLOCKS', targetTicketKey: 'ENG-2' }),
      link({ id: 'b', relation: 'IS_BLOCKED_BY', targetTicketKey: 'ENG-3' }),
      link({ id: 'c', relation: 'DUPLICATES', targetTicketKey: 'ENG-4' }),
      link({ id: 'd', relation: 'IS_DUPLICATED_BY', targetTicketKey: 'ENG-5' }),
      link({
        id: 'e',
        relation: 'RELATES_TO',
        targetTicketKey: 'ENG-6',
        targetStatus: 'DONE',
        targetDeleted: true,
      }),
    ]);
    render();

    expect(await screen.findByText('Blocks')).toBeInTheDocument();
    expect(screen.getByText('Blocked by')).toBeInTheDocument();
    expect(screen.getByText('Duplicates')).toBeInTheDocument();
    expect(screen.getByText('Duplicated by')).toBeInTheDocument();
    expect(screen.getByText('Relates to')).toBeInTheDocument();

    const anchor = screen.getByText('ENG-2').closest('a');
    expect(anchor).toHaveAttribute('href', '/projects/ENG/tickets/ENG-2');

    // The soft-deleted target keeps its row with a "deleted" badge.
    expect(screen.getByText('deleted')).toBeInTheDocument();
    expect(screen.getByText('DONE')).toBeInTheDocument();
  });

  it('removes a link via the trash button (any member)', async () => {
    vi.mocked(listTicketLinks).mockResolvedValue([link({ id: 'l9', targetTicketKey: 'ENG-2' })]);
    vi.mocked(deleteTicketLink).mockResolvedValue(undefined);
    render();

    fireEvent.click(await screen.findByRole('button', { name: 'Remove link to ENG-2' }));
    await waitFor(() => expect(deleteTicketLink).toHaveBeenCalledWith('l9'));
  });
});
