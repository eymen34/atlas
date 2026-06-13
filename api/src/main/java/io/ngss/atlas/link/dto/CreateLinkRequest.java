package io.ngss.atlas.link.dto;

import io.ngss.atlas.domain.LinkRelation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Body of POST /api/tickets/{id}/links (T-026). {@code toTicketKey} is the target's
 * display key (e.g. {@code ENG-12}) and MUST be in the same project as the source.
 */
public record CreateLinkRequest(
    @NotBlank String toTicketKey,
    @NotNull
        @Schema(
            description =
                "Only BLOCKS, DUPLICATES, RELATES_TO are valid on create — "
                    + "IS_BLOCKED_BY and IS_DUPLICATED_BY are server-derived and rejected with 400.")
        LinkRelation relation) {}
