package io.ngss.atlas.domain;

import java.util.UUID;

/**
 * Custom fragment for race-safe per-project ticket numbering (T-017).
 *
 * <p>Implemented with a single native {@code UPDATE ... RETURNING} rather than the
 * pessimistic SELECT-lock-compute-update pattern used elsewhere (T-015's
 * last-admin guard): the counter has NO conditional logic — it is a pure atomic
 * increment — so one statement is tighter, and Postgres's row lock taken by the
 * UPDATE serializes concurrent claimers just as well.
 *
 * <p>This is a fragment (not a derived {@code @Query} method) because the claimed
 * value must come from the {@code RETURNING} projection. A Spring Data
 * {@code @Modifying} method would call {@code executeUpdate()} and return the
 * affected-row count (always 1) — NOT the claimed number — so every ticket would
 * collide on {@code UNIQUE(project_id, number)}. Running the native query through
 * {@code EntityManager.getSingleResult()} returns the {@code RETURNING} scalar.
 */
public interface ProjectTicketCounterRepositoryCustom {

  /**
   * Atomically claims and returns the next ticket number for {@code projectId}.
   * Must run inside the same transaction as the ticket insert.
   *
   * @return the number just claimed (1 for the first ticket in a project)
   * @throws IllegalStateException if no counter row exists for the project (a bug:
   *     the counter is seeded at project creation and backfilled by V6, so it must
   *     always exist) — surfaced loudly rather than as a silent 500.
   */
  int claimNextNumber(UUID projectId);
}
