import { EditorContent, useEditor } from '@tiptap/react';
import StarterKit from '@tiptap/starter-kit';
import { lazy, Suspense, useState } from 'react';
import type { Member } from '@/api/projects';
import type { Comment } from '@/api/tickets';
import { Avatar, AvatarFallback } from '@/components/ui/avatar';
import { Button } from '@/components/ui/button';
import { useActorLookup } from '@/hooks/useActorLookup';
import { formatRelativeTime } from '@/lib/relativeTime';
import { mentionExtension } from './mentionConfig';

// Read-only body renderer is code-split (T-046) into the shared TipTap-backed chunk
// reused with the ticket description. The inline edit editor below stays eager.
const ReadOnlyRichText = lazy(() => import('./ReadOnlyRichText'));

export interface CommentItemProps {
  comment: Comment;
  members: Member[];
  currentUserId: string | null;
  /** True when the caller is a project ADMIN (may moderate any comment). */
  canModerate: boolean;
  onEdit: (id: string, html: string) => Promise<unknown>;
  onDelete: (id: string) => Promise<unknown>;
}

function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return '?';
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

/** Inline editable body (mounted only while editing → its own editor lifecycle). */
function CommentEditor({
  initialHtml,
  onSave,
  onCancel,
  saving,
}: {
  initialHtml: string;
  onSave: (html: string) => void;
  onCancel: () => void;
  saving: boolean;
}) {
  const editor = useEditor({
    extensions: [StarterKit, mentionExtension],
    content: initialHtml || '<p></p>',
    immediatelyRender: false,
    editorProps: {
      attributes: {
        'aria-label': 'Edit comment',
        class:
          'min-h-16 w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50',
      },
    },
  });
  return (
    <div data-testid="comment-editor" className="space-y-2">
      <EditorContent editor={editor} />
      <div className="flex justify-end gap-2">
        <Button variant="outline" size="sm" onClick={onCancel} disabled={saving}>
          Cancel
        </Button>
        <Button
          size="sm"
          disabled={saving || !editor || editor.isEmpty}
          onClick={() => editor && onSave(editor.getHTML())}
        >
          Save
        </Button>
      </div>
    </div>
  );
}

/**
 * One comment row (T-022). Renders the HTML body READ-ONLY through TipTap
 * (editable:false + the shared mention node — never dangerouslySetInnerHTML, so
 * scripts / inline handlers in stored HTML neither execute nor survive). A deleted
 * comment shows a "[deleted]" placeholder. Edit/Delete affordances appear only for
 * the author or a project admin.
 */
export function CommentItem({
  comment,
  members,
  currentUserId,
  canModerate,
  onEdit,
  onDelete,
}: CommentItemProps) {
  const lookup = useActorLookup(members);
  const [editing, setEditing] = useState(false);
  const [busy, setBusy] = useState(false);

  if (comment.deleted) {
    return (
      <li data-testid="comment-item" className="text-sm text-muted-foreground italic">
        [deleted]
      </li>
    );
  }

  const actor = lookup(comment.authorId);
  const canModify = comment.authorId === currentUserId || canModerate;

  return (
    <li data-testid="comment-item" className="flex items-start gap-3">
      <Avatar size="sm" className="mt-0.5">
        <AvatarFallback>{initials(actor.name)}</AvatarFallback>
      </Avatar>
      <div className="min-w-0 flex-1 space-y-1">
        <div className="flex items-center gap-2 text-sm">
          <span className="font-medium">{actor.name}</span>
          <time className="text-xs text-muted-foreground" dateTime={comment.createdAt}>
            {formatRelativeTime(comment.createdAt)}
          </time>
        </div>

        {editing ? (
          <CommentEditor
            initialHtml={comment.body ?? ''}
            saving={busy}
            onCancel={() => setEditing(false)}
            onSave={(html) => {
              setBusy(true);
              void onEdit(comment.id, html)
                .then(() => setEditing(false))
                .finally(() => setBusy(false));
            }}
          />
        ) : (
          <>
            <div
              data-testid="comment-body"
              className="prose prose-sm dark:prose-invert max-w-none text-sm"
            >
              <Suspense
                fallback={
                  <div
                    data-testid="comment-body-fallback"
                    className="h-4 w-2/3 animate-pulse rounded bg-muted"
                    aria-hidden
                  />
                }
              >
                <ReadOnlyRichText html={comment.body ?? ''} mentions />
              </Suspense>
            </div>
            {canModify && (
              <div className="flex gap-2">
                <Button variant="ghost" size="sm" onClick={() => setEditing(true)}>
                  Edit
                </Button>
                <Button
                  variant="ghost"
                  size="sm"
                  disabled={busy}
                  onClick={() => {
                    setBusy(true);
                    void onDelete(comment.id).finally(() => setBusy(false));
                  }}
                >
                  Delete
                </Button>
              </div>
            )}
          </>
        )}
      </div>
    </li>
  );
}
