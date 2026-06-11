package io.ngss.atlas.watcher;

import io.ngss.atlas.domain.TicketWatcher;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for {@link TicketWatcher} (T-023). */
public interface WatcherRepository extends JpaRepository<TicketWatcher, UUID> {

  /**
   * Idempotent insert: a duplicate (ticket_id, user_id) is silently ignored via
   * {@code ON CONFLICT DO NOTHING} — NOT a catch-DataIntegrityViolation retry,
   * which would poison the surrounding transaction (jpa_rollback_only_trap).
   * Returns the affected row count (1 = inserted, 0 = already present).
   */
  @Modifying(clearAutomatically = false, flushAutomatically = false)
  @Query(
      nativeQuery = true,
      value =
          "INSERT INTO ticket_watchers (id, ticket_id, user_id, created_at) "
              + "VALUES (:id, :ticketId, :userId, :createdAt) "
              + "ON CONFLICT (ticket_id, user_id) DO NOTHING")
  int insertIgnoreConflict(
      @Param("id") UUID id,
      @Param("ticketId") UUID ticketId,
      @Param("userId") UUID userId,
      @Param("createdAt") Instant createdAt);

  /** Idempotent delete: removing a non-existent watcher affects 0 rows (no error). */
  @Modifying
  @Query(
      nativeQuery = true,
      value = "DELETE FROM ticket_watchers WHERE ticket_id = :ticketId AND user_id = :userId")
  int deleteByTicketAndUser(@Param("ticketId") UUID ticketId, @Param("userId") UUID userId);

  /** Watcher user ids for a ticket, oldest-first (bare list — small, bounded sets). */
  @Query("SELECT w.userId FROM TicketWatcher w WHERE w.ticketId = :ticketId ORDER BY w.createdAt ASC")
  List<UUID> findUserIdsByTicketId(@Param("ticketId") UUID ticketId);
}
