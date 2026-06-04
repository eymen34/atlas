package io.ngss.atlas.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for POST /api/projects.
 *
 * <p>{@code key} must match {@code ^[A-Z][A-Z0-9]{1,9}$} (2–10 chars, leading
 * uppercase letter, then uppercase alphanumerics) — enforced at the DTO layer so
 * a violation is a 400 before the service runs. {@code name} max 200,
 * {@code description} max 1000 (resolved triage decision).
 */
public record CreateProjectRequest(
    @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9]{1,9}$") String key,
    @NotBlank @Size(max = 200) String name,
    @Size(max = 1000) String description) {}
