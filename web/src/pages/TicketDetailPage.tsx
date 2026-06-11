import { useQuery } from '@tanstack/react-query';
import { useMemo } from 'react';
import { useNavigate, useParams } from 'react-router';
import { apiErrorStatus } from '@/api/errors';
import { listMembers, projectKeys } from '@/api/projects';
import { listLabels, ticketKeys } from '@/api/tickets';
import { Button } from '@/components/ui/button';
import { CommentsSection } from '@/features/tickets/CommentsSection';
import { useTicketActivity, useTicketDetail } from '@/features/tickets/hooks';
import { TicketActivityTimeline } from '@/features/tickets/TicketActivityTimeline';
import { TicketDescription } from '@/features/tickets/TicketDescription';
import { TicketHeader } from '@/features/tickets/TicketHeader';
import { TicketSidebar } from '@/features/tickets/TicketSidebar';
import { useAuthStore } from '@/store/authStore';

/**
 * Ticket detail page (T-021). A SIBLING of the project shell route — it fetches
 * its own ticket by the route key and does NOT use the project outlet context.
 *
 * <p>Only {@code getTicket} resolves the display key; members/labels/activity are
 * fetched by the resolved UUIDs (those endpoints bind UUID path vars), so they are
 * gated on the ticket having loaded.
 */
export default function TicketDetailPage() {
  const { key = '' } = useParams();
  const navigate = useNavigate();

  const ticketQuery = useTicketDetail(key);
  const ticket = ticketQuery.data;
  const projectId = ticket?.projectId;

  const membersQuery = useQuery({
    queryKey: projectKeys.members(projectId ?? ''),
    queryFn: () => listMembers(projectId!),
    enabled: !!projectId,
  });
  const labelsQuery = useQuery({
    queryKey: ticketKeys.labels(projectId ?? ''),
    queryFn: () => listLabels(projectId!),
    enabled: !!projectId,
  });
  const activityQuery = useTicketActivity(key, ticket?.id);

  const members = useMemo(() => membersQuery.data ?? [], [membersQuery.data]);
  const labels = labelsQuery.data ?? [];

  const currentUserId = useAuthStore((s) => s.user?.id ?? null);
  const isProjectAdmin = useMemo(
    () => members.some((m) => m.userId === currentUserId && m.role === 'ADMIN'),
    [members, currentUserId]
  );

  if (ticketQuery.isLoading) {
    return (
      <div
        role="status"
        aria-live="polite"
        data-testid="ticket-detail-loading"
        className="flex min-h-[40vh] items-center justify-center text-muted-foreground"
      >
        Loading…
      </div>
    );
  }

  if (ticketQuery.isError || !ticket) {
    const notFound = apiErrorStatus(ticketQuery.error) === 404;
    return (
      <div
        data-testid={notFound ? 'ticket-not-found' : 'ticket-error'}
        className="mx-auto max-w-md space-y-3 py-16 text-center"
      >
        <h1 className="text-lg font-semibold">
          {notFound ? 'Ticket not found' : 'Could not load this ticket'}
        </h1>
        <p className="text-sm text-muted-foreground">
          {notFound
            ? 'It may have been deleted, or you may not have access to it.'
            : 'Something went wrong. Please try again.'}
        </p>
        <Button variant="outline" size="sm" onClick={() => navigate(-1)}>
          Go back
        </Button>
      </div>
    );
  }

  return (
    <div data-testid="ticket-detail-page" className="mx-auto max-w-5xl p-4 lg:p-6">
      <div className="grid gap-6 lg:grid-cols-[1fr_320px]">
        <div className="space-y-6">
          <TicketHeader idOrKey={key} ticket={ticket} members={members} />
          <TicketDescription idOrKey={key} ticket={ticket} />
          <CommentsSection
            ticketId={ticket.id}
            idOrKey={key}
            members={members}
            currentUserId={currentUserId}
            isProjectAdmin={isProjectAdmin}
          />
          {/* T-025: attachment list slot */}
          {/* T-026: links slot */}
          <TicketActivityTimeline events={activityQuery.data ?? []} members={members} />
        </div>
        <TicketSidebar idOrKey={key} ticket={ticket} members={members} labels={labels} />
      </div>
    </div>
  );
}
