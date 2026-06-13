import { useDraggable } from '@dnd-kit/core';
import { CSS } from '@dnd-kit/utilities';
import { GripVertical } from 'lucide-react';
import { memo } from 'react';
import { Link } from 'react-router';
import type { Ticket } from '@/api/tickets';
import { Badge } from '@/components/ui/badge';

export interface BoardTicketCardProps {
  ticket: Ticket;
  /** Project display key for the detail link (mutation_uuid_vs_route_key: link uses ticket.key). */
  projectKey: string;
  assigneeName?: string;
  labelColors: string[];
}

function BoardTicketCardImpl({ ticket, projectKey, assigneeName, labelColors }: BoardTicketCardProps) {
  // data carries the whole ticket so the drag handler reads it without a lookup.
  const { attributes, listeners, setNodeRef, transform, isDragging } = useDraggable({
    id: ticket.id,
    data: { ticket },
  });
  const style = {
    transform: CSS.Translate.toString(transform),
    opacity: isDragging ? 0.4 : undefined,
  };

  return (
    <li
      ref={setNodeRef}
      style={style}
      data-testid={`board-ticket-card-${ticket.id}`}
      data-ticket-key={ticket.key}
      className="flex items-start gap-2 rounded-md border border-border bg-card p-2 text-sm shadow-sm"
    >
      {/* Separate drag handle: clicking the card body navigates (AC5); dragging the
          handle (past the 8px PointerSensor threshold) moves the card. The handle is
          also the keyboard drag target (Space to lift, arrows to move). */}
      <span
        {...listeners}
        {...attributes}
        role="button"
        aria-label={`Drag ${ticket.key}`}
        className="mt-0.5 cursor-grab touch-none text-muted-foreground"
      >
        <GripVertical className="h-4 w-4" aria-hidden="true" />
      </span>
      <div className="min-w-0 flex-1">
        <Link
          to={`/projects/${projectKey}/tickets/${ticket.key}`}
          className="font-mono text-xs text-muted-foreground hover:underline"
        >
          {ticket.key}
        </Link>
        <p className="truncate font-medium">{ticket.title}</p>
        <div className="mt-1 flex items-center gap-2">
          <Badge variant="outline" className="font-normal">
            {ticket.priority}
          </Badge>
          {assigneeName && (
            <span className="truncate text-xs text-muted-foreground">{assigneeName}</span>
          )}
          {labelColors.length > 0 && (
            <span className="flex items-center gap-1" aria-hidden="true">
              {labelColors.map((color, i) => (
                <span
                  key={i}
                  className="h-2 w-2 rounded-full border border-border"
                  style={{ backgroundColor: color }}
                />
              ))}
            </span>
          )}
        </div>
      </div>
    </li>
  );
}

/** Explicit comparator (D4): re-render only when a displayed field actually changes. */
export const BoardTicketCard = memo(
  BoardTicketCardImpl,
  (prev, next) =>
    prev.ticket.id === next.ticket.id &&
    prev.ticket.status === next.ticket.status &&
    prev.ticket.title === next.ticket.title &&
    prev.ticket.priority === next.ticket.priority &&
    prev.ticket.assigneeId === next.ticket.assigneeId &&
    prev.assigneeName === next.assigneeName &&
    prev.projectKey === next.projectKey &&
    prev.labelColors.join(',') === next.labelColors.join(',')
);
