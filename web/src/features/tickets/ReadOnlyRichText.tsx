import { EditorContent, useEditor } from '@tiptap/react';
import StarterKit from '@tiptap/starter-kit';
import { mentionExtension } from './mentionConfig';

export interface ReadOnlyRichTextProps {
  /**
   * Stored HTML to render. Parsed through the StarterKit schema (never injected as
   * raw HTML), so scripts / inline handlers are dropped on parse — the read-side
   * XSS guard. Recreating on change keeps read mode in sync after an edit.
   */
  html: string;
  /** Include the shared mention node (comment bodies). Descriptions don't need it. */
  mentions?: boolean;
}

/**
 * Read-only rich-text renderer (T-046). A single TipTap {@code EditorContent} with
 * {@code editable:false} + StarterKit, DEFAULT-exported so it is {@code React.lazy}
 * -loaded into ONE shared chunk reused by every read-only site (ticket description
 * and comment bodies) — mirroring the T-021 edit-editor split. NEVER
 * dangerouslySetInnerHTML: TipTap parses the HTML through its schema, which is what
 * strips {@code script}/{@code onerror}/etc.
 */
export default function ReadOnlyRichText({ html, mentions = false }: ReadOnlyRichTextProps) {
  const editor = useEditor(
    {
      extensions: mentions ? [StarterKit, mentionExtension] : [StarterKit],
      content: html || '<p></p>',
      editable: false,
      immediatelyRender: false,
    },
    [html, mentions]
  );

  return <EditorContent editor={editor} />;
}
