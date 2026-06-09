import { render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { TicketActivityTimeline } from '../TicketActivityTimeline';
import { ACTIVITY_MIXED, ACTIVITY_UNKNOWN, MEMBERS_TWO } from './fixtures';

afterEach(() => {
  vi.restoreAllMocks();
});

describe('TicketActivityTimeline', () => {
  it('AC5: renders each event with an icon, resolved actor, summary, and timestamp', () => {
    render(<TicketActivityTimeline events={ACTIVITY_MIXED} members={MEMBERS_TWO} />);

    const timeline = screen.getByTestId('ticket-activity-timeline');
    expect(timeline).toHaveAttribute('aria-label', 'Activity timeline');

    // One row per known event type, each with an icon + timestamp.
    expect(screen.getByTestId('activity-event-CREATED')).toBeInTheDocument();
    expect(screen.getByTestId('activity-event-STATUS_CHANGED')).toBeInTheDocument();
    expect(screen.getAllByTestId('event-icon')).toHaveLength(ACTIVITY_MIXED.length);
    expect(screen.getAllByTestId('event-timestamp')).toHaveLength(ACTIVITY_MIXED.length);

    // Actor resolution: member name, System (null actor), Unknown user (orphan id).
    const statusRow = screen.getByTestId('activity-event-STATUS_CHANGED');
    expect(within(statusRow).getByText('Bob')).toBeInTheDocument();
    expect(within(statusRow).getByText('changed status from TODO to IN_PROGRESS')).toBeInTheDocument();

    const labelsRow = screen.getByTestId('activity-event-LABELS_CHANGED');
    expect(within(labelsRow).getByText('System')).toBeInTheDocument(); // null actor

    const priorityRow = screen.getByTestId('activity-event-PRIORITY_CHANGED');
    expect(within(priorityRow).getByText('Unknown user')).toBeInTheDocument(); // orphan actor
  });

  it('EC: assignee summary resolves the target UUID to a name (never a raw id)', () => {
    render(<TicketActivityTimeline events={ACTIVITY_MIXED} members={MEMBERS_TWO} />);
    const row = screen.getByTestId('activity-event-ASSIGNEE_CHANGED');
    expect(within(row).getByText('assigned this ticket to Bob')).toBeInTheDocument();
    // The raw assignee UUID must not leak into the DOM.
    expect(row.textContent).not.toContain('u2');
  });

  it('AC-5.2: an unknown event type renders a safe fallback without crashing', () => {
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
    render(<TicketActivityTimeline events={ACTIVITY_UNKNOWN} members={MEMBERS_TWO} />);

    const row = screen.getByTestId('activity-event-COMPLETELY_UNKNOWN_EVENT_XYZ_9999');
    expect(row).toBeInTheDocument();
    expect(within(row).getByText('updated this ticket')).toBeInTheDocument();
    expect(errorSpy).not.toHaveBeenCalled();
  });

  it('renders the empty state when there is no activity', () => {
    render(<TicketActivityTimeline events={[]} members={MEMBERS_TWO} />);
    expect(screen.getByTestId('activity-empty-state')).toHaveTextContent('No activity yet.');
  });
});
