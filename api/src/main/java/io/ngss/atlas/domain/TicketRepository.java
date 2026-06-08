package io.ngss.atlas.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link Ticket} (T-017). Single-row finders are scoped
 * to live (non-soft-deleted) rows via the {@code AndDeletedAtIsNull} suffix,
 * mirroring the soft-delete model in V6 / {@link ProjectRepository}.
 */
public interface TicketRepository extends JpaRepository<Ticket, UUID> {

  Optional<Ticket> findByIdAndDeletedAtIsNull(UUID id);

  /**
   * Resolves a live ticket by its display key {@code (projectKey, number)} — e.g.
   * {@code ENG-42}. Joins {@link Project} (no association on the entity, same
   * ad-hoc {@code JOIN ... ON} pattern as
   * {@link ProjectMemberRepository#findMemberResponsesByProjectId}) and requires
   * BOTH the ticket and its project to be live, so a soft-deleted project's
   * tickets never resolve.
   */
  @Query(
      "SELECT t FROM Ticket t JOIN Project p ON p.id = t.projectId "
          + "WHERE p.key = :projectKey AND t.number = :number "
          + "AND t.deletedAt IS NULL AND p.deletedAt IS NULL")
  Optional<Ticket> findByProjectKeyAndNumberAndDeletedAtIsNull(
      @Param("projectKey") String projectKey, @Param("number") int number);

  /**
   * Project-scoped listing with optional filters (T-017). A null filter argument
   * is ignored (the {@code :param IS NULL OR ...} guard), so status / assigneeId /
   * priority compose. Excludes soft-deleted tickets; default sort is
   * {@code updated_at DESC}. The {@code q} (search, T-018) and {@code label}
   * (T-028) filters are accepted by the API but NOT passed here — they are no-op
   * stubs until those tickets land.
   */
  @Query(
      "SELECT t FROM Ticket t "
          + "WHERE t.projectId = :projectId AND t.deletedAt IS NULL "
          + "AND (:status IS NULL OR t.status = :status) "
          + "AND (:assigneeId IS NULL OR t.assigneeId = :assigneeId) "
          + "AND (:priority IS NULL OR t.priority = :priority) "
          + "ORDER BY t.updatedAt DESC")
  List<Ticket> findFiltered(
      @Param("projectId") UUID projectId,
      @Param("status") TicketStatus status,
      @Param("assigneeId") UUID assigneeId,
      @Param("priority") TicketPriority priority);
}
