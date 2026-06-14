import { Check } from 'lucide-react';
import { useState } from 'react';
import type { Member } from '@/api/projects';
import {
  type Label as TicketLabel,
  PRIORITY_OPTIONS,
  type Ticket,
  type TicketPriority,
} from '@/api/tickets';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  Command,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
} from '@/components/ui/command';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { cn } from '@/lib/utils';
import { useSetTicketLabels, useUnassignTicket, useUpdateTicket } from './hooks';

const memberName = (m: Member) => m.displayName || m.email || 'Unknown user';

function sameIds(a: string[], b: string[]): boolean {
  if (a.length !== b.length) return false;
  const set = new Set(a);
  return b.every((id) => set.has(id));
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="space-y-1">
      <p className="text-xs font-medium text-muted-foreground uppercase tracking-wide">{label}</p>
      {children}
    </div>
  );
}

function AssigneePicker({
  idOrKey,
  ticket,
  members,
}: {
  idOrKey: string;
  ticket: Ticket;
  members: Member[];
}) {
  const update = useUpdateTicket(idOrKey, ticket);
  const unassign = useUnassignTicket(idOrKey, ticket);
  const [open, setOpen] = useState(false);
  const current = members.find((m) => m.userId === ticket.assigneeId);
  const pending = update.isPending || unassign.isPending;

  function choose(userId: string | null) {
    setOpen(false);
    if (userId === (ticket.assigneeId ?? null)) {
      return; // no change
    }
    // SET goes through PATCH; CLEAR is the dedicated DELETE verb (T-041) — PATCH
    // assigneeId:null would silently no-op on the backend.
    if (userId === null) {
      unassign.mutate();
    } else {
      update.mutate({ assigneeId: userId });
    }
  }

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button
          variant="outline"
          size="sm"
          data-testid="assignee-picker"
          disabled={pending}
          className="w-full justify-start font-normal"
        >
          {current ? memberName(current) : <span className="text-muted-foreground">Unassigned</span>}
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" className="w-64 p-0">
        <Command>
          <CommandInput placeholder="Search members…" />
          <CommandList>
            <CommandEmpty>No members.</CommandEmpty>
            <CommandGroup>
              <CommandItem value="Unassigned" onSelect={() => choose(null)}>
                <Check className={cn('size-4', ticket.assigneeId ? 'opacity-0' : 'opacity-100')} />
                Unassigned
              </CommandItem>
              {members.map((m) => (
                <CommandItem key={m.userId} value={memberName(m)} onSelect={() => choose(m.userId)}>
                  <Check
                    className={cn(
                      'size-4',
                      ticket.assigneeId === m.userId ? 'opacity-100' : 'opacity-0'
                    )}
                  />
                  {memberName(m)}
                </CommandItem>
              ))}
            </CommandGroup>
          </CommandList>
        </Command>
      </PopoverContent>
    </Popover>
  );
}

function PriorityPicker({ idOrKey, ticket }: { idOrKey: string; ticket: Ticket }) {
  const update = useUpdateTicket(idOrKey, ticket);
  return (
    <Select
      value={ticket.priority}
      onValueChange={(v) => update.mutate({ priority: v as TicketPriority })}
      disabled={update.isPending}
    >
      <SelectTrigger data-testid="priority-picker" className="w-full" aria-label="Priority">
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        {PRIORITY_OPTIONS.map((p) => (
          <SelectItem key={p} value={p}>
            {p}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}

function LabelsPicker({
  idOrKey,
  ticket,
  labels,
}: {
  idOrKey: string;
  ticket: Ticket;
  labels: TicketLabel[];
}) {
  const setLabels = useSetTicketLabels(idOrKey, ticket);
  const [open, setOpen] = useState(false);
  const [selected, setSelected] = useState<string[]>(ticket.labelIds);

  function onOpenChange(next: boolean) {
    if (next) {
      // Re-sync the working selection from the ticket each time we open.
      setSelected(ticket.labelIds);
    } else if (!sameIds(selected, ticket.labelIds)) {
      // Commit on close, only if the set actually changed.
      setLabels.mutate(selected);
    }
    setOpen(next);
  }

  function toggle(id: string) {
    setSelected((prev) => (prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]));
  }

  const chosen = labels.filter((l) => ticket.labelIds.includes(l.id));

  return (
    <Popover open={open} onOpenChange={onOpenChange}>
      <PopoverTrigger asChild>
        <Button
          variant="outline"
          size="sm"
          data-testid="labels-picker"
          disabled={setLabels.isPending}
          className="h-auto min-h-9 w-full flex-wrap justify-start gap-1 font-normal"
        >
          {chosen.length > 0 ? (
            chosen.map((l) => (
              <Badge key={l.id} variant="secondary" className="font-normal">
                {l.name}
              </Badge>
            ))
          ) : (
            <span className="text-muted-foreground">Add labels</span>
          )}
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" className="w-64 p-0">
        <Command>
          <CommandInput placeholder="Search labels…" />
          <CommandList>
            <CommandEmpty>No labels.</CommandEmpty>
            <CommandGroup>
              {labels.map((l) => (
                <CommandItem key={l.id} value={l.name} onSelect={() => toggle(l.id)}>
                  <Check
                    className={cn('size-4', selected.includes(l.id) ? 'opacity-100' : 'opacity-0')}
                  />
                  {l.name}
                </CommandItem>
              ))}
            </CommandGroup>
          </CommandList>
        </Command>
      </PopoverContent>
    </Popover>
  );
}

export interface TicketSidebarProps {
  idOrKey: string;
  ticket: Ticket;
  members: Member[];
  labels: TicketLabel[];
}

/** Right-rail field editors: assignee, priority, labels, plus a read-only reporter. */
export function TicketSidebar({ idOrKey, ticket, members, labels }: TicketSidebarProps) {
  const reporter = members.find((m) => m.userId === ticket.reporterId);
  return (
    <aside data-testid="ticket-sidebar" className="space-y-4">
      <Field label="Assignee">
        <AssigneePicker idOrKey={idOrKey} ticket={ticket} members={members} />
      </Field>
      <Field label="Priority">
        <PriorityPicker idOrKey={idOrKey} ticket={ticket} />
      </Field>
      <Field label="Labels">
        <LabelsPicker idOrKey={idOrKey} ticket={ticket} labels={labels} />
      </Field>
      <Field label="Reporter">
        <p className="text-sm">{reporter ? memberName(reporter) : 'Unknown user'}</p>
      </Field>
    </aside>
  );
}
