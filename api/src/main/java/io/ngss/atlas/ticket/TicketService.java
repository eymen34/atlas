package io.ngss.atlas.ticket;

import io.ngss.atlas.activity.ActivityEventWriter;
import io.ngss.atlas.activity.payload.AssigneeChangedPayload;
import io.ngss.atlas.activity.payload.CreatedPayload;
import io.ngss.atlas.activity.payload.LabelsChangedPayload;
import io.ngss.atlas.activity.payload.PriorityChangedPayload;
import io.ngss.atlas.activity.payload.StatusChangedPayload;
import io.ngss.atlas.comment.TicketMentionRepository;
import io.ngss.atlas.common.PagedResponse;
import io.ngss.atlas.domain.ActivityEventType;
import io.ngss.atlas.domain.Label;
import io.ngss.atlas.domain.Project;
import io.ngss.atlas.domain.ProjectRepository;
import io.ngss.atlas.domain.ProjectTicketCounterRepository;
import io.ngss.atlas.domain.Ticket;
import io.ngss.atlas.domain.TicketLabel;
import io.ngss.atlas.domain.TicketPriority;
import io.ngss.atlas.domain.TicketMention;
import io.ngss.atlas.domain.TicketRepository;
import io.ngss.atlas.domain.TicketStatus;
import io.ngss.atlas.label.CrossProjectLabelException;
import io.ngss.atlas.label.LabelRepository;
import io.ngss.atlas.label.TicketLabelRepository;
import io.ngss.atlas.mention.MentionParser;
import io.ngss.atlas.project.ProjectNotFoundException;
import io.ngss.atlas.security.ProjectAccessGuard;
import io.ngss.atlas.ticket.dto.CreateTicketRequest;
import io.ngss.atlas.ticket.dto.TicketResponse;
import io.ngss.atlas.ticket.dto.TransitionRequest;
import io.ngss.atlas.ticket.dto.UpdateTicketRequest;
import io.ngss.atlas.ticket.event.TicketTransitionedEvent;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for the Ticket aggregate (T-017; T-018 added dynamic/paged
 * listing and label association).
 *
 * <p>Authorization is delegated to {@link ProjectAccessGuard}, scoped to the
 * ticket's project. Project-addressed operations guard the path id; ticket-addressed
 * operations LOAD the ticket first, then guard {@code ticket.projectId}. Non-members
 * get 404; DELETE requires ADMIN (403 for member-non-admin).
 *
 * <p>All timestamps are stamped EXPLICITLY here via {@link Instant#now()}.
 */
@Service
public class TicketService {

  /**
   * A ticket display key: a project key ({@code ^[A-Z][A-Z0-9]{1,9}$}, may contain
   * digits e.g. {@code ENG2}) + hyphen + number. Split on the LAST hyphen.
   */
  private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Z][A-Z0-9]{1,9}-\\d+$");

  private final TicketRepository ticketRepository;
  private final ProjectTicketCounterRepository counterRepository;
  private final ProjectRepository projectRepository;
  private final LabelRepository labelRepository;
  private final TicketLabelRepository ticketLabelRepository;
  private final ProjectAccessGuard guard;
  private final ApplicationEventPublisher eventPublisher;
  private final EntityManager entityManager;
  private final ActivityEventWriter activityWriter;
  private final MentionParser mentionParser;
  private final TicketMentionRepository ticketMentionRepository;

  public TicketService(
      TicketRepository ticketRepository,
      ProjectTicketCounterRepository counterRepository,
      ProjectRepository projectRepository,
      LabelRepository labelRepository,
      TicketLabelRepository ticketLabelRepository,
      ProjectAccessGuard guard,
      ApplicationEventPublisher eventPublisher,
      EntityManager entityManager,
      ActivityEventWriter activityWriter,
      MentionParser mentionParser,
      TicketMentionRepository ticketMentionRepository) {
    this.ticketRepository = ticketRepository;
    this.counterRepository = counterRepository;
    this.projectRepository = projectRepository;
    this.labelRepository = labelRepository;
    this.ticketLabelRepository = ticketLabelRepository;
    this.guard = guard;
    this.eventPublisher = eventPublisher;
    this.entityManager = entityManager;
    this.activityWriter = activityWriter;
    this.mentionParser = mentionParser;
    this.ticketMentionRepository = ticketMentionRepository;
  }

  // ───────────────────────── create ─────────────────────────

  @Transactional
  public TicketResponse create(UUID projectId, CreateTicketRequest req, UUID callerId) {
    Project project = loadLiveProject(projectId);
    guard.requireMember(projectId);

    TicketPriority priority = req.priority() != null ? req.priority() : TicketPriority.P2;
    int number = counterRepository.claimNextNumber(projectId);

    Instant now = Instant.now();
    Ticket ticket =
        new Ticket(
            UUID.randomUUID(),
            projectId,
            number,
            req.title(),
            req.description(),
            TicketStatus.TODO,
            priority,
            req.assigneeId(),
            callerId,
            now,
            now,
            null);
    ticketRepository.save(ticket);
    // Activity: append a CREATED row in THIS transaction (atomic with the insert).
    activityWriter.record(
        ticket.getId(),
        callerId,
        ActivityEventType.CREATED,
        new CreatedPayload(ticket.getTitle(), ticket.getStatus(), ticket.getPriority()),
        now);
    // T-022: a new ticket's HTML description may @mention project members.
    if (req.description() != null) {
      saveTicketMentions(ticket.getId(), mentionParser.parse(req.description(), projectId));
    }
    // A freshly created ticket has no labels yet.
    return TicketResponse.from(ticket, project.getKey(), List.of());
  }

  // ───────────────────────── list (paged, filtered) ─────────────────────────

  @Transactional(readOnly = true)
  public PagedResponse<TicketResponse> list(
      UUID projectId,
      List<TicketStatus> statuses,
      List<TicketPriority> priorities,
      String assigneeId,
      List<UUID> labelIds,
      String q,
      int page,
      int size) {
    // Load the project for its key (and the live + 404 check) via findById — an
    // em.find entity LOAD, not a counted query — to keep the page's query count
    // minimal. q (search, T-018 out of scope) is accepted and ignored.
    Project project =
        projectRepository
            .findById(projectId)
            .filter(p -> p.getDeletedAt() == null)
            .orElseThrow(() -> new ProjectNotFoundException(projectId));
    guard.requireMember(projectId);
    String projectKey = project.getKey();

    Specification<Ticket> spec =
        TicketSpecifications.build(projectId, statuses, priorities, assigneeId, labelIds);
    Pageable pageable =
        PageRequest.of(
            page,
            size,
            Sort.by(Sort.Direction.DESC, "updatedAt").and(Sort.by(Sort.Direction.ASC, "id")));
    Page<Ticket> ticketPage = ticketRepository.findAll(spec, pageable);

    // Batch-load every label for the whole page in ONE query (no N+1).
    List<UUID> pageTicketIds = ticketPage.getContent().stream().map(Ticket::getId).toList();
    Map<UUID, List<UUID>> labelsByTicket =
        pageTicketIds.isEmpty()
            ? Map.of()
            : ticketLabelRepository.findByTicketIdIn(pageTicketIds).stream()
                .collect(
                    Collectors.groupingBy(
                        TicketLabel::getTicketId,
                        Collectors.mapping(TicketLabel::getLabelId, Collectors.toList())));

    return PagedResponse.from(
        ticketPage,
        t -> TicketResponse.from(t, projectKey, labelsByTicket.getOrDefault(t.getId(), List.of())));
  }

  // ───────────────────────── get by id or key ─────────────────────────

  @Transactional(readOnly = true)
  public TicketResponse getByIdOrKey(String idOrKey) {
    Ticket ticket;
    String projectKey;
    if (KEY_PATTERN.matcher(idOrKey).matches()) {
      int dash = idOrKey.lastIndexOf('-');
      projectKey = idOrKey.substring(0, dash);
      int number = parseNumber(idOrKey, idOrKey.substring(dash + 1));
      ticket =
          ticketRepository
              .findByProjectKeyAndNumberAndDeletedAtIsNull(projectKey, number)
              .orElseThrow(() -> new TicketNotFoundException("ticket not found: " + idOrKey));
    } else {
      UUID id;
      try {
        id = UUID.fromString(idOrKey);
      } catch (IllegalArgumentException notAUuidOrKey) {
        throw new TicketNotFoundException("ticket not found: " + idOrKey);
      }
      ticket =
          ticketRepository
              .findByIdAndDeletedAtIsNull(id)
              .orElseThrow(() -> new TicketNotFoundException("ticket not found: " + idOrKey));
      projectKey = projectKey(ticket.getProjectId());
    }
    guard.requireMember(ticket.getProjectId());
    return TicketResponse.from(ticket, projectKey, currentLabelIds(ticket.getId()));
  }

  // ───────────────────────── update (PATCH) ─────────────────────────

  @Transactional
  public TicketResponse update(UUID ticketId, UpdateTicketRequest req, UUID callerId) {
    Ticket ticket = loadLiveTicket(ticketId);
    guard.requireMember(ticket.getProjectId());

    if (req.title() != null && req.title().isBlank()) {
      throw new TicketValidationException("title must not be blank");
    }
    // Capture pre-change values for activity logging. title/description edits are
    // intentionally NOT logged (T-019 design decision) — only assignee/priority.
    UUID oldAssignee = ticket.getAssigneeId();
    TicketPriority oldPriority = ticket.getPriority();
    String oldDescription = ticket.getDescription();

    String newTitle = req.title() != null ? req.title() : ticket.getTitle();
    String newDescription =
        req.description() != null ? req.description() : ticket.getDescription();
    UUID newAssignee = req.assigneeId() != null ? req.assigneeId() : ticket.getAssigneeId();
    TicketPriority newPriority = req.priority() != null ? req.priority() : ticket.getPriority();

    Instant now = Instant.now();
    ticket.updateFields(newTitle, newDescription, newAssignee, newPriority, now);
    ticketRepository.save(ticket);

    if (!Objects.equals(oldAssignee, ticket.getAssigneeId())) {
      activityWriter.record(
          ticket.getId(),
          callerId,
          ActivityEventType.ASSIGNEE_CHANGED,
          new AssigneeChangedPayload(oldAssignee, ticket.getAssigneeId()),
          now);
    }
    if (oldPriority != ticket.getPriority()) {
      activityWriter.record(
          ticket.getId(),
          callerId,
          ActivityEventType.PRIORITY_CHANGED,
          new PriorityChangedPayload(oldPriority, ticket.getPriority()),
          now);
    }
    // T-022: re-diff description @mentions only when the description is PRESENT in
    // the request (req.description() != null) AND meaningfully changed — a null↔""
    // or whitespace-only flip is a no-op and must not churn ticket_mentions.
    if (req.description() != null && isMeaningfullyChanged(oldDescription, req.description())) {
      ticketMentionRepository.deleteByTicketId(ticketId);
      entityManager.flush();
      saveTicketMentions(
          ticketId, mentionParser.parse(req.description(), ticket.getProjectId()));
    }
    // Mutation responses do not re-read associations (T-018 contract).
    return TicketResponse.from(ticket, projectKey(ticket.getProjectId()), List.of());
  }

  // ───────────────────────── transition ─────────────────────────

  @Transactional
  public TicketResponse transition(UUID ticketId, TransitionRequest req, UUID callerId) {
    Ticket ticket = loadLiveTicket(ticketId);
    guard.requireMember(ticket.getProjectId());

    TicketStatus from = ticket.getStatus();
    TicketStatus to = req.toStatus();
    String projectKey = projectKey(ticket.getProjectId());

    if (from == to) {
      // No-op: 200 with no real change and no event.
      return TicketResponse.from(ticket, projectKey, List.of());
    }

    Instant now = Instant.now();
    ticket.transition(to, now);
    ticketRepository.save(ticket);
    // T-024 fan-out event (consumed AFTER_COMMIT by notifications) — KEPT.
    eventPublisher.publishEvent(
        new TicketTransitionedEvent(
            ticket.getId(), ticket.getProjectId(), from, to, callerId, now));
    // Activity: a STATUS_CHANGED row, written synchronously in THIS transaction
    // (NOT via the event above) so it is atomic with the status change.
    activityWriter.record(
        ticket.getId(),
        callerId,
        ActivityEventType.STATUS_CHANGED,
        new StatusChangedPayload(from, to),
        now);
    return TicketResponse.from(ticket, projectKey, List.of());
  }

  // ───────────────────────── soft-delete (ADMIN) ─────────────────────────

  @Transactional
  public void softDelete(UUID ticketId) {
    Ticket ticket = loadLiveTicket(ticketId);
    guard.requireAdmin(ticket.getProjectId());
    ticket.softDelete(Instant.now());
    ticketRepository.save(ticket);
  }

  // ───────────────────────── set labels (full replace) ─────────────────────────

  @Transactional
  public TicketResponse setTicketLabels(
      UUID ticketId, List<UUID> requestedLabelIds, UUID callerId) {
    Ticket ticket = loadLiveTicket(ticketId);
    guard.requireMember(ticket.getProjectId());

    // De-duplicate, preserving the caller's order.
    List<UUID> distinctIds = new ArrayList<>(new LinkedHashSet<>(requestedLabelIds));
    if (!distinctIds.isEmpty()) {
      List<Label> labels = labelRepository.findAllById(distinctIds);
      // Every requested id must exist AND belong to the ticket's project.
      boolean allInProject =
          labels.size() == distinctIds.size()
              && labels.stream().allMatch(l -> l.getProjectId().equals(ticket.getProjectId()));
      if (!allInProject) {
        throw new CrossProjectLabelException(
            "one or more labels do not exist in this ticket's project");
      }
    }

    // Snapshot the current label set BEFORE replacing, for the LABELS_CHANGED delta.
    Set<UUID> oldLabelIds = new HashSet<>(currentLabelIds(ticketId));

    // Concurrent PUTs to the same ticket use last-writer-wins semantics; SELECT FOR
    // UPDATE not implemented in this ticket.
    ticketLabelRepository.deleteByTicketId(ticketId);
    entityManager.flush();
    Instant now = Instant.now();
    for (UUID labelId : distinctIds) {
      ticketLabelRepository.save(new TicketLabel(ticketId, labelId, now));
    }

    // Activity: log only a real change. A no-op replace (added+removed empty) → 0 rows.
    List<UUID> added = distinctIds.stream().filter(id -> !oldLabelIds.contains(id)).toList();
    List<UUID> removed = oldLabelIds.stream().filter(id -> !distinctIds.contains(id)).toList();
    if (!added.isEmpty() || !removed.isEmpty()) {
      activityWriter.record(
          ticket.getId(),
          callerId,
          ActivityEventType.LABELS_CHANGED,
          new LabelsChangedPayload(added, removed),
          now);
    }

    return TicketResponse.from(ticket, projectKey(ticket.getProjectId()), distinctIds);
  }

  // ───────────────────────── helpers ─────────────────────────

  private Project loadLiveProject(UUID projectId) {
    return projectRepository
        .findByIdAndDeletedAtIsNull(projectId)
        .orElseThrow(() -> new ProjectNotFoundException(projectId));
  }

  private Ticket loadLiveTicket(UUID ticketId) {
    return ticketRepository
        .findByIdAndDeletedAtIsNull(ticketId)
        .orElseThrow(() -> new TicketNotFoundException("ticket not found: " + ticketId));
  }

  /** The project key for a ticket's response; the FK guarantees the project exists. */
  private String projectKey(UUID projectId) {
    return projectRepository
        .findById(projectId)
        .map(Project::getKey)
        .orElseThrow(
            () -> new IllegalStateException("project missing for ticket project " + projectId));
  }

  /** The label ids currently attached to a ticket (detail-view population). */
  private List<UUID> currentLabelIds(UUID ticketId) {
    return ticketLabelRepository.findByTicketId(ticketId).stream()
        .map(TicketLabel::getLabelId)
        .toList();
  }

  private static int parseNumber(String idOrKey, String digits) {
    try {
      return Integer.parseInt(digits);
    } catch (NumberFormatException overflow) {
      throw new TicketNotFoundException("ticket not found: " + idOrKey);
    }
  }

  /** Persists the resolved description @mention rows for a ticket (T-022). */
  private void saveTicketMentions(UUID ticketId, Set<UUID> userIds) {
    for (UUID userId : userIds) {
      ticketMentionRepository.save(new TicketMention(ticketId, userId));
    }
  }

  /** True when two descriptions differ once blank/whitespace is normalized to null. */
  private static boolean isMeaningfullyChanged(String a, String b) {
    return !Objects.equals(blankToNull(a), blankToNull(b));
  }

  private static String blankToNull(String s) {
    return (s == null || s.isBlank()) ? null : s;
  }
}
