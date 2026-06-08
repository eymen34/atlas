package io.ngss.atlas.ticket.event;

import io.ngss.atlas.domain.TicketStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Application event published when a ticket's status actually changes (T-017).
 * Plain record — NOT a JPA entity. Published via Spring's
 * {@code ApplicationEventPublisher} inside the transition transaction.
 *
 * <p>Bridge to T-019 (activity log): T-017 only PUBLISHES the event; no listener
 * exists yet. Publishing with no listener is a no-op in Spring, so this is
 * feature-flag-safe. A same-status (no-op) transition does NOT publish.
 *
 * @param ticketId the ticket whose status changed
 * @param projectId the ticket's project (denormalized so a listener need not reload)
 * @param fromStatus status before the transition
 * @param toStatus status after the transition (guaranteed {@code != fromStatus})
 * @param actorId the authenticated caller who performed the transition
 * @param occurredAt when the transition was applied
 */
public record TicketTransitionedEvent(
    UUID ticketId,
    UUID projectId,
    TicketStatus fromStatus,
    TicketStatus toStatus,
    UUID actorId,
    Instant occurredAt) {}
