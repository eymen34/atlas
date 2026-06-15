package io.ngss.atlas.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * AC-1 / EC-8: the PROCESSING-reclaim half of {@code POST /internal/tasks/run-maintenance}.
 * Rows are time-travelled via a raw {@code UPDATE} (DB now(), never a host Instant) before the
 * sweep. Default {@code OUTBOX_RECLAIM_AFTER_MINUTES=15}; the env-override is in
 * {@link RunMaintenanceEnvOverrideIT}.
 */
class RunMaintenanceReclaimIT extends OutboxITBase {

  /** Seeds one outbox row of the given status/attempt and ages BOTH timestamps by {@code age}. */
  private UUID seedRow(String status, int attemptCount, String age) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO outbox (id, kind, status, payload, attempt_count) "
            + "VALUES (?::uuid, 'EMAIL_NOTIFICATION', ?, '{}'::jsonb, ?)",
        id.toString(),
        status,
        attemptCount);
    jdbc.update(
        "UPDATE outbox SET updated_at = now() - CAST(? AS interval), "
            + "next_attempt_at = now() - CAST(? AS interval) WHERE id = ?::uuid",
        age,
        age,
        id.toString());
    return id;
  }

  private boolean isRecent(UUID id, String column) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT " + column + " >= now() - interval '2 minutes' FROM outbox WHERE id = ?::uuid",
            Boolean.class,
            id.toString()));
  }

  // ── AC-1.1: old + below-cap → PENDING (next_attempt_at + updated_at refreshed, attempt kept) ──
  @Test
  void oldBelowCapProcessing_reclaimedToPending() {
    UUID id = seedRow("PROCESSING", 3, "60 minutes");

    runMaintenance(DRAIN_SECRET)
        .then()
        .statusCode(200)
        .body("reclaimedToPending", equalTo(1))
        .body("reclaimedToFailed", equalTo(0))
        .body("expiredUploads", equalTo(0));

    assertThat(outboxStatus(id)).isEqualTo("PENDING");
    assertThat(outboxAttemptCount(id)).isEqualTo(3); // attempt_count UNCHANGED
    assertThat(isRecent(id, "next_attempt_at")).as("next_attempt_at refreshed to now()").isTrue();
    assertThat(isRecent(id, "updated_at")).as("updated_at refreshed to now()").isTrue();
  }

  // ── AC-1.2: old + at-cap → FAILED (updated_at refreshed; next_attempt_at NOT changed) ──
  @Test
  void oldAtCapProcessing_reclaimedToFailed() {
    UUID id = seedRow("PROCESSING", 10, "60 minutes");

    runMaintenance(DRAIN_SECRET)
        .then()
        .statusCode(200)
        .body("reclaimedToFailed", equalTo(1))
        .body("reclaimedToPending", equalTo(0));

    assertThat(outboxStatus(id)).isEqualTo("FAILED");
    assertThat(isRecent(id, "updated_at")).as("updated_at refreshed").isTrue();
    assertThat(isRecent(id, "next_attempt_at"))
        .as("next_attempt_at NOT changed for terminal FAILED")
        .isFalse();
  }

  // ── boundary: attempt_count=9 → PENDING, attempt_count=10 → FAILED (the < vs >= cap split) ──
  @Test
  void attemptCapBoundary_nineToPending_tenToFailed() {
    UUID below = seedRow("PROCESSING", 9, "60 minutes");
    UUID at = seedRow("PROCESSING", 10, "60 minutes");

    runMaintenance(DRAIN_SECRET)
        .then()
        .statusCode(200)
        .body("reclaimedToPending", equalTo(1))
        .body("reclaimedToFailed", equalTo(1));

    assertThat(outboxStatus(below)).isEqualTo("PENDING");
    assertThat(outboxStatus(at)).isEqualTo("FAILED");
  }

  // ── EC-1: a fresh PROCESSING row within the threshold is NOT reclaimed ──
  @Test
  void freshProcessingWithinThreshold_notReclaimed() {
    UUID id = seedRow("PROCESSING", 3, "5 minutes"); // < 15-min default

    runMaintenance(DRAIN_SECRET)
        .then()
        .statusCode(200)
        .body("reclaimedToPending", equalTo(0))
        .body("reclaimedToFailed", equalTo(0));

    assertThat(outboxStatus(id)).isEqualTo("PROCESSING");
  }

  // ── terminal/queued rows are never touched by reclaim ──
  @Test
  void failedAndSentRows_areUntouched() {
    UUID failed = seedRow("FAILED", 10, "60 minutes");
    UUID sent = seedRow("SENT", 1, "60 minutes");
    UUID pending = seedRow("PENDING", 0, "60 minutes");

    runMaintenance(DRAIN_SECRET)
        .then()
        .statusCode(200)
        .body("reclaimedToPending", equalTo(0))
        .body("reclaimedToFailed", equalTo(0));

    assertThat(outboxStatus(failed)).isEqualTo("FAILED");
    assertThat(outboxStatus(sent)).isEqualTo("SENT");
    assertThat(outboxStatus(pending)).isEqualTo("PENDING"); // only PROCESSING is reclaimed
  }

  // ── EC-8: a second sweep over already-reclaimed rows is a no-op ──
  @Test
  void secondSweepIsIdempotent() {
    seedRow("PROCESSING", 3, "60 minutes");
    runMaintenance(DRAIN_SECRET).then().statusCode(200).body("reclaimedToPending", equalTo(1));

    runMaintenance(DRAIN_SECRET)
        .then()
        .statusCode(200)
        .body("reclaimedToPending", equalTo(0))
        .body("reclaimedToFailed", equalTo(0));
  }
}
