import type { KeyboardEvent } from 'react';
import type { Member } from '@/api/projects';
import type { Label, Ticket, TicketPriority, TicketStatus } from '@/api/tickets';
import { Avatar, AvatarFallback } from '@/components/ui/avatar';
import { Badge } from '@/components/ui/badge';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { formatRelativeTime } from '@/lib/relativeTime';
import { cn } from '@/lib/utils';

const STATUS_CLASS: Record<TicketStatus, string> = {
  TODO: 'bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-200',
  IN_PROGRESS: 'bg-blue-100 text-blue-700 dark:bg-blue-950 dark:text-blue-200',
  IN_REVIEW: 'bg-amber-100 text-amber-700 dark:bg-amber-950 dark:text-amber-200',
  DONE: 'bg-green-100 text-green-700 dark:bg-green-950 dark:text-green-200',
};

const PRIORITY_CLASS: Record<TicketPriority, string> = {
  P0: 'bg-red-100 text-red-700 dark:bg-red-950 dark:text-red-200',
  P1: 'bg-orange-100 text-orange-700 dark:bg-orange-950 dark:text-orange-200',
  P2: 'bg-yellow-100 text-yellow-800 dark:bg-yellow-950 dark:text-yellow-200',
  P3: 'bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-200',
};

const MAX_LABEL_CHIPS = 3;

export interface TicketTableProps {
  tickets: Ticket[];
  members: Member[];
  labels: Label[];
  onRowClick: (ticket: Ticket) => void;
}

function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return '?';
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

export function TicketTable({ tickets, members, labels, onRowClick }: TicketTableProps) {
  const memberById = new Map(members.map((m) => [m.userId, m]));
  const labelById = new Map(labels.map((l) => [l.id, l]));

  function handleKeyDown(e: KeyboardEvent<HTMLTableRowElement>, ticket: Ticket) {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      onRowClick(ticket);
    }
  }

  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>Key</TableHead>
          <TableHead>Title</TableHead>
          <TableHead>Status</TableHead>
          <TableHead>Assignee</TableHead>
          <TableHead>Priority</TableHead>
          <TableHead>Labels</TableHead>
          <TableHead>Updated</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {tickets.map((ticket) => {
          const assignee = ticket.assigneeId ? memberById.get(ticket.assigneeId) : undefined;
          const assigneeName = assignee?.displayName || assignee?.email;
          const chipLabels = ticket.labelIds
            .map((id) => labelById.get(id))
            .filter((l): l is Label => Boolean(l));
          const shown = chipLabels.slice(0, MAX_LABEL_CHIPS);
          const overflow = chipLabels.length - shown.length;
          return (
            <TableRow
              key={ticket.id}
              tabIndex={0}
              aria-label={`${ticket.key} ${ticket.title}`}
              className="cursor-pointer"
              onClick={() => onRowClick(ticket)}
              onKeyDown={(e) => handleKeyDown(e, ticket)}
            >
              <TableCell className="font-mono text-xs text-muted-foreground">{ticket.key}</TableCell>
              <TableCell className="max-w-xs truncate font-medium">{ticket.title}</TableCell>
              <TableCell>
                <Badge className={cn('border-transparent', STATUS_CLASS[ticket.status])}>
                  {ticket.status}
                </Badge>
              </TableCell>
              <TableCell>
                {assigneeName ? (
                  <span className="flex items-center gap-2">
                    <Avatar size="sm">
                      <AvatarFallback>{initials(assigneeName)}</AvatarFallback>
                    </Avatar>
                    <span className="truncate">{assigneeName}</span>
                  </span>
                ) : (
                  <span className="text-muted-foreground">Unassigned</span>
                )}
              </TableCell>
              <TableCell>
                <Badge className={cn('border-transparent', PRIORITY_CLASS[ticket.priority])}>
                  {ticket.priority}
                </Badge>
              </TableCell>
              <TableCell>
                <span className="flex flex-wrap gap-1">
                  {shown.map((label) => (
                    <Badge key={label.id} variant="outline" className="font-normal">
                      {label.name}
                    </Badge>
                  ))}
                  {overflow > 0 && (
                    <Badge variant="secondary" className="font-normal">
                      +{overflow}
                    </Badge>
                  )}
                </span>
              </TableCell>
              <TableCell className="text-muted-foreground">
                {formatRelativeTime(ticket.updatedAt)}
              </TableCell>
            </TableRow>
          );
        })}
      </TableBody>
    </Table>
  );
}
