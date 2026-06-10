package io.ngss.atlas.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {

  // N5: existsByEmailIgnoreCase derives UPPER(email)=UPPER(?), which does NOT
  // use the V1 lower(email) functional index (users_email_lower_key). Acceptable
  // while the table is small; replace with an explicit lower()-based @Query when
  // the table grows (auth performance backlog).
  boolean existsByEmailIgnoreCase(String email);

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
