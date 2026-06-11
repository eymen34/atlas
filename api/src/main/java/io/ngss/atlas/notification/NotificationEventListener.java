package io.ngss.atlas.notification;

import io.ngss.atlas.domain.Notification;
import io.ngss.atlas.domain.NotificationKind;
import io.ngss.atlas.mention.MentionsPersistedEvent;
import io.ngss.atlas.ticket.event.TicketAssignedEvent;
import io.ngss.atlas.ticket.event.TicketTransitionedEvent;
import io.ngss.atlas.watcher.WatcherRepository;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Fans events out into notification rows (T-024).
 *
 * <p>Each handler is {@code @TransactionalEventListener(AFTER_COMMIT)} +
 * {@code @Transactional(REQUIRES_NEW)} (on the METHOD, independently) — the inserts
 * run in a NEW transaction AFTER the originating change has committed. Without
 * REQUIRES_NEW there is no active transaction at AFTER_COMMIT time and the saves
 * would silently no-op (after_commit_requires_new). Each handler wraps its body in
 * {@code try/catch(Exception)} + ERROR log so a fan-out failure NEVER fails the
 * originating HTTP request (the core write already committed).
 *
 * <p>Policy: ASSIGNED skips self-assign; MENTIONED_* skips the actor BEFORE dedup;
 * WATCHED loads watchers and skips the actor. Dedup tuple is (user, kind, ticket)
 * within a 60s window — actor is NOT in the tuple (see docs/notifications.md).
 */
@Component
public class NotificationEventListener {

  private static final Logger log = LoggerFactory.getLogger(NotificationEventListener.class);
  private static final long DEDUP_WINDOW_SECONDS = 60;

  private final NotificationRepository notificationRepository;
  private final WatcherRepository watcherRepository;
  private final NotificationPayloads payloads;

  public NotificationEventListener(
      NotificationRepository notificationRepository,
      WatcherRepository watcherRepository,
      NotificationPayloads payloads) {
    this.notificationRepository = notificationRepository;
    this.watcherRepository = watcherRepository;
    this.payloads = payloads;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void onTicketAssigned(TicketAssignedEvent e) {
    try {
      if (e.newAssigneeId().equals(e.actorId())) {
        return; // self-assign → no notification
      }
      fanOut(
          e.newAssigneeId(),
          NotificationKind.ASSIGNED,
          e.ticketId(),
          e.sourceEventId(),
          payloads.forAssigned(e.actorId()),
          e.occurredAt());
    } catch (Exception ex) {
      log.error("notification fan-out failed for ASSIGNED ticket={}", e.ticketId(), ex);
    }
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void onMentionsPersisted(MentionsPersistedEvent e) {
    try {
      NotificationKind kind =
          e.kind() == MentionsPersistedEvent.Kind.TICKET
              ? NotificationKind.MENTIONED_TICKET
              : NotificationKind.MENTIONED_COMMENT;
      String payload =
          e.kind() == MentionsPersistedEvent.Kind.TICKET
              ? payloads.forMentionedTicket(e.actorId())
              : payloads.forMentionedComment(e.actorId(), e.commentId());
      for (UUID userId : e.mentionedUserIds()) {
        if (userId.equals(e.actorId())) {
          continue; // self-mention skip — BEFORE dedup
        }
        fanOut(userId, kind, e.ticketId(), e.sourceEventId(), payload, e.occurredAt());
      }
    } catch (Exception ex) {
      log.error("notification fan-out failed for MENTIONED ticket={}", e.ticketId(), ex);
    }
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void onTicketTransitioned(TicketTransitionedEvent e) {
    try {
      String payload =
          payloads.forWatchedStatusChanged(
              e.actorId(), e.fromStatus().name(), e.toStatus().name());
      for (UUID userId : watcherRepository.findUserIdsByTicketId(e.ticketId())) {
        if (userId.equals(e.actorId())) {
          continue; // a watcher who is also the actor is not notified of their own change
        }
        fanOut(
            userId,
            NotificationKind.WATCHED_STATUS_CHANGED,
            e.ticketId(),
            e.sourceEventId(),
            payload,
            e.occurredAt());
      }
    } catch (Exception ex) {
      log.error("notification fan-out failed for WATCHED ticket={}", e.ticketId(), ex);
    }
  }

  /** Inserts one notification, suppressing a same-(user,kind,ticket) row within 60s. */
  private void fanOut(
      UUID userId,
      NotificationKind kind,
      UUID ticketId,
      UUID sourceEventId,
      String payload,
      Instant occurredAt) {
    Instant since = occurredAt.minusSeconds(DEDUP_WINDOW_SECONDS);
    if (notificationRepository.existsDedupWindow(userId, kind, ticketId, since)) {
      return;
    }
    notificationRepository.save(
        new Notification(
            UUID.randomUUID(), userId, kind, ticketId, sourceEventId, payload, null, occurredAt));
  }
}
