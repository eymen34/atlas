package io.ngss.atlas.domain;

import io.ngss.atlas.project.dto.MemberResponse;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for {@link ProjectMember} (T-015). */
public interface ProjectMemberRepository extends JpaRepository<ProjectMember, UUID> {

  Optional<ProjectMember> findByProjectIdAndUserId(UUID projectId, UUID userId);

  List<ProjectMember> findByProjectId(UUID projectId);

  List<ProjectMember> findByUserId(UUID userId);

  long countByProjectIdAndRole(UUID projectId, ProjectRole role);

  boolean existsByProjectIdAndUserId(UUID projectId, UUID userId);

  /**
   * Pessimistic-locks all ADMIN rows of a project (SELECT ... FOR UPDATE),
   * serializing concurrent demote/remove so the last-admin guard is race-safe:
   * the size check runs only after this lock is acquired inside the transaction.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "SELECT pm FROM ProjectMember pm "
          + "WHERE pm.projectId = :projectId AND pm.role = io.ngss.atlas.domain.ProjectRole.ADMIN")
  List<ProjectMember> lockAdminsForProject(@Param("projectId") UUID projectId);

  /**
   * One-round-trip member listing: ad-hoc entity JOIN to {@code User} (no
   * association on the entity) projected straight into {@link MemberResponse}.
   * Constructor argument order/types MUST match the record exactly:
   * (UUID, String, String, ProjectRole, UUID, Instant).
   */
  @Query(
      "SELECT new io.ngss.atlas.project.dto.MemberResponse("
          + "pm.userId, u.email, u.displayName, pm.role, pm.invitedBy, pm.createdAt) "
          + "FROM ProjectMember pm JOIN User u ON u.id = pm.userId "
          + "WHERE pm.projectId = :projectId "
          + "ORDER BY pm.createdAt ASC")
  List<MemberResponse> findMemberResponsesByProjectId(@Param("projectId") UUID projectId);
}
