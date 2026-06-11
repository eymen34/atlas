package io.ngss.atlas.mention;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Application event published when @mentions are persisted for a ticket description
 * or a comment (T-024). ONE class with a nested {@link Kind} discriminator — a
 * separate {@code comment.event} package is deliberately NOT created (BLOCKING-2).
 * Consumed AFTER_COMMIT by the notification fan-out; published only when
 * {@code mentionedUserIds} is non-empty.
 *
 * @param kind whether the mentions are on a ticket description or a comment
 * @param ticketId the ticket carrying the mentions
 * @param projectId the ticket's project (denormalized)
 * @param commentId the comment id for {@link Kind#COMMENT}, else null
 * @param mentionedUserIds the mentioned user ids — ALREADY DIFFED (only newly-added
 *     users on an edit), so the listener notifies exactly this set (minus the actor)
 * @param actorId the caller who authored the mention
 * @param sourceEventId the COMMENT_ADDED/COMMENT_EDITED activity row id, or null for
 *     a description change (no DESCRIPTION_CHANGED activity type exists — CORRECTION-A)
 * @param occurredAt when the mentions were persisted
 */
public record MentionsPersistedEvent(
    Kind kind,
    UUID ticketId,
    UUID projectId,
    UUID commentId,
    Set<UUID> mentionedUserIds,
    UUID actorId,
    UUID sourceEventId,
    Instant occurredAt) {

  public enum Kind {
    TICKET,
    COMMENT
  }
}
