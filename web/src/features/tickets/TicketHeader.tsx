import { ArrowLeft } from 'lucide-react';
import { useEffect, useRef, useState, type KeyboardEvent } from 'react';
import { useNavigate } from 'react-router';
import type { Member } from '@/api/projects';
import { STATUS_OPTIONS, type Ticket, type TicketStatus } from '@/api/tickets';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { useTransitionTicket, useUpdateTicket } from './hooks';
import { WatchToggle } from './WatchToggle';

const STATUS_LABELS: Record<TicketStatus, string> = {
  TODO: 'To Do',
  IN_PROGRESS: 'In Progress',
  IN_REVIEW: 'In Review',
  DONE: 'Done',
};

export interface TicketHeaderProps {
  idOrKey: string;
  ticket: Ticket;
  members: Member[];
}

/**
 * Ticket header: back button, key, an inline-editable title (click → input;
 * Enter/blur commit, Escape cancels; blank reverts), and the status Select that
 * drives the transition endpoint (NOT a field PATCH).
 */
export function TicketHeader({ idOrKey, ticket, members }: TicketHeaderProps) {
  const navigate = useNavigate();
  const update = useUpdateTicket(idOrKey, ticket);
  const transition = useTransitionTicket(idOrKey, ticket);

  const [editingTitle, setEditingTitle] = useState(false);
  const [draft, setDraft] = useState(ticket.title);
  // Guards against Enter (commit) and the ensuing blur (commit) firing twice.
  const committingRef = useRef(false);
  const inputRef = useRef<HTMLInputElement>(null);

  // Focus + select the title input when entering edit mode (avoids the autoFocus
  // prop, which jsx-a11y flags).
  useEffect(() => {
    if (editingTitle) {
      inputRef.current?.focus();
      inputRef.current?.select();
    }
  }, [editingTitle]);

  function startEdit() {
    setDraft(ticket.title);
    committingRef.current = false;
    setEditingTitle(true);
  }

  function commit() {
    if (committingRef.current) return;
    committingRef.current = true;
    setEditingTitle(false);
    const trimmed = draft.trim();
    // Blank/whitespace or unchanged → revert silently (no mutation).
    if (trimmed && trimmed !== ticket.title) {
      update.mutate({ title: trimmed });
    }
  }

  function cancel() {
    committingRef.current = true; // suppress the blur that unmounting fires
    setDraft(ticket.title);
    setEditingTitle(false);
  }

  function onTitleKeyDown(e: KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'Enter') {
      e.preventDefault();
      commit();
    } else if (e.key === 'Escape') {
      e.preventDefault();
      cancel();
    }
  }

  return (
    <header className="space-y-3">
      <div className="flex items-center gap-2">
        <Button variant="ghost" size="sm" aria-label="Back" onClick={() => navigate(-1)}>
          <ArrowLeft className="size-4" />
        </Button>
        <span className="font-mono text-sm text-muted-foreground">{ticket.key}</span>
      </div>

      <div className="flex flex-wrap items-start justify-between gap-3">
        {editingTitle ? (
          <Input
            ref={inputRef}
            aria-label="Ticket title"
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={onTitleKeyDown}
            onBlur={commit}
            className="max-w-xl text-lg font-semibold"
          />
        ) : (
          <h1 className="text-2xl font-semibold tracking-tight">
            <button
              type="button"
              onClick={startEdit}
              className="rounded text-left hover:bg-muted/50 focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none"
              title="Click to edit the title"
            >
              {ticket.title}
            </button>
          </h1>
        )}

        <div className="flex items-center gap-2">
          <Select
            value={ticket.status}
            onValueChange={(v) => transition.mutate(v as TicketStatus)}
            disabled={transition.isPending}
          >
            <SelectTrigger data-testid="status-select" className="w-[150px]" aria-label="Status">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {STATUS_OPTIONS.map((s) => (
                <SelectItem key={s} value={s}>
                  {STATUS_LABELS[s]}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          {/* T-023: watch toggle — renders only when the watchers flag is on. */}
          <WatchToggle ticketId={ticket.id} members={members} />
        </div>
      </div>
    </header>
  );
}
