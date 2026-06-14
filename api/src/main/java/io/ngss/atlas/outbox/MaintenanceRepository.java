package io.ngss.atlas.outbox;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Native-SQL maintenance sweeps (T-053). Uses {@link NamedParameterJdbcTemplate} rather than
 * the {@code @PersistenceContext EntityManager} pattern of {@link OutboxRepositoryImpl}: the
 * sole caller, {@code MaintenanceService.runMaintenance()}, runs with {@code propagation=NEVER},
 * so each sweep statement runs in its own autocommit (no shared transaction holding locks).
 *
 * <p>ALL time comparisons use DB {@code now()} via {@code make_interval(...)} — never a
 * host-side {@code Instant} (outbox_drain_state_machine). Every reclaim {@code UPDATE} sets
 * {@code updated_at = now()} EXPLICITLY. The reclaim sweeps {@code FOR UPDATE SKIP LOCKED} so
 * a concurrent drain never double-touches a row, and a {@code LIMIT} bounds each pass.
 */
@Repository
public class MaintenanceRepository {

  /** Stuck PROCESSING rows below the attempt cap → PENDING (next_attempt_at refreshed). */
  private static final String RECLAIM_TO_PENDING_SQL =
      """
      WITH eligible AS (
        SELECT id FROM outbox
        WHERE status = 'PROCESSING'
          AND updated_at < now() - make_interval(mins => :mins)
          AND attempt_count < :cap
        ORDER BY id
        FOR UPDATE SKIP LOCKED
        LIMIT :batch
      )
      UPDATE outbox o
        SET status = 'PENDING', next_attempt_at = now(), updated_at = now()
        FROM eligible e WHERE o.id = e.id
      """;

  /** Stuck PROCESSING rows at/above the attempt cap → FAILED (terminal; next_attempt_at left as-is). */
  private static final String RECLAIM_TO_FAILED_SQL =
      """
      WITH eligible AS (
        SELECT id FROM outbox
        WHERE status = 'PROCESSING'
          AND updated_at < now() - make_interval(mins => :mins)
          AND attempt_count >= :cap
        ORDER BY id
        FOR UPDATE SKIP LOCKED
        LIMIT :batch
      )
      UPDATE outbox o
        SET status = 'FAILED', updated_at = now()
        FROM eligible e WHERE o.id = e.id
      """;

  /** Never-finalized PENDING attachment uploads older than the expiry window, not yet deleted. */
  private static final String SELECT_EXPIRED_PENDING_SQL =
      """
      SELECT id FROM attachments
      WHERE status = 'PENDING'
        AND created_at < now() - make_interval(hours => :hours)
        AND deleted_at IS NULL
      ORDER BY id
      LIMIT :batch
      """;

  private final NamedParameterJdbcTemplate jdbc;

  public MaintenanceRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** @return the number of PROCESSING rows reclaimed to PENDING this pass. */
  public int reclaimToPending(int afterMinutes, int batchLimit, int attemptCap) {
    return jdbc.update(
        RECLAIM_TO_PENDING_SQL,
        new MapSqlParameterSource()
            .addValue("mins", afterMinutes)
            .addValue("cap", attemptCap)
            .addValue("batch", batchLimit));
  }

  /** @return the number of PROCESSING rows moved to FAILED this pass. */
  public int reclaimToFailed(int afterMinutes, int batchLimit, int attemptCap) {
    return jdbc.update(
        RECLAIM_TO_FAILED_SQL,
        new MapSqlParameterSource()
            .addValue("mins", afterMinutes)
            .addValue("cap", attemptCap)
            .addValue("batch", batchLimit));
  }

  /** @return up to {@code batchLimit} ids of expired, still-live PENDING attachment uploads. */
  public List<UUID> selectExpiredPendingAttachmentIds(int afterHours, int batchLimit) {
    return jdbc.queryForList(
        SELECT_EXPIRED_PENDING_SQL,
        new MapSqlParameterSource().addValue("hours", afterHours).addValue("batch", batchLimit),
        UUID.class);
  }
}
