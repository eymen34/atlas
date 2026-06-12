package io.ngss.atlas.notification;

import io.ngss.atlas.domain.Notification;
import io.ngss.atlas.domain.NotificationKind;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for {@link Notification} (T-024). */
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

  Page<Notification> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

  Page<Notification> findByUserIdAndReadAtIsNullOrderByCreatedAtDesc(UUID userId, Pageable pageable);

  /**
   * Caller-scoped mark-read (IDOR-safe): the {@code user_id = :callerId} predicate
   * means another user's notification is never touched — a foreign id affects 0
   * rows, which the service maps to 404.
   */
  @Modifying
  @Query("UPDATE Notification n SET n.readAt = :now WHERE n.id = :id AND n.userId = :callerId")
  int markRead(@Param("id") UUID id, @Param("callerId") UUID callerId, @Param("now") Instant now);

  @Modifying
  @Query("UPDATE Notification n SET n.readAt = :now WHERE n.userId = :callerId AND n.readAt IS NULL")
  int markAllRead(@Param("callerId") UUID callerId, @Param("now") Instant now);

  /**
   * Dedup window: true if a notification with the SAME (user, kind, ticket) was
   * created within the window. Tuple is (user_id, kind, ticket_id) — actor is NOT
   * part of it, so same-kind triggers from different actors in one window collapse
   * to one notification (accepted; see docs/notifications.md).
   */
  @Query(
      "SELECT COUNT(n) > 0 FROM Notification n "
          + "WHERE n.userId = :userId AND n.kind = :kind AND n.ticketId = :ticketId "
          + "AND n.createdAt > :since")
  boolean existsDedupWindow(
      @Param("userId") UUID userId,
      @Param("kind") NotificationKind kind,
      @Param("ticketId") UUID ticketId,
      @Param("since") Instant since);
}
