import { EditorContent, useEditor } from '@tiptap/react';
import StarterKit from '@tiptap/starter-kit';
import { Button } from '@/components/ui/button';

export interface TicketDescriptionEditorProps {
  /** Current description HTML (the editor's starting content). */
  initialHtml: string;
  /** Called with the new HTML when the user saves a CHANGED description. */
  onSave: (html: string) => void;
  /** Called when the user cancels, or saves without changes (discards edits). */
  onCancel: () => void;
  /** True while the save mutation is in flight (disables the buttons). */
  saving?: boolean;
}

/**
 * Edit-mode rich-text editor for a ticket description (TipTap StarterKit, HTML
 * output). DEFAULT export so it can be {@code React.lazy}-loaded — it ships as a
 * separate chunk and only downloads when the user actually edits.
 *
 * <p>Save is a no-op (just {@code onCancel}) when the HTML is unchanged, so an
 * accidental open→save doesn't write or push an activity row.
 */
export default function TicketDescriptionEditor({
  initialHtml,
  onSave,
  onCancel,
  saving = false,
}: TicketDescriptionEditorProps) {
  const editor = useEditor({
    extensions: [StarterKit],
    content: initialHtml || '<p></p>',
    immediatelyRender: false,
    editorProps: {
      attributes: {
        'aria-label': 'Edit description',
        class:
          'min-h-32 w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50',
      },
    },
  });

  function handleSave() {
    if (!editor) return;
    const html = editor.isEmpty ? '' : editor.getHTML();
    if (html === initialHtml) {
      onCancel();
      return;
    }
    onSave(html);
  }

  return (
    <div data-testid="ticket-description-editor" className="space-y-2">
      <EditorContent editor={editor} />
      <div className="flex justify-end gap-2">
        <Button type="button" variant="outline" size="sm" onClick={onCancel} disabled={saving}>
          Cancel
        </Button>
        <Button type="button" size="sm" onClick={handleSave} disabled={saving}>
          Save
        </Button>
      </div>
    </div>
  );
}
