import { fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { useLocation } from 'react-router';
import { describe, expect, it } from 'vitest';
import { server } from '@/test/msw/server';
import { renderWithProviders } from '@/test/test-utils';
import { GlobalSearchDialog } from '../GlobalSearchDialog';

/** Echoes the current path so we can assert result-click navigation. */
function LocationProbe() {
  const loc = useLocation();
  return <div data-testid="loc">{loc.pathname}</div>;
}

const HIT = {
  ticketId: 't-1',
  ticketKey: 'ENG-1',
  title: 'Authentication service',
  status: 'TODO',
  projectKey: 'ENG',
  projectId: 'p-1',
  snippet: 'the [[auth]] service handles login',
  updatedAt: '2026-06-10T10:00:00.000Z',
  rank: 0.9,
};

describe('GlobalSearchDialog', () => {
  it('opens on ⌘K / Ctrl+K', async () => {
    renderWithProviders(<GlobalSearchDialog />);
    expect(screen.queryByTestId('global-search-input')).toBeNull();

    fireEvent.keyDown(document, { key: 'k', metaKey: true });

    expect(await screen.findByTestId('global-search-input')).toBeInTheDocument();
  });

  it('debounces typing to a single backend request and shows ranked results', async () => {
    let calls = 0;
    server.use(
      http.get('/api/search/tickets', () => {
        calls += 1;
        return HttpResponse.json({ items: [HIT], page: 0, size: 20, total: 1 });
      })
    );
    const user = userEvent.setup();
    renderWithProviders(<GlobalSearchDialog />);

    await user.click(screen.getByTestId('global-search-trigger'));
    await user.type(await screen.findByTestId('global-search-input'), 'auth');

    // The flat ranked result row renders…
    expect(await screen.findByTestId('global-search-result')).toHaveTextContent('ENG-1');
    expect(screen.getByTestId('global-search-result')).toHaveTextContent('Authentication service');
    // …and the four keystrokes collapsed into exactly one request.
    await waitFor(() => expect(calls).toBe(1));
  });

  it('navigates to /projects/{projectKey}/tickets/{ticketKey} when a result is selected', async () => {
    server.use(
      http.get('/api/search/tickets', () =>
        HttpResponse.json({ items: [HIT], page: 0, size: 20, total: 1 })
      )
    );
    const user = userEvent.setup();
    renderWithProviders(
      <>
        <GlobalSearchDialog />
        <LocationProbe />
      </>
    );

    await user.click(screen.getByTestId('global-search-trigger'));
    await user.type(await screen.findByTestId('global-search-input'), 'auth');
    await user.click(await screen.findByTestId('global-search-result'));

    await waitFor(() =>
      expect(screen.getByTestId('loc')).toHaveTextContent('/projects/ENG/tickets/ENG-1')
    );
  });
});
