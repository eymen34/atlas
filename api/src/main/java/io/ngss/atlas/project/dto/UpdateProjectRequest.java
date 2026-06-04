package io.ngss.atlas.project.dto;

import jakarta.validation.constraints.Size;

/**
 * Request body for PATCH /api/projects/{id}. Partial update with
 * null-means-unchanged semantics:
 *
 * <ul>
 *   <li>field absent / explicit {@code null} → leave the value unchanged
 *   <li>{@code name} present → must be non-blank (a blank name is a 400, enforced
 *       in {@code ProjectService} since {@code @NotBlank} cannot express
 *       "non-blank only when present" on a nullable record component)
 *   <li>{@code description} present as {@code ""} → clears the description
 * </ul>
 *
 * Max sizes match {@link CreateProjectRequest}: name 200, description 1000.
 */
public record UpdateProjectRequest(
    @Size(max = 200) String name, @Size(max = 1000) String description) {}
