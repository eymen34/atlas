package io.ngss.atlas.auth.dto;

import java.time.Instant;
import java.util.UUID;

public record UserProfileResponse(UUID id, String email, Instant createdAt) {}
