package io.ngss.atlas.notification.dto;

import io.ngss.atlas.domain.NotificationKind;
import java.time.Instant;
import java.util.UUID;

/**
 * A notification as returned by GET /api/notifications (T-024). Enriched (batch,
 * no N+1) with the display fields the bell needs: {@code projectKey},
 * {@code ticketKey} ("{project.key}-{ticket.number}"), {@code ticketTitle}, and
 * {@code actorDisplayName}. Payload-derived fields ({@code actorId},
 * {@code commentId}, {@code fromStatus}, {@code toStatus}) are nullable per kind.
 */
public record NotificationResponse(
    UUID id,
    NotificationKind kind,
    UUID ticketId,
    String ticketKey,
    String ticketTitle,
    String projectKey,
    UUID actorId,
    String actorDisplayName,
    UUID commentId,
    String fromStatus,
    String toStatus,
    boolean read,
    Instant createdAt) {}
