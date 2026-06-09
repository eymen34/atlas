import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Outlet, Route, Routes } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Project } from '@/api/projects';
import { listMembers } from '@/api/projects';
import type { Ticket, TicketPage } from '@/api/tickets';
import { listLabels, listTickets } from '@/api/tickets';
import { ListPage } from '@/pages/project/ListPage';
import { renderWithProviders } from '@/test/test-utils';

vi.mock('@/api/tickets', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/tickets')>();
  return { ...actual, listTickets: vi.fn(), listLabels: vi.fn(), createTicket: vi.fn() };
});
vi.mock('@/api/projects', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/projects')>();
  return { ...actual, listMembers: vi.fn() };
});

const listTicketsMock = vi.mocked(listTickets);
const listLabelsMock = vi.mocked(listLabels);
const listMembersMock = vi.mocked(listMembers);

const PROJECT: Project = {
  id: 'p-uuid',
  key: 'ALPHA',
  name: 'Alpha',
  description: '',
  createdBy: 'me',
  createdAt: '',
  updatedAt: '',
  callerRole: 'ADMIN',
  memberCount: 1,
};

function ticket(n: number, overrides: Partial<Ticket> = {}): Ticket {
  return {
    id: `t-${n}`,
    key: `ALPHA-${n}`,
    title: `Ticket ${n}`,
    status: 'TODO',
    priority: 'P2',
    reporterId: 'me',
    labelIds: [],
    createdAt: '2026-06-01T00:00:00Z',
    updatedAt: '2026-06-01T00:00:00Z',
    projectId: 'p-uuid',
    number: n,
    ...overrides,
  };
}

function page(items: Ticket[], extra: Partial<TicketPage> = {}): TicketPage {
  return { items, page: 0, size: 25, total: items.length, ...extra };
}

function renderList(initialEntries: string[]) {
  return renderWithProviders(
    <Routes>
      <Route path="/projects/:projectIdOrKey" element={<Outlet context={{ project: PROJECT }} />}>
        <Route path="list" element={<ListPage />} />
      </Route>
    </Routes>,
    { initialEntries }
  );
}

beforeEach(() => {
  listTicketsMock.mockReset();
  listLabelsMock.mockReset().mockResolvedValue([]);
  listMembersMock.mockReset().mockResolvedValue([]);
});

describe('ListPage', () => {
  it('AC-1.1: renders a table with 7 column headers and one row per ticket', async () => {
    listTicketsMock.mockResolvedValue(page([ticket(1), ticket(2), ticket(3), ticket(4)]));

    renderList(['/projects/ALPHA/list']);

    const table = await screen.findByRole('table');
    expect(within(table).getAllByRole('columnheader')).toHaveLength(7);
    // 1 header row + 4 ticket rows.
    expect(within(table).getAllByRole('row')).toHaveLength(5);
    expect(screen.getByText('ALPHA-1')).toBeInTheDocument();
    expect(screen.getByText('ALPHA-4')).toBeInTheDocument();
  });

  it('AC-2.5: passes filters parsed from the URL straight to listTickets', async () => {
    listTicketsMock.mockResolvedValue(page([]));

    renderList(['/projects/ALPHA/list?status=IN_PROGRESS&priority=P0&assigneeId=unassigned&page=2&size=50']);

    await waitFor(() =>
      expect(listTicketsMock).toHaveBeenCalledWith('p-uuid', {
        status: ['IN_PROGRESS'],
        priority: ['P0'],
        assigneeId: 'unassigned',
        label: undefined,
        page: 2,
        size: 50,
      })
    );
    // The active status filter is reflected on the trigger.
    expect(screen.getByRole('button', { name: /Status \(1\)/ })).toBeInTheDocument();
  });

  it('EC-5: changing a filter resets the page to 0', async () => {
    listTicketsMock.mockResolvedValue(page([]));
    const user = userEvent.setup();

    renderList(['/projects/ALPHA/list?status=IN_PROGRESS&page=3']);
    await waitFor(() => expect(listTicketsMock).toHaveBeenCalled());
    expect(listTicketsMock.mock.calls[0][1].page).toBe(3);

    await user.click(screen.getByRole('button', { name: /Status/ }));
    await user.click(await screen.findByRole('menuitemcheckbox', { name: 'TODO' }));

    await waitFor(() => {
      const last = listTicketsMock.mock.calls.at(-1)?.[1];
      expect(last?.page).toBe(0);
      expect(last?.status).toEqual(['IN_PROGRESS', 'TODO']);
    });
  });
});
