package io.ngss.atlas.comment;

import io.ngss.atlas.domain.Comment;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for {@link Comment} (T-022). */
public interface CommentRepository extends JpaRepository<Comment, UUID> {

  /**
   * Newest-first page of a ticket's comments. Soft-deleted rows ARE included (D5:
   * server-redacted on the way out, never filtered here). The {@code id DESC}
   * tiebreaker makes the order stable when timestamps collide.
   */
  @Query("SELECT c FROM Comment c WHERE c.ticketId = :ticketId ORDER BY c.createdAt DESC, c.id DESC")
  Page<Comment> findByTicketIdOrderByCreatedAtDescIdDesc(
      @Param("ticketId") UUID ticketId, Pageable pageable);

  /** A live (non-deleted) comment by id, for edit/delete. */
  Optional<Comment> findByIdAndDeletedAtIsNull(UUID id);
}
