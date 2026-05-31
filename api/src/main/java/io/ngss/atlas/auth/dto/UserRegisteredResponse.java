package io.ngss.atlas.auth.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response for a successful POST /api/auth/register. Permanently token-free
 * (N3): registration does NOT log the user in, so there are no access/refresh
 * token fields. T-012 login introduces its own response DTO.
 */
public record UserRegisteredResponse(UUID id, String email, String displayName, Instant createdAt) {}
