package io.ngss.atlas.activity.payload;

import io.ngss.atlas.domain.TicketPriority;

/** Activity payload for {@code PRIORITY_CHANGED} ({@code from != to}). */
public record PriorityChangedPayload(TicketPriority from, TicketPriority to) {}
