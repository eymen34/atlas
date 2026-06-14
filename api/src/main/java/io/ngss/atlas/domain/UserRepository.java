package io.ngss.atlas.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {

  /**
   * Duplicate-email pre-check for registration. Phrased as {@code lower(u.email) =
   * lower(:email)} so the generated SQL matches the V1 {@code users_email_lower_key}
   * functional unique index (an index on {@code lower(email)}). A derived
   * {@code existsByEmailIgnoreCase} would instead compile to {@code UPPER(email) =
   * UPPER(?)}, which cannot use that index and forces a sequential scan.
   */
  @Query(
      "select case when count(u) > 0 then true else false end "
          + "from User u where lower(u.email) = lower(:email)")
  boolean existsByEmailLower(@Param("email") String email);

  Optional<User> findByEmailIgnoreCase(String email);

  /** Mention-handle uniqueness pre-check (T-022); the V9 unique index is the backstop. */
  boolean existsByMentionHandle(String mentionHandle);

  /**
   * Resolves @mention handles to the ids of the project's members (T-022). Uses an
   * ad-hoc entity JOIN to {@code ProjectMember} (no association on the entity) — the
   * Hibernate 6.6 unassociated-join form already used by
   * {@code ProjectMemberRepository.findMemberResponsesByProjectId}. {@code handles}
   * are expected lowercase (mention_handle is stored lowercase).
   */
  @Query(
      "SELECT u.id FROM ProjectMember pm JOIN User u ON u.id = pm.userId "
          + "WHERE pm.projectId = :projectId AND lower(u.mentionHandle) IN :handles")
  List<UUID> findMemberIdsByProjectIdAndHandles(
      @Param("projectId") UUID projectId, @Param("handles") Collection<String> handles);
}
