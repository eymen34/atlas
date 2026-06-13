package io.ngss.atlas.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Native-SQL access to the {@code outbox} table (T-029). Defined as an interface with a
 * hand-written {@link OutboxRepositoryImpl} (NOT Spring Data) — the canonical native-only
 * repo pattern (counter_returning_pattern), since {@code outbox} is not a JPA entity.
 */
public interface OutboxRepository {

  /** Inserts a PENDING row (app-generated id, DB-default timestamps); returns its id. */
  UUID enqueue(OutboxKind kind, JsonNode payload);

  /**
   * Atomically claims up to {@code max} due PENDING rows, flipping them to PROCESSING and
   * returning them. Uses {@code FOR UPDATE SKIP LOCKED} so concurrent drains pick DISJOINT
   * subsets. The due check ({@code next_attempt_at <= now()}) uses the DATABASE clock to
   * avoid host/container skew.
   */
  List<OutboxRow> claimBatch(int max);

  /** PROCESSING → SENT, stamping {@code sent_at} and {@code updated_at}. */
  void markSent(UUID id, Instant now);

  /** PROCESSING → PENDING with a bumped attempt count, backed-off next attempt, and error. */
  void scheduleRetry(
      UUID id, int newAttemptCount, Instant nextAttemptAt, String lastError, Instant now);

  /** PROCESSING → FAILED (attempt budget exhausted), recording the last error. */
  void markFailed(UUID id, String lastError, Instant now);

  /** Count of rows in a given status (test/observability helper). */
  long countByStatus(OutboxStatus status);
}
