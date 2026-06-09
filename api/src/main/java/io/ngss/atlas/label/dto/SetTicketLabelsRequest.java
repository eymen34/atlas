package io.ngss.atlas.label.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * Request body for PUT /api/tickets/{id}/labels — a full replace of the ticket's
 * labels. The list is {@code @NotNull} but MAY be empty: an empty list clears all
 * labels. Duplicate ids are de-duplicated by the service.
 */
public record SetTicketLabelsRequest(@NotNull List<UUID> labelIds) {}
