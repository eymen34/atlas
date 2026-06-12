import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { useLocation } from 'react-router';
import { describe, expect, it } from 'vitest';
import { renderWithProviders } from '@/test/test-utils';
import { server } from '@/test/msw/server';
import { NotificationBell } from '../NotificationBell';

/** Echoes the current path so we can assert row-click navigation. */
function LocationProbe() {
  const loc = useLocation();
  return <div data-testid="loc">{loc.pathname}</div>;
}

function listOf(count: number) {
  return Array.from({ length: count }, (_, i) => ({
    id: `n-${i}`,
    kind: 'ASSIGNED',
    ticketId: `t-${i}`,
    ticketKey: `ENG-${i + 1}`,
    ticketTitle: `Ticket ${i + 1}`,
    projectKey: 'ENG',
    actorId: 'a-1',
    actorDisplayName: 'Alice',
    read: false,
    createdAt: '2026-06-10T10:00:00.000Z',
  }));
}

/**
 * BLOCKING-1: the badge count comes from a SECOND query ({@code unread=true&size=1}
 * → total), independent of the list (which returns full rows). The two are wired to
 * different `total`s here to prove they don't share state.
 */
function useNotificationHandlers(badgeTotal: number, listCount: number) {
  server.use(
    http.get('/api/notifications', ({ request }) => {
      const url = new URL(request.url);
      if (url.searchParams.get('unread') === 'true') {
        return HttpResponse.json({ items: [], page: 0, size: 1, total: badgeTotal });
      }
      return HttpResponse.json({
        items: listOf(listCount),
        page: 0,
        size: 20,
        total: listCount,
      });
    }),
    http.post('/api/notifications/:id/read', () => new HttpResponse(null, { status: 204 }))
  );
}

describe('NotificationBell', () => {
  it('renders the unread badge from the count query (42), not the list size (20)', async () => {
    useNotificationHandlers(42, 20);
    renderWithProviders(<NotificationBell />);

    expect(await screen.findByTestId('notification-badge')).toHaveTextContent('42');
  });

  it('opens the panel and renders the full list (20 rows)', async () => {
    useNotificationHandlers(42, 20);
    const user = userEvent.setup();
    renderWithProviders(<NotificationBell />);

    await user.click(screen.getByRole('button', { name: 'Notifications' }));

    await waitFor(() =>
      expect(screen.getAllByTestId('notification-row')).toHaveLength(20)
    );
  });

  it('navigates to /projects/{projectKey}/tickets/{ticketKey} when a row is clicked', async () => {
    useNotificationHandlers(3, 5);
    const user = userEvent.setup();
    renderWithProviders(
      <>
        <NotificationBell />
        <LocationProbe />
      </>
    );

    await user.click(screen.getByRole('button', { name: 'Notifications' }));
    const rows = await screen.findAllByTestId('notification-row');
    // First row is ENG-1 in project ENG (verified route shape, override #5).
    await user.click(within(rows[0]).getByText('Alice assigned ENG-1 to you'));

    await waitFor(() =>
      expect(screen.getByTestId('loc')).toHaveTextContent('/projects/ENG/tickets/ENG-1')
    );
  });

  it('shows no badge when there are zero unread', async () => {
    useNotificationHandlers(0, 0);
    renderWithProviders(<NotificationBell />);

    // The bell renders, but the badge element is absent at zero unread.
    expect(await screen.findByRole('button', { name: 'Notifications' })).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByTestId('notification-badge')).toBeNull());
  });
});
