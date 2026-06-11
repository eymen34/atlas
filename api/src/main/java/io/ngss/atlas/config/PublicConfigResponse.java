package io.ngss.atlas.config;

/**
 * Unauthenticated public configuration (T-023). Exposes ONLY non-sensitive feature
 * flags the frontend needs before login — nothing else may be added here. Lives in
 * the flat {@code io.ngss.atlas.config} package (NOT config/dto/).
 */
public record PublicConfigResponse(FeaturesDto features) {}
