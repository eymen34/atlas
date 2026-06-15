import { screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/api/generated';
import type { Member } from '@/api/projects';
import { getUserSummary } from '@/api/users';
import { useActorLookup } from '@/hooks/useActorLookup';
import { renderWithProviders } from '@/test/test-utils';

// Mock only the network call; keep the real userKeys so the cache keying is exercised.
vi.mock('@/api/users', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/users')>();
  return { ...actual, getUserSummary: vi.fn() };
});

const getUserSummaryMock = vi.mocked(getUserSummary);

const DEPARTED = '11111111-1111-1111-1111-111111111111';

const MEMBERS: Member[] = [
  {
    userId: 'u1',
    email: 'alice@example.com',
    displayName: 'Alice',
    role: 'ADMIN',
    createdAt: '',
    mentionHandle: 'alice',
  },
];

/** A real ApiError so the hook's instanceof-based status check sees the 404. */
function apiError(status: number): ApiError {
  return new ApiError(
    { method: 'GET', url: '/api/users/{id}' } as never,
    { url: `/api/users/${DEPARTED}`, ok: false, status, statusText: 'err', body: {} } as never,
    'err'
  );
}

/** Renders one row per id, each showing the resolved actor name from the hook. */
function Harness({ ids, members = MEMBERS }: { ids: (string | null | undefined)[]; members?: Member[] }) {
  const lookup = useActorLookup(members);
  return (
    <ul>
      {ids.map((id, i) => (
        <li key={i} data-testid={`row-${i}`}>
          {lookup(id).name}
        </li>
      ))}
    </ul>
  );
}

beforeEach(() => {
  getUserSummaryMock.mockReset();
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('useActorLookup — departed-member fallback (T-044)', () => {
  it('resolves an actor who left the project to their displayName, never the raw UUID', async () => {
    getUserSummaryMock.mockResolvedValue({ id: DEPARTED, displayName: 'Carol' });

    renderWithProviders(<Harness ids={[DEPARTED]} />);

    expect(await screen.findByText('Carol')).toBeInTheDocument();
    expect(screen.queryByText(DEPARTED)).toBeNull();
    expect(getUserSummaryMock).toHaveBeenCalledWith(DEPARTED);
  });

  it('caches per id: many rows for one departed actor trigger a single request', async () => {
    getUserSummaryMock.mockResolvedValue({ id: DEPARTED, displayName: 'Carol' });

    renderWithProviders(<Harness ids={[DEPARTED, DEPARTED, DEPARTED]} />);

    expect(await screen.findAllByText('Carol')).toHaveLength(3);
    expect(getUserSummaryMock).toHaveBeenCalledTimes(1);
  });

  it('renders "Former member" (never the raw UUID) when the user row is gone (404)', async () => {
    getUserSummaryMock.mockRejectedValue(apiError(404));

    renderWithProviders(<Harness ids={[DEPARTED]} />);

    await vi.waitFor(() => expect(getUserSummaryMock).toHaveBeenCalledWith(DEPARTED));
    expect(await screen.findByText('Former member')).toBeInTheDocument();
    expect(screen.queryByText(DEPARTED)).toBeNull();
  });

  it('resolves members and System/Unknown locally, with no fallback request', () => {
    renderWithProviders(<Harness ids={['u1', null, undefined]} />);

    expect(screen.getByTestId('row-0')).toHaveTextContent('Alice');
    expect(screen.getByTestId('row-1')).toHaveTextContent('System');
    expect(screen.getByTestId('row-2')).toHaveTextContent('Unknown user');
    expect(getUserSummaryMock).not.toHaveBeenCalled();
  });
});
