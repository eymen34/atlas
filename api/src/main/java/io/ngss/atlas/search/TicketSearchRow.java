package io.ngss.atlas.search;

import java.time.Instant;
import java.util.UUID;

/**
 * Package-private projection of one native search row (T-028). The service assembles
 * the public {@code TicketSearchResult} from this (e.g. ticketKey = projectKey-number).
 * {@code status} stays a raw String here; the service parses it to {@code TicketStatus}.
 */
record TicketSearchRow(
    UUID id,
    UUID projectId,
    String projectKey,
    int number,
    String title,
    String status,
    Instant updatedAt,
    String snippet,
    double rank) {}
