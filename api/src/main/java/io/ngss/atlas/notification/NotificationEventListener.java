package io.ngss.atlas.notification;

import io.ngss.atlas.domain.Notification;
import io.ngss.atlas.domain.NotificationKind;
import io.ngss.atlas.domain.Project;
import io.ngss.atlas.domain.ProjectRepository;
import io.ngss.atlas.domain.Ticket;
import io.ngss.atlas.domain.TicketRepository;
import io.ngss.atlas.domain.User;
import io.ngss.atlas.domain.UserRepository;
import io.ngss.atlas.mention.MentionsPersistedEvent;
import io.ngss.atlas.outbox.EmailPayload;
import io.ngss.atlas.outbox.OutboxKind;
import io.ngss.atlas.outbox.OutboxRepository;
import io.ngss.atlas.ticket.event.TicketAssignedEvent;
import io.ngss.atlas.ticket.event.TicketTransitionedEvent;
import io.ngss.atlas.watcher.WatcherRepository;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.ObjectMapper;

/**
 * Fans events out into notification rows (T-024) and, for opted-in recipients, enqueues an
 * EMAIL_NOTIFICATION outbox row (T-029).
 *
 * <p>Each handler is {@code @TransactionalEventListener(AFTER_COMMIT)} +
 * {@code @Transactional(REQUIRES_NEW)} (on the METHOD, independently) — the inserts
 * run in a NEW transaction AFTER the originating change has committed. Without
 * REQUIRES_NEW there is no active transaction at AFTER_COMMIT time and the saves
 * would silently no-op (after_commit_requires_new). Each handler wraps its body in
 * {@code try/catch(Exception)} + ERROR log so a fan-out failure NEVER fails the
 * originating HTTP request (the core write already committed).
 *
 * <p>The email enqueue happens inside the SAME REQUIRES_NEW transaction as the notification
 * save (so the outbox row and the notification commit atomically), gated on the recipient's
 * {@code email_notifications_enabled}. The subject {@code [<projectKey>-<number>] <title>} and
 * body are built HERE at enqueue time from the loaded ticket/project/actor — the email handler
 * reads them verbatim.
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
  private final TicketRepository ticketRepository;
  private final ProjectRepository projectRepository;
  private final UserRepository userRepository;
  private final OutboxRepository outboxRepository;
  private final ObjectMapper objectMapper;
  private final String appBaseUrl;

  public NotificationEventListener(
      NotificationRepository notificationRepository,
      WatcherRepository watcherRepository,
      NotificationPayloads payloads,
      TicketRepository ticketRepository,
      ProjectRepository projectRepository,
      UserRepository userRepository,
      OutboxRepository outboxRepository,
      ObjectMapper objectMapper,
      @Value("${app.base-url:http://localhost:8080}") String appBaseUrl) {
    this.notificationRepository = notificationRepository;
    this.watcherRepository = watcherRepository;
    this.payloads = payloads;
    this.ticketRepository = ticketRepository;
    this.projectRepository = projectRepository;
    this.userRepository = userRepository;
    this.outboxRepository = outboxRepository;
    this.objectMapper = objectMapper;
    this.appBaseUrl = appBaseUrl;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void onTicketAssigned(TicketAssignedEvent e) {
    try {
      if (e.newAssigneeId().equals(e.actorId())) {
        return; // self-assign → no notification
      }
      EmailContext email =
          emailContext(e.ticketId(), e.projectId(), e.actorId(), "assigned this ticket to you");
      fanOut(
          e.newAssigneeId(),
          NotificationKind.ASSIGNED,
          e.ticketId(),
          e.sourceEventId(),
          payloads.forAssigned(e.actorId()),
          e.occurredAt(),
          email);
    } catch (Exception ex) {
      log.error("notification fan-out failed for ASSIGNED ticket={}", e.ticketId(), ex);
    }
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void onMentionsPersisted(MentionsPersistedEvent e) {
    try {
      boolean isTicket = e.kind() == MentionsPersistedEvent.Kind.TICKET;
      NotificationKind kind =
          isTicket ? NotificationKind.MENTIONED_TICKET : NotificationKind.MENTIONED_COMMENT;
      String payload =
          isTicket
              ? payloads.forMentionedTicket(e.actorId())
              : payloads.forMentionedComment(e.actorId(), e.commentId());
      EmailContext email =
          emailContext(
              e.ticketId(),
              e.projectId(),
              e.actorId(),
              isTicket ? "mentioned you on this ticket" : "mentioned you in a comment");
      for (UUID userId : e.mentionedUserIds()) {
        if (userId.equals(e.actorId())) {
          continue; // self-mention skip — BEFORE dedup
        }
        fanOut(userId, kind, e.ticketId(), e.sourceEventId(), payload, e.occurredAt(), email);
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
      EmailContext email =
          emailContext(
              e.ticketId(),
              e.projectId(),
              e.actorId(),
              "changed status from " + e.fromStatus().name() + " to " + e.toStatus().name());
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
            e.occurredAt(),
            email);
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
      Instant occurredAt,
      EmailContext email) {
    Instant since = occurredAt.minusSeconds(DEDUP_WINDOW_SECONDS);
    if (notificationRepository.existsDedupWindow(userId, kind, ticketId, since)) {
      return;
    }
    notificationRepository.save(
        new Notification(
            UUID.randomUUID(), userId, kind, ticketId, sourceEventId, payload, null, occurredAt));
    enqueueEmailIfEnabled(userId, email);
  }

  /**
   * Enqueues an EMAIL_NOTIFICATION outbox row for the recipient when they have email enabled.
   * Runs in the same REQUIRES_NEW transaction as the notification save (AC-6). Skipped silently
   * when the email context could not be built (ticket/project gone) or the recipient is unknown.
   */
  private void enqueueEmailIfEnabled(UUID userId, EmailContext email) {
    if (email == null) {
      return;
    }
    User recipient = userRepository.findById(userId).orElse(null);
    if (recipient == null
        || !recipient.isEmailNotificationsEnabled()
        || recipient.getEmail() == null) {
      return;
    }
    String body =
        email.actorDisplayName()
            + " "
            + email.changeDescription()
            + "\n"
            + appBaseUrl
            + email.linkPath();
    outboxRepository.enqueue(
        OutboxKind.EMAIL_NOTIFICATION,
        objectMapper.valueToTree(new EmailPayload(recipient.getEmail(), email.subject(), body)));
  }

  /**
   * Loads ticket/project/actor once to build the per-event email subject + deep-link. Returns
   * null when the ticket or project cannot be loaded (then no email is enqueued).
   */
  private EmailContext emailContext(
      UUID ticketId, UUID projectId, UUID actorId, String changeDescription) {
    Ticket ticket = ticketRepository.findById(ticketId).orElse(null);
    Project project = projectRepository.findById(projectId).orElse(null);
    if (ticket == null || project == null) {
      return null;
    }
    User actor = actorId != null ? userRepository.findById(actorId).orElse(null) : null;
    String actorName = actor != null ? actor.getDisplayName() : "Someone";
    String ticketKey = project.getKey() + "-" + ticket.getNumber();
    String subject = "[" + ticketKey + "] " + ticket.getTitle();
    String linkPath = "/projects/" + project.getKey() + "/tickets/" + ticketKey;
    return new EmailContext(subject, linkPath, actorName, changeDescription);
  }

  /** Pre-built, recipient-independent email fields for one event. */
  private record EmailContext(
      String subject, String linkPath, String actorDisplayName, String changeDescription) {}
}
