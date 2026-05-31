package io.ngss.atlas.auth.dto;

public record AuthResponse(String accessToken, String refreshToken, long expiresIn) {}
