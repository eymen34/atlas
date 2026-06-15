import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Comment } from '@/api/tickets';
import { deleteComment, listComments, ticketKeys } from '@/api/tickets';
import { CommentsSection } from '../CommentsSection';
import { createTestQueryClient, renderWithProviders } from '@/test/test-utils';
import { MEMBERS_TWO } from './fixtures';

vi.mock('@/api/tickets', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/tickets')>();
  return {
    ...actual,
    listComments: vi.fn(),
    createComment: vi.fn(),
    updateComment: vi.fn(),
    deleteComment: vi.fn(),
  };
});
vi.mock('sonner', async (importOriginal) => {
  const actual = await importOriginal<typeof import('sonner')>();
  return { ...actual, toast: { success: vi.fn(), error: vi.fn() } };
});
// Stub the lazy composer (TipTap-heavy) — this suite is about the list + mutations.
vi.mock('../CommentComposer', () => ({ default: () => <div data-testid="composer-stub" /> }));

const listCommentsMock = vi.mocked(listComments);
const deleteCommentMock = vi.mocked(deleteComment);

const TICKET_ID = 't-uuid';
const ID_OR_KEY = 'ENG-1';

function comment(over: Partial<Comment>): Comment {
  return {
    id: 'c',
    ticketId: TICKET_ID,
    authorId: 'u1',
    body: '<p>body</p>',
    deleted: false,
    mentionedUserIds: [],
    createdAt: '2026-06-01T10:00:00.000Z',
    updatedAt: '2026-06-01T10:00:00.000Z',
    ...over,
  };
}

// Server returns newest-first.
const NEWEST = comment({ id: 'c3', authorId: 'u1', body: '<p>mine newest</p>' });
const DELETED = comment({ id: 'c2', authorId: 'u2', body: null, deleted: true });
const OTHERS = comment({ id: 'c1', authorId: 'u2', body: '<p>by bob</p>' });

function renderSection(currentUserId = 'u1', isProjectAdmin = false) {
  const queryClient = createTestQueryClient();
  renderWithProviders(
    <CommentsSection
      ticketId={TICKET_ID}
      idOrKey={ID_OR_KEY}
      members={MEMBERS_TWO}
      currentUserId={currentUserId}
      isProjectAdmin={isProjectAdmin}
    />,
    { queryClient }
  );
  return queryClient;
}

beforeEach(() => {
  listCommentsMock
    .mockReset()
    .mockResolvedValue({ items: [NEWEST, DELETED, OTHERS], page: 0, size: 20, total: 3 });
  deleteCommentMock.mockReset().mockResolvedValue(undefined);
});

describe('CommentsSection', () => {
  it('renders comments newest-first with a redacted placeholder for deleted ones', async () => {
    renderSection();
    const items = await screen.findAllByTestId('comment-item');
    expect(items).toHaveLength(3);
    // The body now renders via the lazy read-only renderer (T-046), so await it.
    await waitFor(() => expect(items[0]).toHaveTextContent('mine newest')); // newest-first preserved
    expect(items[1]).toHaveTextContent('[deleted]');
  });

  it('gates edit/delete to the author (current user u1 owns only the first)', async () => {
    renderSection('u1', false);
    const items = await screen.findAllByTestId('comment-item');
    expect(within(items[0]).queryByRole('button', { name: 'Delete' })).toBeInTheDocument();
    // c1 (by bob) → no affordance for u1 (not admin).
    expect(within(items[2]).queryByRole('button', { name: 'Delete' })).toBeNull();
  });

  it('delete invalidates BOTH the comment list and the activity query', async () => {
    const queryClient = renderSection('u1', false);
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
    const items = await screen.findAllByTestId('comment-item');

    fireEvent.click(within(items[0]).getByRole('button', { name: 'Delete' }));

    await waitFor(() => expect(deleteCommentMock).toHaveBeenCalledWith('c3'));
    await waitFor(() =>
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['tickets', TICKET_ID, 'comments'] })
    );
    await waitFor(() =>
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ticketKeys.activity(ID_OR_KEY) })
    );
  });
});
