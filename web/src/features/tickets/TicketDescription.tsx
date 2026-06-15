import { lazy, Suspense, useState } from 'react';
import type { Ticket } from '@/api/tickets';
import { Button } from '@/components/ui/button';
import { useUpdateTicket } from './hooks';

// Edit-mode editor is code-split: it only loads when the user clicks Edit, keeping
// TipTap's edit bundle out of the initial detail-page payload.
const TicketDescriptionEditor = lazy(() => import('./TicketDescriptionEditor'));
// Read-only renderer is likewise code-split (T-046): the same TipTap-backed chunk
// shared with comment bodies, so the read-only renderer isn't eagerly bundled.
const ReadOnlyRichText = lazy(() => import('./ReadOnlyRichText'));

export interface TicketDescriptionProps {
  idOrKey: string;
  ticket: Ticket;
}

/**
 * Ticket description: HTML rendered READ-ONLY through a TipTap editor with
 * {@code editable:false} (NOT dangerouslySetInnerHTML — TipTap parses the HTML
 * through its schema, dropping scripts and inline handlers). Clicking Edit lazy-
 * loads the full editor; a successful save returns to read mode.
 */
export function TicketDescription({ idOrKey, ticket }: TicketDescriptionProps) {
  const [editing, setEditing] = useState(false);
  const update = useUpdateTicket(idOrKey, ticket);
  const html = ticket.description ?? '';

  if (editing) {
    return (
      <section aria-label="Description" className="space-y-2">
        <h2 className="text-sm font-medium text-muted-foreground">Description</h2>
        <Suspense
          fallback={
            <div data-testid="description-loading-fallback" className="text-sm text-muted-foreground">
              Loading editor…
            </div>
          }
        >
          <TicketDescriptionEditor
            initialHtml={html}
            saving={update.isPending}
            onSave={(next) => update.mutate({ description: next }, { onSuccess: () => setEditing(false) })}
            onCancel={() => setEditing(false)}
          />
        </Suspense>
      </section>
    );
  }

  return (
    <section aria-label="Description" className="space-y-2">
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-medium text-muted-foreground">Description</h2>
        <Button variant="ghost" size="sm" onClick={() => setEditing(true)}>
          Edit
        </Button>
      </div>
      <div
        data-testid="ticket-description-readonly"
        className="prose prose-sm dark:prose-invert max-w-none rounded-md border border-input/60 px-3 py-2 text-sm"
      >
        {html ? (
          <Suspense
            fallback={
              <div data-testid="description-readonly-fallback" className="space-y-2" aria-hidden>
                <div className="h-4 w-3/4 animate-pulse rounded bg-muted" />
                <div className="h-4 w-1/2 animate-pulse rounded bg-muted" />
              </div>
            }
          >
            <ReadOnlyRichText html={html} />
          </Suspense>
        ) : (
          <p className="text-muted-foreground">No description.</p>
        )}
      </div>
    </section>
  );
}
