package io.ngss.atlas.label.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for PATCH /api/labels/{id}. Partial update with null-means-unchanged
 * semantics:
 *
 * <ul>
 *   <li>both {@code name} and {@code color} null → nothing to update → 400
 *       ({@code LabelValidationException}, enforced in the service).
 *   <li>{@code name} present → max 50 chars; renamed value is re-checked for the
 *       case-insensitive per-project uniqueness (DB unique index → 409).
 *   <li>{@code color} present → must match {@code ^#[0-9A-Fa-f]{6}$}.
 * </ul>
 */
public record UpdateLabelRequest(
    @Size(max = 50) String name, @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String color) {}
