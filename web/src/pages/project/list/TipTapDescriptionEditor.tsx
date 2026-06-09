import { EditorContent, useEditor } from '@tiptap/react';
import StarterKit from '@tiptap/starter-kit';

export interface TipTapDescriptionEditorProps {
  /** Initial content (the editor is otherwise uncontrolled; it remounts per dialog open). */
  value: string;
  /** Fires on every edit with the current HTML, or '' when the editor is empty. */
  onChange: (html: string) => void;
}

/**
 * Minimal rich-text description editor (TipTap StarterKit). Emits HTML — the
 * backend stores the description verbatim (T-017), and T-020 only writes it. A
 * markdown serializer (tiptap-markdown) is intentionally NOT added to avoid an
 * unvalidated dependency; a future ticket can swap the output format.
 *
 * <p>In unit tests this component is mocked with a plain textarea via its
 * {@code data-testid}.
 */
export function TipTapDescriptionEditor({ value, onChange }: TipTapDescriptionEditorProps) {
  const editor = useEditor({
    extensions: [StarterKit],
    content: value,
    immediatelyRender: false,
    editorProps: {
      attributes: {
        'aria-label': 'Description',
        class:
          'min-h-24 w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50',
      },
    },
    onUpdate: ({ editor: e }) => onChange(e.isEmpty ? '' : e.getHTML()),
  });

  return (
    <div data-testid="description-editor">
      <EditorContent editor={editor} />
    </div>
  );
}
