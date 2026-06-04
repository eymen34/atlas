package io.ngss.atlas.project.dto;

import io.ngss.atlas.domain.Project;
import java.time.Instant;
import java.util.UUID;

/** Response body for project endpoints. Soft-delete state is never exposed. */
public record ProjectResponse(
    UUID id,
    String key,
    String name,
    String description,
    UUID createdBy,
    Instant createdAt,
    Instant updatedAt) {

  public static ProjectResponse from(Project p) {
    return new ProjectResponse(
        p.getId(),
        p.getKey(),
        p.getName(),
        p.getDescription(),
        p.getCreatedBy(),
        p.getCreatedAt(),
        p.getUpdatedAt());
  }
}
