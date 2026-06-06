package io.ngss.atlas.project.dto;

import io.ngss.atlas.domain.Project;
import io.ngss.atlas.domain.ProjectRole;
import java.time.Instant;
import java.util.UUID;

/**
 * Response body for project endpoints. Soft-delete state is never exposed.
 *
 * <p>T-016: every project view also carries the authenticated caller's role
 * ({@code callerRole} — always populated, since only members can read a project)
 * and the total {@code memberCount}. Both are derived per request and are never
 * persisted on the {@link Project} entity.
 */
public record ProjectResponse(
    UUID id,
    String key,
    String name,
    String description,
    UUID createdBy,
    Instant createdAt,
    Instant updatedAt,
    ProjectRole callerRole,
    long memberCount) {

  public static ProjectResponse from(Project p, ProjectRole callerRole, long memberCount) {
    return new ProjectResponse(
        p.getId(),
        p.getKey(),
        p.getName(),
        p.getDescription(),
        p.getCreatedBy(),
        p.getCreatedAt(),
        p.getUpdatedAt(),
        callerRole,
        memberCount);
  }
}
