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
   * T-015: live projects the user is a member of (replaces the T-014
   * created_by-scoped listing). Subquery against project_members; soft-deleted
   * projects are excluded.
   */
  @Query(
      "SELECT p FROM Project p WHERE p.deletedAt IS NULL AND p.id IN "
          + "(SELECT pm.projectId FROM ProjectMember pm WHERE pm.userId = :userId) "
          + "ORDER BY p.createdAt DESC")
  List<Project> findLiveProjectsForMember(@Param("userId") UUID userId);
}
