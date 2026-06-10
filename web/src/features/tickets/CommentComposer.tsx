import { EditorContent, useEditor } from '@tiptap/react';
import StarterKit from '@tiptap/starter-kit';
import { useState } from 'react';
import type { Member } from '@/api/projects';
import { Button } from '@/components/ui/button';
import { createMentionSuggestion, mentionExtension } from './mentionConfig';

export interface CommentComposerProps {
  members: Member[];
  /** Persists the composed HTML; resolves when the create completes. */
  onSubmit: (html: string) => Promise<unknown>;
  submitting?: boolean;
}

/**
 * Comment composer (T-022). TipTap StarterKit + the shared mention node, with an
 * @mention autocomplete over the project's members. DEFAULT export so it is
 * React.lazy-loaded into its own chunk. The created HTML is handed to {@code
 * onSubmit}; the SERVER re-derives the canonical mention set (D4).
 */
export default function CommentComposer({
  members,
  onSubmit,
  submitting = false,
}: CommentComposerProps) {
  const [busy, setBusy] = useState(false);
  const editor = useEditor({
    extensions: [
      StarterKit,
      mentionExtension.configure({ suggestion: createMentionSuggestion(members) }),
    ],
    content: '',
    immediatelyRender: false,
    editorProps: {
      attributes: {
        'aria-label': 'Add a comment',
        class:
          'min-h-20 w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50',
      },
    },
  });

  async function submit() {
    if (!editor || editor.isEmpty || busy) return;
    const html = editor.getHTML();
    setBusy(true);
    try {
      await onSubmit(html);
      editor.commands.clearContent();
    } finally {
      setBusy(false);
    }
  }

  const disabled = submitting || busy || !editor;

  return (
    <div data-testid="comment-composer" className="space-y-2">
      <EditorContent editor={editor} />
      <div className="flex justify-end">
        <Button size="sm" onClick={submit} disabled={disabled}>
          Comment
        </Button>
      </div>
    </div>
  );
}
