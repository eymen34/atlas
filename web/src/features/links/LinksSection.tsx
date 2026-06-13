import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link2, Trash2 } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router';
import { toast } from 'sonner';
import { deleteTicketLink, linkKeys, listTicketLinks, type TicketLink } from '@/api/links';
import { ticketKeys } from '@/api/tickets';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { AddLinkDialog } from './AddLinkDialog';
import { ORDERED_RELATIONS, RELATION_LABELS } from './relationLabels';

export interface LinksSectionProps {
  /** Current ticket UUID — link endpoints + cache key. */
  ticketId: string | undefined;
  /** Route identifier (e.g. ENG-1) — used to invalidate this ticket's activity query. */
  idOrKey: string;
  /** Project UUID — the add dialog's ticket search. */
  projectId: string | undefined;
  /** Project key (e.g. ENG) — builds target ticket links. */
  projectKey: string;
}

/**
 * Ticket links (T-026). Outgoing links grouped CLIENT-side into the five relation
 * sections. Add via a dialog (relation + cmdk search); remove via the trash button
 * (any member). Mutations are PRAGMATIC: invalidate the link list + the activity of
 * both endpoints (each link writes an activity row on both tickets).
 */
export function LinksSection({ ticketId, idOrKey, projectId, projectKey }: LinksSectionProps) {
  const qc = useQueryClient();
  const [addOpen, setAddOpen] = useState(false);

  const query = useQuery({
    queryKey: linkKeys.list(ticketId ?? ''),
    queryFn: () => listTicketLinks(ticketId!),
    enabled: !!ticketId,
  });

  const deleteMut = useMutation({
    mutationFn: (link: TicketLink) => deleteTicketLink(link.id),
    onSuccess: (_void, link) => {
      if (ticketId) {
        void qc.invalidateQueries({ queryKey: linkKeys.list(ticketId) });
      }
      void qc.invalidateQueries({ queryKey: ticketKeys.activity(idOrKey) });
      void qc.invalidateQueries({ queryKey: ticketKeys.activity(link.targetTicketKey) });
      toast.success('Link removed');
    },
    onError: () => toast.error('Could not remove the link'),
  });

  const links = query.data ?? [];
  const groups = ORDERED_RELATIONS.map((rel) => ({
    rel,
    items: links.filter((l) => l.relation === rel),
  })).filter((g) => g.items.length > 0);

  return (
    <section data-testid="links-section" aria-label="Links" className="space-y-3">
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-medium text-muted-foreground">Links</h2>
        <Button
          variant="outline"
          size="sm"
          type="button"
          onClick={() => setAddOpen(true)}
          disabled={!ticketId}
        >
          Add link
        </Button>
      </div>

      {query.isError && <p className="text-sm text-muted-foreground">Could not load links.</p>}
      {!query.isLoading && !query.isError && links.length === 0 && (
        <p data-testid="links-empty" className="text-sm text-muted-foreground">
          No links yet.
        </p>
      )}

      {groups.map((g) => (
        <div key={g.rel} className="space-y-1">
          <h3 className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
            {RELATION_LABELS[g.rel]}
          </h3>
          <ul className="space-y-1">
            {g.items.map((link) => (
              <li
                key={link.id}
                data-testid="link-row"
                className="flex items-center gap-2 rounded-md px-2 py-1.5 text-sm hover:bg-accent"
              >
                <Link2 className="h-4 w-4 shrink-0 text-muted-foreground" aria-hidden="true" />
                <Link
                  to={`/projects/${projectKey}/tickets/${link.targetTicketKey}`}
                  className="font-mono text-xs text-muted-foreground hover:underline"
                >
                  {link.targetTicketKey}
                </Link>
                <span className="flex-1 truncate">{link.targetTitle}</span>
                {link.targetDeleted && (
                  <Badge variant="outline" className="text-destructive">
                    deleted
                  </Badge>
                )}
                <Badge variant="outline" className="font-normal">
                  {link.targetStatus}
                </Badge>
                <button
                  type="button"
                  aria-label={`Remove link to ${link.targetTicketKey}`}
                  onClick={() => deleteMut.mutate(link)}
                >
                  <Trash2 className="h-4 w-4 text-destructive" />
                </button>
              </li>
            ))}
          </ul>
        </div>
      ))}

      {ticketId && projectId && (
        <AddLinkDialog
          open={addOpen}
          onClose={() => setAddOpen(false)}
          ticketId={ticketId}
          idOrKey={idOrKey}
          projectId={projectId}
        />
      )}
    </section>
  );
}
