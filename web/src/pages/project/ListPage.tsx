import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate } from 'react-router';
import { listMembers, projectKeys } from '@/api/projects';
import { listLabels, listTickets, ticketKeys } from '@/api/tickets';
import { Button } from '@/components/ui/button';
import { ProjectViewToggle } from '@/features/project/ProjectViewToggle';
import { useProjectOutlet } from './context';
import { FilterBar } from './list/FilterBar';
import { NewTicketDialog } from './list/NewTicketDialog';
import { PagerFooter } from './list/PagerFooter';
import { TicketTable } from './list/TicketTable';
import { useTicketFilters } from './list/useTicketFilters';

export function ListPage() {
  const { project } = useProjectOutlet();
  const [filters, setFilters] = useTicketFilters();
  const navigate = useNavigate();
  const [dialogOpen, setDialogOpen] = useState(false);

  const ticketsQuery = useQuery({
    queryKey: ticketKeys.list(project.id, filters),
    queryFn: () => listTickets(project.id, filters),
    placeholderData: keepPreviousData,
  });

  // Members/labels feed the filter controls + row rendering. Their failure must NOT
  // take down the table, so we fall back to empty arrays (no full-page error).
  const membersQuery = useQuery({
    queryKey: projectKeys.members(project.id),
    queryFn: () => listMembers(project.id),
  });
  const labelsQuery = useQuery({
    queryKey: ticketKeys.labels(project.id),
    queryFn: () => listLabels(project.id),
  });

  const members = membersQuery.data ?? [];
  const labels = labelsQuery.data ?? [];
  const data = ticketsQuery.data;

  return (
    <section className="space-y-4">
      <header className="flex items-center justify-between gap-4">
        <div className="flex items-center gap-3">
          <h2 className="text-lg font-semibold">Tickets</h2>
          <ProjectViewToggle />
        </div>
        <Button onClick={() => setDialogOpen(true)}>New ticket</Button>
      </header>

      <FilterBar value={filters} onChange={setFilters} members={members} labels={labels} />

      {ticketsQuery.isLoading && (
        <div data-testid="tickets-loading" className="space-y-2">
          {Array.from({ length: 5 }).map((_, i) => (
            <div key={i} className="h-10 animate-pulse rounded-md bg-muted/40" />
          ))}
        </div>
      )}

      {ticketsQuery.isError && (
        <div role="alert" className="rounded-md border border-dashed py-10 text-center">
          <p className="text-sm text-muted-foreground">Could not load tickets.</p>
          <Button variant="outline" size="sm" className="mt-3" onClick={() => ticketsQuery.refetch()}>
            Retry
          </Button>
        </div>
      )}

      {data && data.items.length === 0 && (
        <p className="rounded-md border border-dashed py-10 text-center text-sm text-muted-foreground">
          No tickets match the current filters.
        </p>
      )}

      {data && data.items.length > 0 && (
        <TicketTable
          tickets={data.items}
          members={members}
          labels={labels}
          onRowClick={(ticket) => navigate(`/projects/${project.key}/tickets/${ticket.key}`)}
        />
      )}

      {data && (
        <PagerFooter
          page={data.page}
          size={data.size}
          total={data.total}
          onChange={(page, size) => setFilters({ ...filters, page, size })}
        />
      )}

      <NewTicketDialog
        open={dialogOpen}
        onOpenChange={setDialogOpen}
        projectId={project.id}
        projectKey={project.key}
        members={members}
      />
    </section>
  );
}
