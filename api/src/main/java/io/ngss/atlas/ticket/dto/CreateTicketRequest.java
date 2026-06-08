package io.ngss.atlas.ticket.dto;

import io.ngss.atlas.domain.TicketPriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Request body for POST /api/projects/{id}/tickets.
 *
 * <p>{@code status} is NOT accepted — a created ticket is always {@code TODO}.
 * {@code priority} is nullable: when omitted it defaults to {@code P2} in
 * {@code TicketService} (NOT {@code @NotNull}). {@code assigneeId} is optional and
 * is not validated against project membership (assignment is silently permitted).
 * {@code description} max is 64KB (markdown).
 */
public record CreateTicketRequest(
    @NotBlank @Size(max = 200) String title,
    @Size(max = 65536) String description,
    TicketPriority priority,
    UUID assigneeId) {}
