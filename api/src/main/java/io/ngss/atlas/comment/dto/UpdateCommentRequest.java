package io.ngss.atlas.comment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for PATCH /api/comments/{id}. A comment edit always replaces the
 * full HTML body (no partial-field semantics); mentions are re-derived server-side.
 */
public record UpdateCommentRequest(@NotBlank @Size(max = 16384) String body) {}
