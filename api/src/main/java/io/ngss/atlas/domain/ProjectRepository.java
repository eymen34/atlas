package io.ngss.atlas.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link Project}. Single-row finders are scoped to
 * live (non-soft-deleted) rows via the {@code AndDeletedAtIsNull} suffix,
 * mirroring the soft-delete model in V4.
 */
public interface ProjectRepository extends JpaRepository<Project, UUID> {

  Optional<Project> findByIdAndDeletedAtIsNull(UUID id);

  Optional<Project> findByKeyAndDeletedAtIsNull(String key);

  boolean existsByKeyAndDeletedAtIsNull(String key);

  /**
   * T-016: one-round-trip listing of the live projects the user is a member of,
   * carrying the caller's role and the project's total member count alongside
   * each project. Returns a tuple {@code Object[]} of {@code (Project,
   * ProjectRole, Long)}.
   *
   * <p>The {@link Project} root entity is selected directly rather than via a
   * {@code SELECT new ...(p, ...)} constructor expression: Hibernate 6.6 does not
   * support an entity argument in a constructor expression, and projecting
   * {@code p.key} individually would collide with the reserved JPQL {@code KEY}
   * word. The member count is a correlated scalar subquery, so the whole listing
   * is ONE SQL statement (no N+1 — enforced by PERF-1). Mirrors the ad-hoc
   * {@code JOIN ... ON} pattern of
   * {@link ProjectMemberRepository#findMemberResponsesByProjectId}.
   */
  @Query(
      "SELECT p, m.role, "
          + "(SELECT COUNT(m2) FROM ProjectMember m2 WHERE m2.projectId = p.id) "
          + "FROM Project p JOIN ProjectMember m ON m.projectId = p.id "
          + "WHERE m.userId = :userId AND p.deletedAt IS NULL "
          + "ORDER BY p.createdAt DESC")
  List<Object[]> findProjectListRowsForMember(@Param("userId") UUID userId);
}
