package io.ngss.atlas.ticket.event;

import io.ngss.atlas.domain.TicketStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Application event published when a ticket's status actually changes (T-017).
 * Plain record — NOT a JPA entity. Published synchronously inside
 * {@code TicketService.transition()} AFTER the entity save.
 *
 * <p>This event is for the notification fan-out (T-024), which will consume it
 * AFTER_COMMIT. Activity-log rows are NOT written via this event — they are written
 * synchronously by {@code ActivityEventWriter} inside the SAME transaction (T-019)
 * to preserve atomicity with the status change. A same-status (no-op) transition
 * publishes nothing and records no activity.
 *
 * @param ticketId the ticket whose status changed
 * @param projectId the ticket's project (denormalized so a listener need not reload)
 * @param fromStatus status before the transition
 * @param toStatus status after the transition (guaranteed {@code != fromStatus})
 * @param actorId the authenticated caller who performed the transition
 * @param sourceEventId the STATUS_CHANGED activity row's id (T-024; recorded BEFORE
 *     this event is published so the notification can reference it)
 * @param occurredAt when the transition was applied
 */
public record TicketTransitionedEvent(
    UUID ticketId,
    UUID projectId,
    TicketStatus fromStatus,
    TicketStatus toStatus,
    UUID actorId,
    UUID sourceEventId,
    Instant occurredAt) {}
