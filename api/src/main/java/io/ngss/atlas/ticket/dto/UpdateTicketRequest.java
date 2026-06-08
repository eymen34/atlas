package io.ngss.atlas.ticket.dto;

import io.ngss.atlas.domain.TicketPriority;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Request body for PATCH /api/tickets/{id}. Partial update with
 * null-means-unchanged semantics on every field:
 *
 * <ul>
 *   <li>field absent / explicit {@code null} → leave the value unchanged
 *   <li>{@code title} present → must be non-blank; a present-but-blank title is a
 *       400, enforced in {@code TicketService} via {@code TicketValidationException}
 *       (mirrors {@code ProjectValidationException}). {@code @NotBlank} cannot
 *       express "non-blank only when present" on a nullable record component, so
 *       only {@code @Size} lives here.
 *   <li>{@code assigneeId} present → reassigns; {@code priority} present → re-prioritizes.
 * </ul>
 *
 * <p>Status is NOT changed here — the POST /api/tickets/{id}/transition endpoint
 * owns status changes.
 */
public record UpdateTicketRequest(
    @Size(max = 200) String title,
    @Size(max = 65536) String description,
    UUID assigneeId,
    TicketPriority priority) {}
