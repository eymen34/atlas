package io.ngss.atlas.activity.payload;

import io.ngss.atlas.domain.TicketPriority;
import io.ngss.atlas.domain.TicketStatus;

/**
 * Activity payload for {@code CREATED} — a snapshot of the ticket at creation time.
 * Serialized to the {@code activity_events.payload} text column by Jackson.
 */
public record CreatedPayload(String title, TicketStatus status, TicketPriority priority) {}
