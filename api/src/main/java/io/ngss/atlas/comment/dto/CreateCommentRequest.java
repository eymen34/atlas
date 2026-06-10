package io.ngss.atlas.comment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for POST /api/tickets/{id}/comments. {@code body} is TipTap-emitted
 * HTML (D1; never markdown), capped at the same 16384 chars as the DB CHECK.
 */
public record CreateCommentRequest(@NotBlank @Size(max = 16384) String body) {}
