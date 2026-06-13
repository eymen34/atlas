package io.ngss.atlas.outbox;

/**
 * Lifecycle of an outbox row (T-029). {@code PENDING → PROCESSING} on claim;
 * {@code PROCESSING → SENT} on success; {@code PROCESSING → PENDING} on a retryable
 * failure (with a backed-off {@code next_attempt_at}); {@code PROCESSING → FAILED} once
 * the attempt budget is exhausted.
 */
public enum OutboxStatus {
  PENDING,
  PROCESSING,
  SENT,
  FAILED
}
