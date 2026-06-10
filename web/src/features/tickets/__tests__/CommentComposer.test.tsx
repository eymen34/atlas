import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import CommentComposer from '../CommentComposer';
import { MEMBERS_TWO } from './fixtures';

// Mock TipTap so the composer is backed by a trivial fake editor (no real editor /
// suggestion popup needed to assert the submit contract).
vi.mock('@tiptap/react', () => ({
  useEditor: () => ({
    isEmpty: false,
    getHTML: () => '<p>hi @bob</p>',
    commands: { clearContent: vi.fn() },
  }),
  EditorContent: () => <div data-testid="editor-content" />,
}));

describe('CommentComposer', () => {
  it('submit hands the editor HTML to onSubmit', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    render(<CommentComposer members={MEMBERS_TWO} onSubmit={onSubmit} />);

    fireEvent.click(screen.getByRole('button', { name: 'Comment' }));

    await waitFor(() => expect(onSubmit).toHaveBeenCalledWith('<p>hi @bob</p>'));
    expect(onSubmit).toHaveBeenCalledTimes(1);
  });
});
