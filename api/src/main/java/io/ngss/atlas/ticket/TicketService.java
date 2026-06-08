package io.ngss.atlas.ticket;

import io.ngss.atlas.domain.Project;
import io.ngss.atlas.domain.ProjectRepository;
import io.ngss.atlas.domain.ProjectTicketCounterRepository;
import io.ngss.atlas.domain.Ticket;
import io.ngss.atlas.domain.TicketPriority;
import io.ngss.atlas.domain.TicketRepository;
import io.ngss.atlas.domain.TicketStatus;
import io.ngss.atlas.project.ProjectNotFoundException;
import io.ngss.atlas.security.ProjectAccessGuard;
import io.ngss.atlas.ticket.dto.CreateTicketRequest;
import io.ngss.atlas.ticket.dto.TicketResponse;
import io.ngss.atlas.ticket.dto.TransitionRequest;
import io.ngss.atlas.ticket.dto.UpdateTicketRequest;
import io.ngss.atlas.ticket.event.TicketTransitionedEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for the Ticket aggregate (T-017).
 *
 * <p>Authorization is delegated entirely to {@link ProjectAccessGuard}, scoped to
 * the ticket's project. Endpoints addressed by project ({@code create}/{@code list})
 * guard the path's project id directly; endpoints addressed by ticket
 * ({@code getByIdOrKey}/{@code update}/{@code transition}/{@code softDelete}) LOAD
 * the ticket first, then guard {@code ticket.projectId} (two-step). Non-members
 * always get 404 (existence-leak prevention); {@code DELETE} additionally requires
 * ADMIN (403 for member-non-admin).
 *
 * <p>All timestamps are stamped EXPLICITLY here via {@link Instant#now()} (no DB
 * trigger, no JPA lifecycle callbacks), mirroring {@code ProjectService}.
 */
@Service
public class TicketService {

