package io.ngss.atlas.project.dto;

import io.ngss.atlas.domain.ProjectRole;
import jakarta.validation.constraints.NotNull;

/** Request body for PATCH /api/projects/{id}/members/{userId}. */
public record UpdateMemberRoleRequest(@NotNull ProjectRole role) {}
