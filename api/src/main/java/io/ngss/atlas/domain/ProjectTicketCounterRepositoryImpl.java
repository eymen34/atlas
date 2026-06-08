package io.ngss.atlas.domain;

import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;

/**
 * Implementation of {@link ProjectTicketCounterRepositoryCustom}. Spring Data
 * wires this fragment by the {@code Impl} naming convention (fragment-interface
 * name + {@code Impl}) and supplies the {@link EntityManager}.
 */
public class ProjectTicketCounterRepositoryImpl implements ProjectTicketCounterRepositoryCustom {

  // Pre-increment claim: bump next_number, then RETURN the value we just consumed
  // (next_number - 1, evaluated on the POST-update row). Seed is 1, so the first
  // claim returns 1 and leaves next_number = 2. The UPDATE's row lock serializes
  // concurrent claimers on the single counter row, guaranteeing distinct numbers.
  private static final String CLAIM_SQL =
      "UPDATE project_ticket_counters SET next_number = next_number + 1 "
          + "WHERE project_id = :projectId RETURNING next_number - 1";

  @PersistenceContext private EntityManager entityManager;

  @Override
  public int claimNextNumber(UUID projectId) {
    try {
      Object claimed =
          entityManager
              .createNativeQuery(CLAIM_SQL)
              .setParameter("projectId", projectId)
              .getSingleResult();
      return ((Number) claimed).intValue();
    } catch (NoResultException missing) {
      // No counter row matched the WHERE — the counter is seeded at project
      // creation and backfilled by V6, so this is a bug, not a client error.
      throw new IllegalStateException(
          "no ticket counter for project " + projectId + " (should be seeded at create/backfill)",
          missing);
    }
  }
}
