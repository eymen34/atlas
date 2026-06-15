import { screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/api/generated';
import { getUserSummary } from '@/api/users';
import { renderWithProviders } from '@/test/test-utils';
import { TicketActivityTimeline } from '../TicketActivityTimeline';
import { ACTIVITY_MIXED, ACTIVITY_UNKNOWN, MEMBERS_TWO } from './fixtures';

// T-044: a non-member actor (the `ghost-uuid` orphan below) now resolves via the
// backend fallback. It is a fabricated id, so the lookup 404s → "Former member".
vi.mock('@/api/users', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/users')>();
  return { ...actual, getUserSummary: vi.fn() };
});

beforeEach(() => {
  vi.mocked(getUserSummary).mockReset().mockRejectedValue(
    new ApiError(
      { method: 'GET', url: '/api/users/{id}' } as never,
      { url: '/api/users/ghost-uuid', ok: false, status: 404, statusText: 'Not Found', body: {} } as never,
      'Not Found'
    )
  );
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('TicketActivityTimeline', () => {
  it('AC5: renders each event with an icon, resolved actor, summary, and timestamp', async () => {
    renderWithProviders(<TicketActivityTimeline events={ACTIVITY_MIXED} members={MEMBERS_TWO} />);

    const timeline = screen.getByTestId('ticket-activity-timeline');
    expect(timeline).toHaveAttribute('aria-label', 'Activity timeline');

    // One row per known event type, each with an icon + timestamp.
    expect(screen.getByTestId('activity-event-CREATED')).toBeInTheDocument();
    expect(screen.getByTestId('activity-event-STATUS_CHANGED')).toBeInTheDocument();
    expect(screen.getAllByTestId('event-icon')).toHaveLength(ACTIVITY_MIXED.length);
    expect(screen.getAllByTestId('event-timestamp')).toHaveLength(ACTIVITY_MIXED.length);

    // Actor resolution: member name, System (null actor), and the departed-member
    // fallback (T-044) — an id absent from the member list resolves via the backend,
    // here a fabricated id that 404s → "Former member" (never the raw UUID).
    const statusRow = screen.getByTestId('activity-event-STATUS_CHANGED');
    expect(within(statusRow).getByText('Bob')).toBeInTheDocument();
    expect(within(statusRow).getByText('changed status from TODO to IN_PROGRESS')).toBeInTheDocument();

    const labelsRow = screen.getByTestId('activity-event-LABELS_CHANGED');
    expect(within(labelsRow).getByText('System')).toBeInTheDocument(); // null actor

    const priorityRow = screen.getByTestId('activity-event-PRIORITY_CHANGED');
    expect(await within(priorityRow).findByText('Former member')).toBeInTheDocument(); // departed actor
    expect(priorityRow.textContent).not.toContain('ghost-uuid'); // raw UUID never leaks
  });

  it('EC: assignee summary resolves the target UUID to a name (never a raw id)', () => {
    renderWithProviders(<TicketActivityTimeline events={ACTIVITY_MIXED} members={MEMBERS_TWO} />);
    const row = screen.getByTestId('activity-event-ASSIGNEE_CHANGED');
    expect(within(row).getByText('assigned this ticket to Bob')).toBeInTheDocument();
    // The raw assignee UUID must not leak into the DOM.
    expect(row.textContent).not.toContain('u2');
  });

  it('AC-5.2: an unknown event type renders a safe fallback without crashing', () => {
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
    renderWithProviders(<TicketActivityTimeline events={ACTIVITY_UNKNOWN} members={MEMBERS_TWO} />);

    const row = screen.getByTestId('activity-event-COMPLETELY_UNKNOWN_EVENT_XYZ_9999');
    expect(row).toBeInTheDocument();
    expect(within(row).getByText('updated this ticket')).toBeInTheDocument();
    expect(errorSpy).not.toHaveBeenCalled();
  });

  it('renders the empty state when there is no activity', () => {
    renderWithProviders(<TicketActivityTimeline events={[]} members={MEMBERS_TWO} />);
    expect(screen.getByTestId('activity-empty-state')).toHaveTextContent('No activity yet.');
  });
});
