import { render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Comment } from '@/api/tickets';
import { CommentItem } from '../CommentItem';
import { MEMBERS_TWO } from './fixtures';

declare global {
  var __xss: number | undefined;
}

function comment(over: Partial<Comment> = {}): Comment {
  return {
    id: 'c1',
    ticketId: 't1',
    authorId: 'u1',
    body: '<p>hello</p>',
    deleted: false,
    mentionedUserIds: [],
    createdAt: '2026-06-01T10:00:00.000Z',
    updatedAt: '2026-06-01T10:00:00.000Z',
    ...over,
  };
}

function renderItem(c: Comment, currentUserId: string, canModerate = false) {
  return render(
    <ul>
      <CommentItem
        comment={c}
        members={MEMBERS_TWO}
        currentUserId={currentUserId}
        canModerate={canModerate}
        onEdit={vi.fn()}
        onDelete={vi.fn()}
      />
    </ul>
  );
}

beforeEach(() => {
  globalThis.__xss = undefined;
});

describe('CommentItem', () => {
  it('SEC-2: a script/onerror/onclick payload is neither executed nor present in the DOM', async () => {
    renderItem(
      comment({
        body:
          '<p>hi <img src=x onerror="window.__xss=1">' +
          '<span data-id="u2" data-label="bob" onclick="window.__xss=2">@bob</span></p>',
      }),
      'u9'
    );

    await waitFor(() => expect(document.querySelector('.ProseMirror')).not.toBeNull());
    expect(globalThis.__xss).toBeUndefined();
    expect(document.querySelector('[onerror]')).toBeNull();
    expect(document.querySelector('[onclick]')).toBeNull();
    expect(document.querySelector('.ProseMirror script')).toBeNull();
  });

  it('renders a [deleted] placeholder for a soft-deleted comment', () => {
    renderItem(comment({ deleted: true, body: null }), 'u1');
    expect(screen.getByTestId('comment-item')).toHaveTextContent('[deleted]');
  });

  it('shows edit/delete only to the author (or a moderator)', async () => {
    // Non-author, non-moderator → no affordances.
    const { unmount } = renderItem(comment({ authorId: 'u1' }), 'u9', false);
    await waitFor(() => expect(document.querySelector('.ProseMirror')).not.toBeNull());
    expect(screen.queryByRole('button', { name: 'Edit' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Delete' })).toBeNull();
    unmount();

    // Author → affordances present.
    renderItem(comment({ authorId: 'u1' }), 'u1', false);
    const item = await screen.findByTestId('comment-item');
    expect(within(item).getByRole('button', { name: 'Edit' })).toBeInTheDocument();
    expect(within(item).getByRole('button', { name: 'Delete' })).toBeInTheDocument();
  });

  it('shows affordances to a project admin on someone else’s comment', async () => {
    renderItem(comment({ authorId: 'u1' }), 'u9', true);
    const item = await screen.findByTestId('comment-item');
    expect(within(item).getByRole('button', { name: 'Delete' })).toBeInTheDocument();
  });
});
