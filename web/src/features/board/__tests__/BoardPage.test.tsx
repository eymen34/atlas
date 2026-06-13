import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { MemoryRouter, Outlet, Route, Routes, useLocation } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/api/tickets', async (importActual) => {
  const actual = await importActual<typeof import('@/api/tickets')>();
  return { ...actual, boardListTickets: vi.fn(), listLabels: vi.fn() };
});
vi.mock('@/api/projects', async (importActual) => {
  const actual = await importActual<typeof import('@/api/projects')>();
  return { ...actual, listMembers: vi.fn() };
});

import { listMembers, type Project } from '@/api/projects';
import { boardListTickets, listLabels, type Ticket, type TicketPage } from '@/api/tickets';
import { BoardPage } from '@/pages/project/BoardPage';

declare global {
  var __xss: number | undefined;
}

const PROJECT: Project = {
  id: 'p1',
  key: 'ENG',
  name: 'Engineering',
  createdBy: 'u1',
  createdAt: '',
  updatedAt: '',
  callerRole: 'MEMBER',
  memberCount: 1,
};

let seq = 0;
function ticket(over: Partial<Ticket> = {}): Ticket {
  seq += 1;
  return {
    id: `id-${seq}`,
    key: `ENG-${seq}`,
    title: `Ticket ${seq}`,
    status: 'TODO',
    priority: 'P2',
    reporterId: 'u1',
    labelIds: [],
    createdAt: '',
    updatedAt: '',
    projectId: 'p1',
    number: seq,
    ...over,
  };
}
function page(items: Ticket[]): TicketPage {
  return { items, page: 0, size: 500, total: items.length };
}

function Loc() {
  const loc = useLocation();
  return <div data-testid="loc">{loc.pathname + loc.search}</div>;
}

function renderBoard(entry = '/projects/ENG/board') {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[entry]}>
        <Loc />
        <Routes>
          <Route
            path="/projects/:projectIdOrKey"
            element={<ProjectOutlet>{undefined}</ProjectOutlet>}
          >
            <Route path="board" element={<BoardPage />} />
            <Route path="list" element={<div data-testid="list-stub" />} />
          </Route>
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
}
function ProjectOutlet({ children }: { children?: ReactNode }) {
  return (
    <>
      {children}
      <Outlet context={{ project: PROJECT }} />
    </>
  );
}

afterEach(() => {
  vi.clearAllMocks();
  globalThis.__xss = undefined;
});

describe('BoardPage', () => {
  it('renders four columns with correct counts + grouping, and fetches without a status filter', async () => {
    vi.mocked(listMembers).mockResolvedValue([]);
    vi.mocked(listLabels).mockResolvedValue([]);
    vi.mocked(boardListTickets).mockResolvedValue(
      page([
        ticket({ status: 'TODO' }),
        ticket({ status: 'TODO' }),
        ticket({ status: 'TODO' }),
        ticket({ status: 'IN_PROGRESS' }),
        ticket({ status: 'IN_PROGRESS' }),
        ticket({ status: 'DONE' }),
      ])
    );

    renderBoard();

    await waitFor(() =>
      expect(screen.getByTestId('board-column-count-TODO')).toHaveTextContent('3')
    );
    expect(screen.getByTestId('board-column-count-IN_PROGRESS')).toHaveTextContent('2');
    expect(screen.getByTestId('board-column-count-IN_REVIEW')).toHaveTextContent('0');
    expect(screen.getByTestId('board-column-count-DONE')).toHaveTextContent('1');

    // Four columns, fixed order.
    ['TODO', 'IN_PROGRESS', 'IN_REVIEW', 'DONE'].forEach((s) =>
      expect(screen.getByTestId(`board-column-${s}`)).toBeInTheDocument()
    );

    // status was NOT passed to the fetch (it is the column axis, not a filter).
    const filtersArg = vi.mocked(boardListTickets).mock.calls[0][1];
    expect(filtersArg.status).toBeUndefined();
  });

  it('card link targets the display key, not the UUID (AC5)', async () => {
    vi.mocked(listMembers).mockResolvedValue([]);
    vi.mocked(listLabels).mockResolvedValue([]);
    vi.mocked(boardListTickets).mockResolvedValue(page([ticket({ key: 'ENG-77', status: 'TODO' })]));

    renderBoard();

    const link = await screen.findByText('ENG-77');
    expect(link.closest('a')).toHaveAttribute('href', '/projects/ENG/tickets/ENG-77');
  });

  it('escapes a malicious ticket title (SEC-1)', async () => {
    vi.mocked(listMembers).mockResolvedValue([]);
    vi.mocked(listLabels).mockResolvedValue([]);
    vi.mocked(boardListTickets).mockResolvedValue(
      page([ticket({ title: '<img src=x onerror="window.__xss=true">', status: 'TODO' })])
    );

    renderBoard();

    await waitFor(() => expect(screen.getByTestId('board-column-count-TODO')).toHaveTextContent('1'));
    expect(globalThis.__xss).toBeUndefined();
    expect(document.querySelector('img[src="x"]')).toBeNull();
  });

  it('strips a stale ?status from the board URL on mount (E2 / T8)', async () => {
    vi.mocked(listMembers).mockResolvedValue([]);
    vi.mocked(listLabels).mockResolvedValue([]);
    vi.mocked(boardListTickets).mockResolvedValue(page([]));

    renderBoard('/projects/ENG/board?status=DONE');

    await waitFor(() => expect(screen.getByTestId('loc')).not.toHaveTextContent('status'));
    expect(screen.getByTestId('board-column-TODO')).toBeInTheDocument();
  });

  it('shares non-status filters with the URL + preserves them on the List toggle (AC4)', async () => {
    vi.mocked(listMembers).mockResolvedValue([]);
    vi.mocked(listLabels).mockResolvedValue([]);
    vi.mocked(boardListTickets).mockResolvedValue(page([]));

    renderBoard('/projects/ENG/board?priority=P1&assigneeId=u1');

    await waitFor(() => expect(boardListTickets).toHaveBeenCalled());
    const filtersArg = vi.mocked(boardListTickets).mock.calls[0][1];
    expect(filtersArg.priority).toEqual(['P1']);
    expect(filtersArg.assigneeId).toBe('u1');
    expect(filtersArg.status).toBeUndefined();

    // The List toggle preserves the query string (and never adds status).
    const listLink = screen.getByRole('link', { name: 'List' });
    expect(listLink.getAttribute('href')).toContain('/projects/ENG/list');
    expect(listLink.getAttribute('href')).toContain('priority=P1');
    expect(listLink.getAttribute('href')).toContain('assigneeId=u1');
    expect(listLink.getAttribute('href')).not.toContain('status');
  });
});
