package io.ngss.atlas.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import org.junit.jupiter.api.Test;

/**
 * AC-4: each reclaim sweep is bounded by {@code MAX_BATCH = 500}. A backlog larger than the limit
 * clears partially per run, so a single sweep of 1100 stuck rows reclaims exactly 500 and leaves
 * 600 PROCESSING for the next cron tick.
 */
class RunMaintenanceLimitIT extends OutboxITBase {

  @Test
  void backlogLargerThanBatch_clearsExactlyMaxBatchPerRun() {
    // Bulk-seed 1100 below-cap PROCESSING rows, all time-travelled past the threshold. gen_random_uuid()
    // is a PG17 core function (no extension) — fine for a SQL-side bulk seed in tests.
    jdbc.update(
        "INSERT INTO outbox (id, kind, status, payload, attempt_count, updated_at, next_attempt_at) "
            + "SELECT gen_random_uuid(), 'EMAIL_NOTIFICATION', 'PROCESSING', '{}'::jsonb, 0, "
            + "       now() - interval '60 minutes', now() - interval '60 minutes' "
            + "FROM generate_series(1, 1100)");

    runMaintenance(DRAIN_SECRET)
        .then()
        .statusCode(200)
        .body("reclaimedToPending", equalTo(500)); // MAX_BATCH

    Long stillProcessing =
        jdbc.queryForObject("SELECT count(*) FROM outbox WHERE status = 'PROCESSING'", Long.class);
    assertThat(stillProcessing).isEqualTo(600L); // 1100 - 500
  }
}
