package io.ngss.atlas.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** Body for POST /api/auth/logout — the raw refresh token to revoke. */
public record LogoutRequest(@NotBlank String refreshToken) {}
