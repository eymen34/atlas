import { screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { listWatchers } from '@/api/tickets';
import { usePublicConfig } from '@/hooks/usePublicConfig';
import { WatchToggle } from '../WatchToggle';
import { renderWithProviders } from '@/test/test-utils';

vi.mock('@/hooks/usePublicConfig', () => ({ usePublicConfig: vi.fn() }));
vi.mock('@/api/tickets', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/tickets')>();
  return { ...actual, listWatchers: vi.fn(), watchTicket: vi.fn(), unwatchTicket: vi.fn() };
});

const usePublicConfigMock = vi.mocked(usePublicConfig);
const listWatchersMock = vi.mocked(listWatchers);

beforeEach(() => {
  usePublicConfigMock.mockReset();
  listWatchersMock.mockReset().mockResolvedValue([]);
});

describe('WatchToggle', () => {
  it('AC-5.2 / EC-9: renders nothing and never fetches watchers when the flag is off', () => {
    usePublicConfigMock.mockReturnValue({
      config: { features: { watchers: false } },
      isLoading: false,
    });
    renderWithProviders(<WatchToggle ticketId="t1" members={[]} />);
    expect(screen.queryByTestId('watch-toggle')).toBeNull();
    expect(listWatchersMock).not.toHaveBeenCalled();
  });

  it('renders nothing while the public config is still loading', () => {
    usePublicConfigMock.mockReturnValue({ config: undefined, isLoading: true });
    renderWithProviders(<WatchToggle ticketId="t1" members={[]} />);
    expect(screen.queryByTestId('watch-toggle')).toBeNull();
    expect(listWatchersMock).not.toHaveBeenCalled();
  });

  it('renders the toggle with the watcher count when the flag is on', async () => {
    usePublicConfigMock.mockReturnValue({
      config: { features: { watchers: true } },
      isLoading: false,
    });
    listWatchersMock.mockResolvedValue(['u1', 'u2']);

    renderWithProviders(<WatchToggle ticketId="t1" members={[]} />);

    const button = await screen.findByTestId('watch-toggle');
    expect(button).toHaveTextContent('2');
    expect(button).toHaveAttribute('aria-label', 'Watch ticket'); // current user not watching
    expect(listWatchersMock).toHaveBeenCalledWith('t1');
  });
});
