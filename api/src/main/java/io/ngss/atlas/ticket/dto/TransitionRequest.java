package io.ngss.atlas.ticket.dto;

import io.ngss.atlas.domain.TicketStatus;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for POST /api/tickets/{id}/transition. The MVP workflow is
 * unrestricted: any status may transition to any other (e.g. DONE → TODO). A
 * transition to the ticket's current status is a no-op (200, no event published).
 */
public record TransitionRequest(@NotNull TicketStatus toStatus) {}
