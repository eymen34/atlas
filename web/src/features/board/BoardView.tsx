import {
  closestCenter,
  DndContext,
  type DragEndEvent,
  DragOverlay,
  type DragStartEvent,
  KeyboardSensor,
  MeasuringStrategy,
  PointerSensor,
  useSensor,
  useSensors,
} from '@dnd-kit/core';
import { useQuery } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { listMembers, type Project, projectKeys } from '@/api/projects';
import { listLabels, type Ticket, ticketKeys } from '@/api/tickets';
import { ProjectViewToggle } from '@/features/project/ProjectViewToggle';
import { FilterBar } from '@/pages/project/list/FilterBar';
import { useTicketFilters } from '@/pages/project/list/useTicketFilters';
import { BoardColumn } from './BoardColumn';
import { boardCoordinateGetter } from './keyboardCoordinates';
import { DragOverlayCard } from './DragOverlayCard';
import { boardDropTarget, COLUMN_ORDER } from './statusOrder';
import { useBoardTickets } from './useBoardTickets';
import { useTransitionTicketOptimistic } from './useTransitionTicketOptimistic';

/** Inner board: owns the board query, the optimistic transition, and the DnD context. */
export function BoardView({ project }: { project: Project }) {
  const [filters, setFilters] = useTicketFilters();
  const { columns, isLoading, isError } = useBoardTickets(project.id, filters);
  const transition = useTransitionTicketOptimistic(project.id, filters);
  const [activeTicket, setActiveTicket] = useState<Ticket | null>(null);

  // Members/labels feed the filter bar + card chips; their failure must NOT break the board.
  const membersQuery = useQuery({
    queryKey: projectKeys.members(project.id),
    queryFn: () => listMembers(project.id),
  });
  const labelsQuery = useQuery({
    queryKey: ticketKeys.labels(project.id),
    queryFn: () => listLabels(project.id),
  });
  const members = useMemo(() => membersQuery.data ?? [], [membersQuery.data]);
  const labels = useMemo(() => labelsQuery.data ?? [], [labelsQuery.data]);
  const memberById = useMemo(() => new Map(members.map((m) => [m.userId, m])), [members]);
  const labelById = useMemo(() => new Map(labels.map((l) => [l.id, l])), [labels]);

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 8 } }),
    useSensor(KeyboardSensor, { coordinateGetter: boardCoordinateGetter })
  );

  function handleDragStart(event: DragStartEvent) {
    setActiveTicket((event.active.data.current?.ticket as Ticket | undefined) ?? null);
  }

  function handleDragEnd(event: DragEndEvent) {
    setActiveTicket(null);
    const ticket = event.active.data.current?.ticket as Ticket | undefined;
    if (!ticket) {
      return;
    }
    const target = boardDropTarget(ticket, event.over?.id);
    if (target) {
      transition.mutate({ ticket, toStatus: target });
    }
  }

  const overlayAssignee =
    activeTicket?.assigneeId ? memberById.get(activeTicket.assigneeId) : undefined;

  return (
    <section className="space-y-4" data-testid="board-page">
      <header className="flex flex-wrap items-center justify-between gap-3">
        <ProjectViewToggle />
        <FilterBar value={filters} onChange={setFilters} members={members} labels={labels} hideStatus />
      </header>

      {isError && (
        <p className="text-sm text-muted-foreground">Could not load the board.</p>
      )}

      <DndContext
        sensors={sensors}
        collisionDetection={closestCenter}
        measuring={{ droppable: { strategy: MeasuringStrategy.Always } }}
        onDragStart={handleDragStart}
        onDragEnd={handleDragEnd}
        onDragCancel={() => setActiveTicket(null)}
      >
        <div className="flex gap-3 overflow-x-auto pb-2" data-testid="board-columns">
          {COLUMN_ORDER.map((status) => (
            <BoardColumn
              key={status}
              status={status}
              items={columns[status]}
              projectKey={project.key}
              memberById={memberById}
              labelById={labelById}
            />
          ))}
        </div>
        <DragOverlay>
          {activeTicket ? (
            <DragOverlayCard
              ticket={activeTicket}
              assigneeName={
                overlayAssignee ? overlayAssignee.displayName || overlayAssignee.email : undefined
              }
              labelColors={activeTicket.labelIds
                .map((id) => labelById.get(id)?.color)
                .filter((c): c is string => Boolean(c))}
            />
          ) : null}
        </DragOverlay>
      </DndContext>

      {isLoading && (
        <p data-testid="board-loading" className="text-sm text-muted-foreground">
          Loading…
        </p>
      )}
    </section>
  );
}
