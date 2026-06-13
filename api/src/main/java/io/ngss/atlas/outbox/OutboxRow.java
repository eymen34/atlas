package io.ngss.atlas.outbox;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Projection of one {@code outbox} row (T-029). The table is native-SQL-only (no JPA
 * {@code @Entity}; entity count stays 17), so this is a plain record mapped by
 * {@link OutboxRepositoryImpl}. {@code payload} is the parsed jsonb (a Jackson 3
 * {@link JsonNode}); handlers deserialize it to their typed payload.
 */
public record OutboxRow(
    UUID id,
    OutboxKind kind,
    OutboxStatus status,
    JsonNode payload,
    int attemptCount,
    Instant nextAttemptAt,
    String lastError,
    Instant createdAt,
    Instant updatedAt,
    Instant sentAt) {}
