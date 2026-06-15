package io.ngss.atlas.security;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Native-SQL access to {@code login_attempts} (T-033). The table is native-only (no JPA
 * {@code @Entity}; entity count stays 17).
 *
 * <p>JdbcTemplate (NOT {@code @PersistenceContext EntityManager}) — {@link #deleteExpiredRows}
 * runs under {@code @Transactional(NEVER)} from {@code MaintenanceService.runMaintenance};
 * {@code EntityManager.executeUpdate} throws {@code TransactionRequiredException} outside a tx,
 * whereas {@link NamedParameterJdbcTemplate} autocommits per statement. This also makes
 * {@code upsertFailedAttempt} commit independently of the login's 401, so a failed attempt is
 * never lost to a rollback (mirrors T-053's {@code MaintenanceRepository}).
 */
@Repository
public class LoginAttemptRepository {

  /**
   * Atomic failed-attempt counter (jpa_rollback_only_trap: native UPSERT, no read-modify-write).
   * BOTH arms evaluate the threshold so {@code LOGIN_MAX_ATTEMPTS=1} locks on the very first
   * attempt. A stale window (first_attempt_at older than the window) resets the counter to 1.
   * All time math uses DB {@code now()} via {@code make_interval}, never a host Instant.
   * {@code lockoutMinutes} is passed equal to {@code windowMinutes} (lockout = window size).
   */
  private static final String UPSERT_SQL =
      """
      INSERT INTO login_attempts (id, attempt_key, key_type, attempt_count, first_attempt_at, locked_until)
      VALUES (gen_random_uuid(), :key, :type, 1, now(),
              CASE WHEN 1 >= :maxAttempts THEN now() + make_interval(mins => :lockoutMinutes) ELSE NULL END)
      ON CONFLICT (attempt_key, key_type) DO UPDATE SET
        attempt_count = CASE
          WHEN login_attempts.first_attempt_at < now() - make_interval(mins => :windowMinutes) THEN 1
          ELSE login_attempts.attempt_count + 1
        END,
        first_attempt_at = CASE
          WHEN login_attempts.first_attempt_at < now() - make_interval(mins => :windowMinutes) THEN now()
          ELSE login_attempts.first_attempt_at
        END,
        locked_until = CASE
          WHEN login_attempts.first_attempt_at < now() - make_interval(mins => :windowMinutes)
            THEN CASE WHEN 1 >= :maxAttempts
                   THEN now() + make_interval(mins => :lockoutMinutes) ELSE NULL END
          WHEN (login_attempts.attempt_count + 1) >= :maxAttempts
            THEN now() + make_interval(mins => :lockoutMinutes)
          ELSE login_attempts.locked_until
        END
      """;

  private final NamedParameterJdbcTemplate jdbc;

  public LoginAttemptRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Records one failed attempt for the (key, type) bucket, locking it once the threshold is hit. */
  public void upsertFailedAttempt(
      String key, LoginAttemptKey type, int maxAttempts, int windowMinutes) {
    jdbc.update(
        UPSERT_SQL,
        new MapSqlParameterSource()
            .addValue("key", key)
            .addValue("type", type.name())
            .addValue("maxAttempts", maxAttempts)
            .addValue("windowMinutes", windowMinutes)
            .addValue("lockoutMinutes", windowMinutes)); // lockout duration = window size
  }

  /** The current counter row for a bucket, if any. */
  public Optional<LoginAttemptRecord> findByKeyAndType(String key, LoginAttemptKey type) {
    return jdbc
        .query(
            "SELECT attempt_count, first_attempt_at, locked_until FROM login_attempts "
                + "WHERE attempt_key = :key AND key_type = :type",
            new MapSqlParameterSource().addValue("key", key).addValue("type", type.name()),
            (rs, rowNum) -> {
              Timestamp lockedUntil = rs.getTimestamp("locked_until");
              return new LoginAttemptRecord(
                  rs.getInt("attempt_count"),
                  rs.getTimestamp("first_attempt_at").toInstant(),
                  lockedUntil == null ? null : lockedUntil.toInstant());
            })
        .stream()
        .findFirst();
  }

  /** Reset-on-success: clears one bucket (the ACCOUNT row; the IP row is intentionally kept). */
  public void deleteByKeyAndType(String key, LoginAttemptKey type) {
    jdbc.update(
        "DELETE FROM login_attempts WHERE attempt_key = :key AND key_type = :type",
        new MapSqlParameterSource().addValue("key", key).addValue("type", type.name()));
  }

  /**
   * Maintenance sweep (T-053): drop rows whose window has fully elapsed and whose lockout (if any)
   * has expired. Returns the number of rows deleted.
   */
  public int deleteExpiredRows(Instant cutoff) {
    return jdbc.update(
        "DELETE FROM login_attempts "
            + "WHERE first_attempt_at < :cutoff AND (locked_until IS NULL OR locked_until < now())",
        new MapSqlParameterSource().addValue("cutoff", Timestamp.from(cutoff)));
  }
}
