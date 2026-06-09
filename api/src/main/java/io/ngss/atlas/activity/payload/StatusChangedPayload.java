package io.ngss.atlas.activity.payload;

import io.ngss.atlas.domain.TicketStatus;

/** Activity payload for {@code STATUS_CHANGED} ({@code from != to}, guaranteed by the caller). */
public record StatusChangedPayload(TicketStatus from, TicketStatus to) {}
