package io.ngss.atlas.comment;

import io.ngss.atlas.domain.CommentMention;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for {@link CommentMention} (T-022). */
public interface CommentMentionRepository extends JpaRepository<CommentMention, UUID> {

  /** Removes all mentions for a comment (edit re-derives; delete clears). */
  @Modifying
  @Query("DELETE FROM CommentMention cm WHERE cm.commentId = :commentId")
  void deleteByCommentId(@Param("commentId") UUID commentId);

  /** The resolved mention rows for the given comments — batch-loaded for a list page. */
  List<CommentMention> findByCommentIdIn(Collection<UUID> commentIds);

  /** Current mention user ids for a comment (T-024: captured BEFORE re-mention diff). */
  @Query("SELECT cm.userId FROM CommentMention cm WHERE cm.commentId = :commentId")
  List<UUID> findUserIdsByCommentId(@Param("commentId") UUID commentId);
}
