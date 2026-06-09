package io.ngss.atlas.label.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for POST /api/projects/{id}/labels.
 *
 * <p>{@code name} is required, max 50 chars (case-insensitive unique per project —
 * enforced by the V7 functional index + a service pre-check → 409). {@code color}
 * is optional; when present it must be a 6-digit hex string like {@code #1A2B3C}
 * ({@code @Pattern} skips validation on null).
 */
public record CreateLabelRequest(
    @NotBlank @Size(max = 50) String name,
    @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String color) {}
