package io.ngss.atlas.link.dto;

import io.ngss.atlas.domain.LinkRelation;
import io.ngss.atlas.domain.TicketStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * A ticket link as returned by the API (T-026), enriched with the TARGET ticket's
 * display fields. {@code targetDeleted} is true when the target ticket is
 * soft-deleted — the row is still returned (not filtered), so the UI can mark it
 * "(deleted)" while keeping the link visible (architect ADDENDUM / EC-13).
 */
public record LinkResponse(
    UUID id,
    UUID fromTicketId,
    UUID toTicketId,
    LinkRelation relation,
    String targetTicketKey,
    String targetTitle,
    TicketStatus targetStatus,
    boolean targetDeleted,
    UUID createdBy,
    Instant createdAt) {}
