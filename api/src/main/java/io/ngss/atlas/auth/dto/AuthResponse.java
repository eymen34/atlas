package io.ngss.atlas.auth.dto;

/**
 * Auth token response (T-012). The refresh token is NO LONGER in the body (T-048) — it is delivered
 * as the HttpOnly {@code atlas_refresh} cookie ({@link io.ngss.atlas.auth.AuthCookieFactory}) so JS
 * can never read it. Only the short-lived access token + its TTL are returned to the client.
 */
public record AuthResponse(String accessToken, long expiresIn) {}
