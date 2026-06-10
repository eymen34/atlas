package io.ngss.atlas.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.Objects;
import java.util.UUID;

/**
 * Twelfth JPA entity (T-022). Maps the V9 {@code comment_mentions} join table — one
 * resolved @mention of a user within a comment.
 *
 * <p>AppCDS cold-start hard rule + join_entity_surrogate: a SURROGATE
 * {@code id} (app-generated in the constructor) is used instead of a composite key;
 * {@code commentId}/{@code userId} are plain UUID columns, NOT associations.
 * Uniqueness of {@code (comment_id, user_id)} is enforced by the V9 constraint.
 */
@Entity
@Table(
    name = "comment_mentions",
    uniqueConstraints =
        @UniqueConstraint(
            name = "comment_mentions_comment_user_key",
            columnNames = {"comment_id", "user_id"}))
public class CommentMention {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "comment_id", nullable = false, updatable = false)
  private UUID commentId;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  /** JPA-only no-args constructor. Do not use directly. */
  protected CommentMention() {}

  /** Generates the surrogate id; the (commentId, userId) pair is supplied. */
  public CommentMention(UUID commentId, UUID userId) {
    this.id = UUID.randomUUID();
    this.commentId = Objects.requireNonNull(commentId, "commentId");
    this.userId = Objects.requireNonNull(userId, "userId");
  }

  public UUID getId() {
    return id;
  }

  public UUID getCommentId() {
    return commentId;
  }

  public UUID getUserId() {
    return userId;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof CommentMention other)) {
      return false;
    }
    return id != null && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }
}
