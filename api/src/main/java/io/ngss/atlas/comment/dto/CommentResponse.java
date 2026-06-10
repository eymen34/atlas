package io.ngss.atlas.comment.dto;

import io.ngss.atlas.domain.Comment;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A comment as returned by the comment endpoints.
 *
 * <p>D5 server-redaction: a soft-deleted comment is still RETURNED (so the timeline
 * keeps its place), but with {@code body = null}, {@code deleted = true}, and no
 * mention ids — the original text never leaves the server once deleted.
 *
 * @param mentionedUserIds the SERVER-resolved mentioned member ids (D4); the
 *     client's mention metadata is never trusted or echoed.
 */
public record CommentResponse(
    UUID id,
    UUID ticketId,
    UUID authorId,
    String body,
    boolean deleted,
    List<UUID> mentionedUserIds,
    Instant createdAt,
    Instant updatedAt) {

  public static CommentResponse from(Comment comment, List<UUID> mentionedUserIds) {
    boolean deleted = comment.isDeleted();
    return new CommentResponse(
        comment.getId(),
        comment.getTicketId(),
        comment.getAuthorId(),
        deleted ? null : comment.getBody(),
        deleted,
        deleted ? List.of() : List.copyOf(mentionedUserIds),
        comment.getCreatedAt(),
        comment.getUpdatedAt());
  }
}
