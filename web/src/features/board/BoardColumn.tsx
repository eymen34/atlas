import { useDroppable } from '@dnd-kit/core';
import type { Member } from '@/api/projects';
import type { Label, Ticket, TicketStatus } from '@/api/tickets';
import { cn } from '@/lib/utils';
import { BoardTicketCard } from './BoardTicketCard';
import { STATUS_LABEL, VIRTUALIZE_THRESHOLD } from './statusOrder';
import { VirtualizedCardList } from './VirtualizedCardList';

export interface BoardColumnProps {
  status: TicketStatus;
  items: Ticket[];
  projectKey: string;
  memberById: Map<string, Member>;
  labelById: Map<string, Label>;
}

/** One status column — a droppable target whose id IS the status (the drop axis). */
export function BoardColumn({ status, items, projectKey, memberById, labelById }: BoardColumnProps) {
  const { setNodeRef, isOver } = useDroppable({ id: status });

  function renderCard(ticket: Ticket) {
    const assignee = ticket.assigneeId ? memberById.get(ticket.assigneeId) : undefined;
    const assigneeName = assignee ? assignee.displayName || assignee.email : undefined;
    const labelColors = ticket.labelIds
      .map((id) => labelById.get(id)?.color)
      .filter((c): c is string => Boolean(c));
    return (
      <BoardTicketCard
        key={ticket.id}
        ticket={ticket}
        projectKey={projectKey}
        assigneeName={assigneeName}
        labelColors={labelColors}
      />
    );
  }

  return (
    <section
      ref={setNodeRef}
      data-testid={`board-column-${status}`}
      aria-label={STATUS_LABEL[status]}
      className={cn(
        'flex w-72 shrink-0 flex-col rounded-lg bg-muted/30 p-2',
        isOver && 'ring-2 ring-primary'
      )}
    >
      <header className="flex items-center justify-between px-1 pb-2 text-sm font-medium">
        <span>{STATUS_LABEL[status]}</span>
        <span className="text-muted-foreground" data-testid={`board-column-count-${status}`}>
          {items.length}
        </span>
      </header>
      {items.length === 0 ? (
        <p className="px-1 py-6 text-center text-xs text-muted-foreground">No tickets</p>
      ) : items.length > VIRTUALIZE_THRESHOLD ? (
        <VirtualizedCardList items={items} renderItem={renderCard} />
      ) : (
        <ul className="space-y-2">{items.map(renderCard)}</ul>
      )}
    </section>
  );
}
