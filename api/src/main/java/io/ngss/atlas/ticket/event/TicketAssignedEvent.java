package io.ngss.atlas.ticket.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Application event published when a ticket gains a (non-null) assignee — both on
 * create-with-assignee and on the assignee-change path of update (T-024). Plain
 * record, consumed AFTER_COMMIT by the notification fan-out. Published
 * unconditionally when {@code newAssigneeId != null}; the listener suppresses the
 * self-assign case ({@code newAssigneeId.equals(actorId)}).
 *
 * @param ticketId the ticket assigned
 * @param projectId the ticket's project (denormalized so the listener need not reload)
 * @param newAssigneeId the user the ticket was assigned to (never null)
 * @param actorId the caller who performed the assignment
 * @param sourceEventId the ASSIGNEE_CHANGED / CREATED activity row's id (nullable)
 * @param occurredAt when the assignment was applied
 */
public record TicketAssignedEvent(
    UUID ticketId,
    UUID projectId,
    UUID newAssigneeId,
    UUID actorId,
    UUID sourceEventId,
    Instant occurredAt) {}
