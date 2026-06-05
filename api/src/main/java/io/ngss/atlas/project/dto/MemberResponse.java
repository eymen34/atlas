package io.ngss.atlas.project.dto;

import io.ngss.atlas.domain.ProjectRole;
import java.time.Instant;
import java.util.UUID;

/**
 * A project membership as returned by the member endpoints. The field order and
 * types MUST match the JPQL constructor projection in
 * {@code ProjectMemberRepository.findMemberResponsesByProjectId}.
 */
public record MemberResponse(
    UUID userId,
    String email,
    String displayName,
    ProjectRole role,
    UUID invitedBy,
    Instant createdAt) {}
