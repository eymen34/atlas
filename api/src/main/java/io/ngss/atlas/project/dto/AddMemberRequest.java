package io.ngss.atlas.project.dto;

import io.ngss.atlas.domain.ProjectRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for POST /api/projects/{id}/members. The email must belong to an
 * already-registered user (a 404 is returned if not). An invalid {@code role}
 * string fails enum deserialization and is mapped to a 400.
 */
public record AddMemberRequest(
    @NotBlank @Email String email, @NotNull ProjectRole role) {}
