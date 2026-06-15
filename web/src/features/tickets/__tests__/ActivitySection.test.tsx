import { fireEvent, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ActivityEvent, ActivityPage } from '@/api/tickets';
import { listTicketActivity } from '@/api/tickets';
import { ActivitySection } from '../ActivitySection';
import { renderWithProviders } from '@/test/test-utils';
import { MEMBERS_TWO } from './fixtures';

vi.mock('@/api/tickets', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/tickets')>();
  return { ...actual, listTicketActivity: vi.fn() };
});

const listActivityMock = vi.mocked(listTicketActivity);

const TICKET_ID = 't-uuid';
const ID_OR_KEY = 'ENG-1';

// All rows share one eventType (so they share one row testid we can count) but
// each carries a UNIQUE id — disjoint per page — so counting rendered rows is a
// faithful no-duplication assertion: a duplicated append would inflate the count.
function evt(id: string): ActivityEvent {
  return {
    id,
    ticketId: TICKET_ID,
    eventType: 'STATUS_CHANGED',
    actorId: 'u1',
    createdAt: '2026-06-01T10:00:00.000Z',
    payload: { from: 'TODO', to: 'IN_PROGRESS' },
  };
}

/** ids[offset..offset+n) → a page envelope with the given total. */
function page(n: number, offset: number, total: number): ActivityPage {
  const items = Array.from({ length: n }, (_, i) => evt(`a${offset + i}`));
  const pageIndex = Math.floor(offset / 20);
  return { items, page: pageIndex, size: 20, total };
}

function rows() {
  return screen.queryAllByTestId('activity-event-STATUS_CHANGED');
}

function loadMore() {
  return screen.queryByTestId('activity-load-more');
}

function renderSection() {
  return renderWithProviders(
    <ActivitySection idOrKey={ID_OR_KEY} ticketId={TICKET_ID} members={MEMBERS_TWO} />
  );
}

beforeEach(() => {
  listActivityMock.mockReset();
});

describe('ActivitySection (T-045 load-more)', () => {
  it('appends successive pages without duplication and hides the control when all rows are loaded', async () => {
    // page 0: rows a0..a19 (total 45) · page 1: a20..a39 · page 2: a40..a44
    listActivityMock.mockImplementation((_ticketId, pageParam = 0) => {
      if (pageParam === 0) return Promise.resolve(page(20, 0, 45));
      if (pageParam === 1) return Promise.resolve(page(20, 20, 45));
      return Promise.resolve(page(5, 40, 45));
    });

    renderSection();

    // First page: 20 rows + a visible "Load more".
    await waitFor(() => expect(rows()).toHaveLength(20));
    expect(loadMore()).toBeInTheDocument();

    // Second page appends → 40 rows, control still visible.
    fireEvent.click(loadMore()!);
    await waitFor(() => expect(rows()).toHaveLength(40));
    expect(loadMore()).toBeInTheDocument();

    // Final page appends → 45 rows, control GONE (loaded === total).
    fireEvent.click(loadMore()!);
    await waitFor(() => expect(rows()).toHaveLength(45));
    expect(loadMore()).toBeNull();

    // No over-fetch: exactly the three pages were requested, in order.
    expect(listActivityMock).toHaveBeenCalledTimes(3);
    expect(listActivityMock.mock.calls.map((c) => c[1])).toEqual([0, 1, 2]);
  });

  it('shows no load-more control when a single page holds every row', async () => {
    listActivityMock.mockResolvedValue(page(12, 0, 12));

    renderSection();

    await waitFor(() => expect(rows()).toHaveLength(12));
    expect(loadMore()).toBeNull();
    expect(listActivityMock).toHaveBeenCalledTimes(1);
  });

  it('renders the empty state and no control when there is no activity', async () => {
    listActivityMock.mockResolvedValue(page(0, 0, 0));

    renderSection();

    expect(await screen.findByTestId('activity-empty-state')).toBeInTheDocument();
    expect(rows()).toHaveLength(0);
    expect(loadMore()).toBeNull();
  });
});
