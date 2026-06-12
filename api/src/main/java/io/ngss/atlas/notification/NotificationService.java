package io.ngss.atlas.notification;

import io.ngss.atlas.common.PagedResponse;
import io.ngss.atlas.domain.Notification;
import io.ngss.atlas.domain.Project;
import io.ngss.atlas.domain.ProjectRepository;
import io.ngss.atlas.domain.Ticket;
import io.ngss.atlas.domain.TicketRepository;
import io.ngss.atlas.domain.User;
import io.ngss.atlas.domain.UserRepository;
import io.ngss.atlas.notification.NotificationPayloads.PayloadV1;
import io.ngss.atlas.notification.dto.NotificationResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Caller-scoped notification read/list service (T-024). The caller id ALWAYS comes
 * from the controller's SecurityContext — never a request param. The list path
 * batch-loads tickets/projects/actors (NO N+1) to enrich each row with
 * projectKey/ticketKey/ticketTitle/actorDisplayName.
 */
@Service
public class NotificationService {

  private final NotificationRepository notificationRepository;
  private final TicketRepository ticketRepository;
  private final ProjectRepository projectRepository;
  private final UserRepository userRepository;
  private final NotificationPayloads payloads;

  public NotificationService(
      NotificationRepository notificationRepository,
      TicketRepository ticketRepository,
      ProjectRepository projectRepository,
      UserRepository userRepository,
      NotificationPayloads payloads) {
    this.notificationRepository = notificationRepository;
    this.ticketRepository = ticketRepository;
    this.projectRepository = projectRepository;
    this.userRepository = userRepository;
    this.payloads = payloads;
  }

  @Transactional(readOnly = true)
  public PagedResponse<NotificationResponse> list(
      UUID callerId, Boolean unread, int page, int size) {
    int clampedSize = Math.max(1, Math.min(100, size));
    int clampedPage = Math.max(0, page);
    Pageable pageable = PageRequest.of(clampedPage, clampedSize);

    Page<Notification> raw =
        Boolean.TRUE.equals(unread)
            ? notificationRepository.findByUserIdAndReadAtIsNullOrderByCreatedAtDesc(
                callerId, pageable)
            : notificationRepository.findByUserIdOrderByCreatedAtDesc(callerId, pageable);

    List<Notification> rows = raw.getContent();
    // Parse each payload once.
    Map<UUID, PayloadV1> payloadByNotificationId =
        rows.stream()
            .collect(Collectors.toMap(Notification::getId, n -> payloads.fromJson(n.getPayload())));

    // Batch-load tickets → projects → actors (NO N+1).
    Set<UUID> ticketIds = rows.stream().map(Notification::getTicketId).collect(Collectors.toSet());
    Map<UUID, Ticket> ticketsById =
        ticketIds.isEmpty()
            ? Map.of()
            : ticketRepository.findAllById(ticketIds).stream()
                .collect(Collectors.toMap(Ticket::getId, Function.identity()));

    Set<UUID> projectIds =
        ticketsById.values().stream().map(Ticket::getProjectId).collect(Collectors.toSet());
    Map<UUID, Project> projectsById =
        projectIds.isEmpty()
            ? Map.of()
            : projectRepository.findAllById(projectIds).stream()
                .collect(Collectors.toMap(Project::getId, Function.identity()));

    Set<UUID> actorIds =
        payloadByNotificationId.values().stream()
            .map(PayloadV1::actorId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    Map<UUID, User> usersById =
        actorIds.isEmpty()
            ? Map.of()
            : userRepository.findAllById(actorIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

    return PagedResponse.from(
        raw,
        n ->
            toResponse(
                n, payloadByNotificationId.get(n.getId()), ticketsById, projectsById, usersById));
  }

  @Transactional
  public void markRead(UUID callerId, UUID id) {
    int rows = notificationRepository.markRead(id, callerId, Instant.now());
    if (rows == 0) {
      // Foreign id OR genuinely missing → uniform 404 (IDOR-safe).
      throw new NotificationNotFoundException(id);
    }
  }

  @Transactional
  public void markAllRead(UUID callerId) {
    notificationRepository.markAllRead(callerId, Instant.now());
  }

  private NotificationResponse toResponse(
      Notification n,
      PayloadV1 payload,
      Map<UUID, Ticket> ticketsById,
      Map<UUID, Project> projectsById,
      Map<UUID, User> usersById) {
    Ticket ticket = ticketsById.get(n.getTicketId());
    Project project = ticket != null ? projectsById.get(ticket.getProjectId()) : null;
    String projectKey = project != null ? project.getKey() : null;
    String ticketKey =
        (project != null && ticket != null) ? project.getKey() + "-" + ticket.getNumber() : null;
    String ticketTitle = ticket != null ? ticket.getTitle() : null;

    UUID actorId = payload != null ? payload.actorId() : null;
    User actor = actorId != null ? usersById.get(actorId) : null;
    String actorDisplayName = actor != null ? actor.getDisplayName() : null;

    return new NotificationResponse(
        n.getId(),
        n.getKind(),
        n.getTicketId(),
        ticketKey,
        ticketTitle,
        projectKey,
        actorId,
        actorDisplayName,
        payload != null ? payload.commentId() : null,
        payload != null ? payload.fromStatus() : null,
        payload != null ? payload.toStatus() : null,
        n.getReadAt() != null,
        n.getCreatedAt());
  }
}
