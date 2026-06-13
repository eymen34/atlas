import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { toast } from 'sonner';
import { apiErrorMessage, apiErrorStatus } from '@/api/errors';
import { createTicketLink, linkKeys, type UserFacingRelation } from '@/api/links';
import { listTickets, type Ticket, type TicketFilters, ticketKeys } from '@/api/tickets';
import { Button } from '@/components/ui/button';
import {
  Command,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
} from '@/components/ui/command';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { RELATION_LABELS, USER_FACING_RELATIONS } from './relationLabels';

export interface AddLinkDialogProps {
  open: boolean;
  onClose: () => void;
  ticketId: string;
  idOrKey: string;
  projectId: string;
}

// Reuse the existing ticket-list query (no new backend endpoint); cmdk filters client-side.
const SEARCH_FILTERS: TicketFilters = { page: 0, size: 100 };

export function AddLinkDialog({ open, onClose, ticketId, idOrKey, projectId }: AddLinkDialogProps) {
  const qc = useQueryClient();
  const [relation, setRelation] = useState<UserFacingRelation>('BLOCKS');
  const [selected, setSelected] = useState<Ticket | null>(null);
  const [error, setError] = useState<string | null>(null);

  const ticketsQuery = useQuery({
    queryKey: ticketKeys.list(projectId, SEARCH_FILTERS),
    queryFn: () => listTickets(projectId, SEARCH_FILTERS),
    enabled: open,
  });

  const candidates = (ticketsQuery.data?.items ?? []).filter((t) => t.id !== ticketId);

  const createMut = useMutation({
    mutationFn: (vars: { toKey: string; rel: UserFacingRelation }) =>
      createTicketLink(ticketId, vars.toKey, vars.rel),
    onSuccess: (created) => {
      void qc.invalidateQueries({ queryKey: linkKeys.list(ticketId) });
      void qc.invalidateQueries({ queryKey: ticketKeys.activity(idOrKey) });
      void qc.invalidateQueries({ queryKey: ticketKeys.activity(created.targetTicketKey) });
      toast.success('Link added');
      reset();
      onClose();
    },
    onError: (err) => {
      // 409 (per-pair conflict) and 400 (self/unknown/inverse) are shown INLINE; the
      // dialog stays open so the user can correct the selection.
      setError(
        apiErrorStatus(err) === 409
          ? 'A link already exists between these tickets'
          : apiErrorMessage(err, 'Could not add the link')
      );
    },
  });

  function reset() {
    setRelation('BLOCKS');
    setSelected(null);
    setError(null);
  }

  function handleOpenChange(next: boolean) {
    if (!next) {
      reset();
      onClose();
    }
  }

  function submit() {
    if (!selected) {
      setError('Pick a ticket to link');
      return;
    }
    setError(null);
    createMut.mutate({ toKey: selected.key, rel: relation });
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Add link</DialogTitle>
        </DialogHeader>
        <div className="space-y-3">
          <Select value={relation} onValueChange={(v) => setRelation(v as UserFacingRelation)}>
            <SelectTrigger data-testid="relation-select" aria-label="Relation">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {USER_FACING_RELATIONS.map((r) => (
                <SelectItem key={r} value={r}>
                  {RELATION_LABELS[r]}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>

          <Command className="rounded-md border">
            <CommandInput placeholder="Search tickets in this project…" />
            <CommandList>
              <CommandEmpty>No tickets found.</CommandEmpty>
              <CommandGroup>
                {candidates.map((t) => (
                  <CommandItem
                    key={t.id}
                    value={`${t.key} ${t.title}`}
                    onSelect={() => setSelected(t)}
                  >
                    <span className="font-mono text-xs text-muted-foreground">{t.key}</span>
                    <span className="ml-2 truncate">{t.title}</span>
                  </CommandItem>
                ))}
              </CommandGroup>
            </CommandList>
          </Command>

          {selected && (
            <p className="text-sm" data-testid="add-link-selected">
              Selected: <span className="font-mono">{selected.key}</span> {selected.title}
            </p>
          )}
          {error && (
            <p data-testid="add-link-error" className="text-sm text-destructive">
              {error}
            </p>
          )}

          <div className="flex justify-end gap-2">
            <Button variant="outline" type="button" onClick={handleOpenChange.bind(null, false)}>
              Cancel
            </Button>
            <Button
              type="button"
              onClick={submit}
              disabled={createMut.isPending || !selected}
              data-testid="add-link-submit"
            >
              Add link
            </Button>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}
