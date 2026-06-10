import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { lazy, Suspense } from 'react';
import { toast } from 'sonner';
import type { Member } from '@/api/projects';
import {
  commentKeys,
  createComment,
  deleteComment,
  listComments,
  ticketKeys,
  updateComment,
} from '@/api/tickets';
import { CommentItem } from './CommentItem';

const CommentComposer = lazy(() => import('./CommentComposer'));

const PAGE = 0;
const SIZE = 20;

export interface CommentsSectionProps {
  /** Ticket UUID — comment endpoints + cache key. */
  ticketId: string;
  /** Route identifier — used to invalidate the activity query (keyed by it). */
  idOrKey: string;
  members: Member[];
  currentUserId: string | null;
  isProjectAdmin: boolean;
}

/**
 * Ticket comment thread (T-022). Newest-first list + a lazy-loaded composer. All
 * mutations are PRAGMATIC (D6): on success invalidate the comment list AND the
 * activity query (each comment mutation writes an activity row) and toast; on
 * error toast. Optimistic state is intentionally avoided — a server edit
 * re-derives mentionedUserIds the client cannot predict.
 */
export function CommentsSection({
  ticketId,
  idOrKey,
  members,
  currentUserId,
  isProjectAdmin,
}: CommentsSectionProps) {
  const queryClient = useQueryClient();

  const query = useQuery({
    queryKey: commentKeys.list(ticketId, PAGE, SIZE),
    queryFn: () => listComments(ticketId, PAGE, SIZE),
    enabled: !!ticketId,
  });

  function invalidate() {
    void queryClient.invalidateQueries({ queryKey: ['tickets', ticketId, 'comments'] });
    void queryClient.invalidateQueries({ queryKey: ticketKeys.activity(idOrKey) });
  }

  const createMut = useMutation({
    mutationFn: (html: string) => createComment(ticketId, html),
    onSuccess: () => {
      invalidate();
      toast.success('Comment added');
    },
    onError: () => toast.error('Could not add the comment'),
  });

  const editMut = useMutation({
    mutationFn: (vars: { id: string; body: string }) => updateComment(vars.id, vars.body),
    onSuccess: () => {
      invalidate();
      toast.success('Comment updated');
    },
    onError: () => toast.error('Could not update the comment'),
  });

  const deleteMut = useMutation({
    mutationFn: (id: string) => deleteComment(id),
    onSuccess: () => {
      invalidate();
      toast.success('Comment deleted');
    },
    onError: () => toast.error('Could not delete the comment'),
  });

  const comments = query.data?.items ?? [];

  return (
    <section data-testid="comments-section" aria-label="Comments" className="space-y-4">
      <h2 className="text-sm font-medium text-muted-foreground">Comments</h2>

      <Suspense
        fallback={
          <div data-testid="composer-loading" className="text-sm text-muted-foreground">
            Loading composer…
          </div>
        }
      >
        <CommentComposer
          members={members}
          submitting={createMut.isPending}
          onSubmit={(html) => createMut.mutateAsync(html)}
        />
      </Suspense>

      {query.isError && (
        <p className="text-sm text-muted-foreground">Could not load comments.</p>
      )}
      {!query.isLoading && !query.isError && comments.length === 0 && (
        <p data-testid="comments-empty" className="text-sm text-muted-foreground">
          No comments yet.
        </p>
      )}

      <ul className="space-y-4">
        {comments.map((comment) => (
          <CommentItem
            key={comment.id}
            comment={comment}
            members={members}
            currentUserId={currentUserId}
            canModerate={isProjectAdmin}
            onEdit={(id, body) => editMut.mutateAsync({ id, body })}
            onDelete={(id) => deleteMut.mutateAsync(id)}
          />
        ))}
      </ul>
    </section>
  );
}
