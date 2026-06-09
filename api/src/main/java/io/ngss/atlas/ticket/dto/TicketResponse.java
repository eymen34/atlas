package io.ngss.atlas.ticket.dto;

import io.ngss.atlas.domain.Ticket;
import io.ngss.atlas.domain.TicketPriority;
import io.ngss.atlas.domain.TicketStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response body for ticket endpoints (also used for list elements).
 *
 * <p>{@code key} is the human-facing display key {@code "{PROJECT_KEY}-{number}"}
 * (e.g. {@code ENG-42}) — COMPUTED, never stored. {@code labelIds} (T-018) is a
 * lightweight list of attached label ids: populated on the list (batch-loaded) and
 * detail GET; emitted as empty on create/update/transition responses (those paths
 * do not re-read associations). Soft-delete state is never exposed.
 */
public record TicketResponse(
    UUID id,
    String key,
    UUID projectId,
    int number,
    String title,
    String description,
    TicketStatus status,
    TicketPriority priority,
    UUID assigneeId,
    UUID reporterId,
    Instant createdAt,
    Instant updatedAt,
    List<UUID> labelIds) {

  public static TicketResponse from(Ticket t, String projectKey, List<UUID> labelIds) {
    return new TicketResponse(
        t.getId(),
        projectKey + "-" + t.getNumber(),
        t.getProjectId(),
        t.getNumber(),
        t.getTitle(),
        t.getDescription(),
        t.getStatus(),
        t.getPriority(),
        t.getAssigneeId(),
        t.getReporterId(),
        t.getCreatedAt(),
        t.getUpdatedAt(),
        labelIds);
  }
}
