package io.ngss.atlas.search.dto;

import io.ngss.atlas.domain.TicketStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * One full-text search hit (T-028). {@code ticketKey} ("ENG-12") is assembled in the
 * service from projectKey + number; {@code snippet} is ts_headline output with
 * {@code [[ ]]} highlight sentinels (the frontend renders them as &lt;strong&gt;);
 * {@code rank} is ts_rank_cd (higher = more relevant).
 */
public record TicketSearchResult(
    String ticketKey,
    UUID ticketId,
    String title,
    TicketStatus status,
    String projectKey,
    UUID projectId,
    String snippet,
    Instant updatedAt,
    double rank) {}
