package io.ngss.atlas.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link Ticket} (T-017; T-018 added
 * {@link JpaSpecificationExecutor} for the dynamic, multi-valued, paged list).
 * Single-row finders are scoped to live (non-soft-deleted) rows via the
 * {@code AndDeletedAtIsNull} suffix, mirroring the soft-delete model in V6.
 *
 * <p>The T-017 {@code findFiltered} {@code :param IS NULL OR …} query was removed
 * in T-018 — dynamic filtering now lives in {@code TicketSpecifications} and runs
 * through {@code findAll(Specification, Pageable)}.
 */
public interface TicketRepository
    extends JpaRepository<Ticket, UUID>, JpaSpecificationExecutor<Ticket> {

  Optional<Ticket> findByIdAndDeletedAtIsNull(UUID id);

  /**
   * Resolves a live ticket by its display key {@code (projectKey, number)} — e.g.
   * {@code ENG-42}. Joins {@link Project} (no association on the entity) and
   * requires BOTH the ticket and its project to be live, so a soft-deleted
   * project's tickets never resolve.
   */
  @Query(
      "SELECT t FROM Ticket t JOIN Project p ON p.id = t.projectId "
          + "WHERE p.key = :projectKey AND t.number = :number "
          + "AND t.deletedAt IS NULL AND p.deletedAt IS NULL")
  Optional<Ticket> findByProjectKeyAndNumberAndDeletedAtIsNull(
      @Param("projectKey") String projectKey, @Param("number") int number);
}