  /**
   * A ticket display key: a project key ({@code ^[A-Z][A-Z0-9]{1,9}$}, which MAY
   * contain digits e.g. {@code ENG2}) + a hyphen + the ticket number. Split on the
   * LAST hyphen so a digit-bearing project key still parses correctly.
   */
  private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Z][A-Z0-9]{1,9}-\\d+$");

  private final TicketRepository ticketRepository;
  private final ProjectTicketCounterRepository counterRepository;
  private final ProjectRepository projectRepository;
  private final ProjectAccessGuard guard;
  private final ApplicationEventPublisher eventPublisher;

  public TicketService(
      TicketRepository ticketRepository,
      ProjectTicketCounterRepository counterRepository,
      ProjectRepository projectRepository,
      ProjectAccessGuard guard,
      ApplicationEventPublisher eventPublisher) {
    this.ticketRepository = ticketRepository;
    this.counterRepository = counterRepository;
    this.projectRepository = projectRepository;
    this.guard = guard;
    this.eventPublisher = eventPublisher;
  }

  // ───────────────────────── create ─────────────────────────

  @Transactional
  public TicketResponse create(UUID projectId, CreateTicketRequest req, UUID callerId) {
    // Load the live project first (missing/soft-deleted → 404), THEN membership
    // (non-member → 404). Both collapse to 404 so neither leaks existence.
    Project project = loadLiveProject(projectId);
    guard.requireMember(projectId);

    // FIX 1 — priority default: use the supplied value when present, else P2.
    TicketPriority priority = req.priority() != null ? req.priority() : TicketPriority.P2;

    // Claim a per-project number atomically in THIS transaction (race-safe).
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
    return TicketResponse.from(ticket, project.getKey());
  }

  // ───────────────────────── list ─────────────────────────

  @Transactional(readOnly = true)
  public List<TicketResponse> list(
      UUID projectId,
      TicketStatus status,
      UUID assigneeId,
      TicketPriority priority,
      String q,
      String label) {
    Project project = loadLiveProject(projectId);
    guard.requireMember(projectId);
    // q (search — T-018) and label (T-028) are accepted by the API but ignored
    // here: deliberate no-op stubs until those tickets land.
    String projectKey = project.getKey();
    return ticketRepository.findFiltered(projectId, status, assigneeId, priority).stream()
        .map(ticket -> TicketResponse.from(ticket, projectKey))
        .toList();
  }

  // ───────────────────────── get by id or key ─────────────────────────

  @Transactional(readOnly = true)
  public TicketResponse getByIdOrKey(String idOrKey) {
    Ticket ticket;
    String projectKey;
    if (KEY_PATTERN.matcher(idOrKey).matches()) {
      // FIX 2 — alphanumeric key: split on the LAST hyphen so e.g. ENG2-42 parses
      // to projectKey=ENG2, number=42.
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
    // FIX 4 — gate visibility on membership AFTER the ticket is loaded.
    guard.requireMember(ticket.getProjectId());
    return TicketResponse.from(ticket, projectKey);
  }

  // ───────────────────────── update (PATCH) ─────────────────────────

  @Transactional
  public TicketResponse update(UUID ticketId, UpdateTicketRequest req) {
    Ticket ticket = loadLiveTicket(ticketId);
    guard.requireMember(ticket.getProjectId());

    // D2 — a present-but-blank title is a 400; null = unchanged.
    if (req.title() != null && req.title().isBlank()) {
      throw new TicketValidationException("title must not be blank");
    }
    // null = unchanged on every field; an explicit "" on description clears it.
    String newTitle = req.title() != null ? req.title() : ticket.getTitle();
    String newDescription =
        req.description() != null ? req.description() : ticket.getDescription();
    UUID newAssignee = req.assigneeId() != null ? req.assigneeId() : ticket.getAssigneeId();
    TicketPriority newPriority = req.priority() != null ? req.priority() : ticket.getPriority();

    // PATCH never changes status; updatedAt always advances. createdAt untouched.
    ticket.updateFields(newTitle, newDescription, newAssignee, newPriority, Instant.now());
    ticketRepository.save(ticket);
    return TicketResponse.from(ticket, projectKey(ticket.getProjectId()));
  }

  // ───────────────────────── transition ─────────────────────────

  @Transactional
  public TicketResponse transition(UUID ticketId, TransitionRequest req, UUID callerId) {
    Ticket ticket = loadLiveTicket(ticketId);
    guard.requireMember(ticket.getProjectId());

    TicketStatus from = ticket.getStatus();
    TicketStatus to = req.toStatus();
    String projectKey = projectKey(ticket.getProjectId());

    // No-op: transitioning to the current status is a 200 with no real change and
    // — crucially — NO event (the T-019 activity log must not record a non-change).
    if (from == to) {
      return TicketResponse.from(ticket, projectKey);
    }

    // Any → any is allowed in the MVP (no state-machine).
    Instant now = Instant.now();
    ticket.transition(to, now);
    ticketRepository.save(ticket);
    // Published inside the transaction; with no listener yet (T-019 adds it) this
    // is a Spring no-op. A future @TransactionalEventListener can bind AFTER_COMMIT.
    eventPublisher.publishEvent(
        new TicketTransitionedEvent(
            ticket.getId(), ticket.getProjectId(), from, to, callerId, now));
    return TicketResponse.from(ticket, projectKey);
  }

  // ───────────────────────── soft-delete (ADMIN) ─────────────────────────

  @Transactional
  public void softDelete(UUID ticketId) {
    Ticket ticket = loadLiveTicket(ticketId);
    // DELETE requires ADMIN: non-member → 404, member-non-admin → 403.
    guard.requireAdmin(ticket.getProjectId());
    ticket.softDelete(Instant.now());
    ticketRepository.save(ticket);
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

  private static int parseNumber(String idOrKey, String digits) {
    try {
      return Integer.parseInt(digits);
    } catch (NumberFormatException overflow) {
      // A syntactically-valid but out-of-int-range number cannot match any ticket.
      throw new TicketNotFoundException("ticket not found: " + idOrKey);
    }
  }
}
