package io.ngss.atlas.link;

import io.ngss.atlas.activity.ActivityEventWriter;
import io.ngss.atlas.activity.payload.LinkAddedPayload;
import io.ngss.atlas.activity.payload.LinkRemovedPayload;
import io.ngss.atlas.domain.ActivityEventType;
import io.ngss.atlas.domain.LinkRelation;
import io.ngss.atlas.domain.Project;
import io.ngss.atlas.domain.ProjectRepository;
import io.ngss.atlas.domain.Ticket;
import io.ngss.atlas.domain.TicketLink;
import io.ngss.atlas.domain.TicketRepository;
import io.ngss.atlas.link.dto.CreateLinkRequest;
import io.ngss.atlas.link.dto.LinkResponse;
import io.ngss.atlas.security.ProjectAccessGuard;
import io.ngss.atlas.ticket.TicketNotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for ticket links (T-026). A user action persists TWO reciprocal
 * rows in ONE transaction (the relation + its {@link LinkRelation#inverse}) and writes
 * a {@code LINK_ADDED}/{@code LINK_REMOVED} activity row on BOTH tickets (synchronous,
 * MANDATORY writer — shares one {@code Instant.now()}). Links are SAME-PROJECT only;
 * the per-pair conflict is rejected by an optimistic pre-check (NOT a caught
 * DataIntegrityViolation — jpa_rollback_only_trap).
 */
@Service
public class LinkService {

  private final TicketLinkRepository linkRepository;
  private final TicketRepository ticketRepository;
  private final ProjectRepository projectRepository;
  private final ProjectAccessGuard guard;
  private final ActivityEventWriter activityWriter;

  public LinkService(
      TicketLinkRepository linkRepository,
      TicketRepository ticketRepository,
      ProjectRepository projectRepository,
      ProjectAccessGuard guard,
      ActivityEventWriter activityWriter) {
    this.linkRepository = linkRepository;
    this.ticketRepository = ticketRepository;
    this.projectRepository = projectRepository;
    this.guard = guard;
    this.activityWriter = activityWriter;
  }

  @Transactional
  public LinkResponse createLink(UUID fromTicketId, UUID actorId, CreateLinkRequest req) {
    Ticket fromTicket = loadTicket(fromTicketId);
    guard.requireMember(fromTicket.getProjectId());

    // Inverse types are server-derived — reject before any DB access.
    if (!LinkRelation.isUserFacing(req.relation())) {
      throw new LinkValidationException(
          "Inverse relation types cannot be created directly: " + req.relation());
    }

    ParsedKey key = parseKey(req.toTicketKey());
    Ticket toTicket =
        ticketRepository
            .findByProjectKeyAndNumberAndDeletedAtIsNull(key.projectKey(), key.number())
            .orElseThrow(() -> new LinkValidationException("Unknown ticket key"));
    // SAME-PROJECT only: a key resolving into another project is indistinguishable from
    // a non-existent one (uniform message, no cross-project existence probe).
    if (!toTicket.getProjectId().equals(fromTicket.getProjectId())) {
      throw new LinkValidationException("Unknown ticket key");
    }
    if (toTicket.getId().equals(fromTicket.getId())) {
      throw new LinkValidationException("Cannot link a ticket to itself");
    }

    // One relation per pair, EITHER direction (D4) — pre-check, not catch-DIV.
    if (linkRepository.existsByFromTicketIdAndToTicketId(fromTicketId, toTicket.getId())
        || linkRepository.existsByFromTicketIdAndToTicketId(toTicket.getId(), fromTicketId)) {
      throw new LinkConflictException("A link already exists between these tickets");
    }

    Instant now = Instant.now();
    LinkRelation relation = req.relation();
    LinkRelation inverse = LinkRelation.inverse(relation);
    UUID fromLinkId = UUID.randomUUID();
    linkRepository.save(
        new TicketLink(fromLinkId, fromTicketId, toTicket.getId(), relation, actorId, now));
    linkRepository.save(
        new TicketLink(UUID.randomUUID(), toTicket.getId(), fromTicketId, inverse, actorId, now));

    activityWriter.record(
        fromTicketId,
        actorId,
        ActivityEventType.LINK_ADDED,
        new LinkAddedPayload(toTicket.getId(), relation),
        now);
    activityWriter.record(
        toTicket.getId(),
        actorId,
        ActivityEventType.LINK_ADDED,
        new LinkAddedPayload(fromTicketId, inverse),
        now);

    return new LinkResponse(
        fromLinkId, // the from-side row's id (the row a later DELETE from this ticket targets)
        fromTicketId,
        toTicket.getId(),
        relation,
        key.projectKey() + "-" + toTicket.getNumber(),
        toTicket.getTitle(),
        toTicket.getStatus(),
        false,
        actorId,
        now);
  }

  @Transactional(readOnly = true)
  public List<LinkResponse> listLinks(UUID fromTicketId, UUID actorId) {
    Ticket fromTicket = loadTicket(fromTicketId);
    guard.requireMember(fromTicket.getProjectId());

    List<TicketLink> rows = linkRepository.findByFromTicketIdOrderByCreatedAtDesc(fromTicketId);
    if (rows.isEmpty()) {
      return List.of();
    }

    // Batch: targets (findAllById INCLUDES soft-deleted — a link's target is always
    // resolvable) + the one project (all targets are same-project). Exactly 3 queries.
    Set<UUID> toIds = rows.stream().map(TicketLink::getToTicketId).collect(Collectors.toSet());
    Map<UUID, Ticket> ticketsById =
        ticketRepository.findAllById(toIds).stream()
            .collect(Collectors.toMap(Ticket::getId, Function.identity()));
    Project project =
        projectRepository
            .findById(fromTicket.getProjectId())
            .orElseThrow(() -> new IllegalStateException("project missing for live ticket"));
    String projectKey = project.getKey();

    return rows.stream()
        .map(
            link -> {
              Ticket target = ticketsById.get(link.getToTicketId());
              return new LinkResponse(
                  link.getId(),
                  link.getFromTicketId(),
                  link.getToTicketId(),
                  link.getRelation(),
                  projectKey + "-" + target.getNumber(),
                  target.getTitle(),
                  target.getStatus(),
                  target.getDeletedAt() != null,
                  link.getCreatedBy(),
                  link.getCreatedAt());
            })
        .toList();
  }

  @Transactional
  public void deleteLink(UUID linkId, UUID actorId) {
    TicketLink link =
        linkRepository.findById(linkId).orElseThrow(() -> new LinkNotFoundException(linkId));
    Ticket fromTicket = loadTicket(link.getFromTicketId());
    guard.requireMember(fromTicket.getProjectId());

    LinkRelation inverse = LinkRelation.inverse(link.getRelation());
    Instant now = Instant.now();
    linkRepository.deleteByFromTicketIdAndToTicketIdAndRelation(
        link.getFromTicketId(), link.getToTicketId(), link.getRelation());
    linkRepository.deleteByFromTicketIdAndToTicketIdAndRelation(
        link.getToTicketId(), link.getFromTicketId(), inverse);

    activityWriter.record(
        link.getFromTicketId(),
        actorId,
        ActivityEventType.LINK_REMOVED,
        new LinkRemovedPayload(link.getToTicketId(), link.getRelation()),
        now);
    activityWriter.record(
        link.getToTicketId(),
        actorId,
        ActivityEventType.LINK_REMOVED,
        new LinkRemovedPayload(link.getFromTicketId(), inverse),
        now);
  }

  // ───────────────────────── helpers ─────────────────────────

  private Ticket loadTicket(UUID ticketId) {
    return ticketRepository
        .findById(ticketId)
        .orElseThrow(() -> new TicketNotFoundException("ticket not found: " + ticketId));
  }

  private record ParsedKey(String projectKey, int number) {}

  /** Splits "ENG-12" on the LAST '-'; uppercases the key; 400 on any malformed shape. */
  private static ParsedKey parseKey(String ticketKey) {
    int dash = ticketKey.lastIndexOf('-');
    if (dash <= 0 || dash == ticketKey.length() - 1) {
      throw new LinkValidationException("Unknown ticket key");
    }
    String projectKey = ticketKey.substring(0, dash).toUpperCase(Locale.ROOT);
    try {
      int number = Integer.parseInt(ticketKey.substring(dash + 1));
      return new ParsedKey(projectKey, number);
    } catch (NumberFormatException e) {
      throw new LinkValidationException("Unknown ticket key");
    }
  }
}
