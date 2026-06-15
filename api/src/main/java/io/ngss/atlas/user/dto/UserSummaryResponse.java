package io.ngss.atlas.user.dto;

import java.util.UUID;

/**
 * Minimal, display-only user projection (T-044): id + displayName ONLY.
 *
 * <p>Deliberately NOT {@code UserProfileResponse} / {@code UserRegisteredResponse}
 * (both carry email + createdAt). This is the actor-lookup fallback used to render
 * the NAME of an author who has left a project, exposed to any authenticated caller
 * (walkable by id), so it must never leak email, role, or any credential/PII field.
 */
public record UserSummaryResponse(UUID id, String displayName) {}
