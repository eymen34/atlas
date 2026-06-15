package io.ngss.atlas.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import org.junit.jupiter.api.Test;

/**
 * AC-4: each reclaim sweep is bounded by {@code MAX_BATCH = 500}, so a backlog larger than the
 * limit drains over successive cron ticks. A 1100-row backlog clears 500 → 500 → 100 across three
 * runs (the third tail is below MAX_BATCH), leaving none PROCESSING.
 */
class RunMaintenanceLimitIT extends OutboxITBase {

  @Test
  void backlogLargerThanBatch_clearsMaxBatchPerRun_overSuccessiveSweeps() {
    // Bulk-seed 1100 below-cap PROCESSING rows, all time-travelled past the threshold. gen_random_uuid()
    // is a PG17 core function (no extension) — fine for a SQL-side bulk seed in tests.
    jdbc.update(
        "INSERT INTO outbox (id, kind, status, payload, attempt_count, updated_at, next_attempt_at) "
            + "SELECT gen_random_uuid(), 'EMAIL_NOTIFICATION', 'PROCESSING', '{}'::jsonb, 0, "
            + "       now() - interval '60 minutes', now() - interval '60 minutes' "
            + "FROM generate_series(1, 1100)");

    // Run 1: a full MAX_BATCH reclaimed, 600 still PROCESSING.
    runMaintenance(DRAIN_SECRET).then().statusCode(200).body("reclaimedToPending", equalTo(500));
    assertThat(stillProcessing()).isEqualTo(600L);

    // Run 2: another full batch, 100 remain.
    runMaintenance(DRAIN_SECRET).then().statusCode(200).body("reclaimedToPending", equalTo(500));
    assertThat(stillProcessing()).isEqualTo(100L);

    // Run 3: the sub-batch tail, backlog fully drained.
    runMaintenance(DRAIN_SECRET).then().statusCode(200).body("reclaimedToPending", equalTo(100));
    assertThat(stillProcessing()).isZero();
  }

  private long stillProcessing() {
    return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE status = 'PROCESSING'", Long.class);
  }
}
