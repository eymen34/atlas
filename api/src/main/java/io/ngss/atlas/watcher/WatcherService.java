package io.ngss.atlas.watcher;

import io.ngss.atlas.config.FeatureFlags;
import io.ngss.atlas.domain.Ticket;
import io.ngss.atlas.domain.TicketRepository;
import io.ngss.atlas.security.ProjectAccessGuard;
import io.ngss.atlas.ticket.TicketNotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ticket-watcher application service (T-023).
 *
 * <p>Two method families:
 *
 * <ul>
 *   <li><b>Auto-watch helpers</b> ({@code autoWatch*}) — {@code MANDATORY}
 *       propagation, so they can ONLY run inside an existing transaction (the
 *       ticket/comment write that triggers them); calling one without a surrounding
 *       {@code @Transactional} throws {@code IllegalTransactionStateException}. Each
 *       takes the caller's {@link Instant} so the watcher row shares the originating
 *       change's timestamp (microsecond parity with the activity row — EC-10). All
 *       are flag-gated no-ops.
 *   <li><b>Endpoint-facing</b> ({@code watch}/{@code unwatch}/{@code listWatcherIds})
 *       — flag-off → 404 (existence-leak parity), then load-then-guard
 *       ({@code requireMember} is 1-arg, reads CurrentUser internally).
 * </ul>
 *
 * <p>Idempotency is at the DB: insert via {@code ON CONFLICT DO NOTHING}, delete
 * is a no-op on a missing row — never a catch-and-retry (jpa_rollback_only_trap).
 */
@Service
public class WatcherService {

  private final WatcherRepository watcherRepository;
  private final TicketRepository ticketRepository;
  private final ProjectAccessGuard guard;
  private final FeatureFlags featureFlags;

  public WatcherService(
      WatcherRepository watcherRepository,
      TicketRepository ticketRepository,
      ProjectAccessGuard guard,
      FeatureFlags featureFlags) {
    this.watcherRepository = watcherRepository;
    this.ticketRepository = ticketRepository;
    this.guard = guard;
    this.featureFlags = featureFlags;
  }

  // ───────────────────────── auto-watch helpers (MANDATORY) ─────────────────────────

  /** Watch the creator (always) and, if different, the initial assignee. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void autoWatchOnCreate(Ticket ticket, UUID creatorId, UUID assigneeIdOrNull, Instant now) {
    if (!featureFlags.watchersEnabled()) {
      return;
    }
    insert(ticket.getId(), creatorId, now);
    if (assigneeIdOrNull != null && !assigneeIdOrNull.equals(creatorId)) {
      insert(ticket.getId(), assigneeIdOrNull, now);
    }
  }

  /** Watch a newly-assigned user (the assign actor is NOT auto-watched). */
  @Transactional(propagation = Propagation.MANDATORY)
  public void autoWatchAssignee(UUID ticketId, UUID newAssigneeId, Instant now) {
    if (!featureFlags.watchersEnabled()) {
      return;
    }
    insert(ticketId, newAssigneeId, now);
  }

  /** Watch a commenter on comment create. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void autoWatchCommenter(UUID ticketId, UUID commenterId, Instant now) {
    if (!featureFlags.watchersEnabled()) {
      return;
    }
    insert(ticketId, commenterId, now);
  }

  // ───────────────────────── endpoint-facing ─────────────────────────

  @Transactional
  public void watch(UUID ticketId, UUID callerId) {
    requireWatchableTicket(ticketId);
    insert(ticketId, callerId, Instant.now());
  }

  @Transactional
  public void unwatch(UUID ticketId, UUID callerId) {
    requireWatchableTicket(ticketId);
    // Idempotent: removing a non-watch affects 0 rows; only the caller's row is touched.
    watcherRepository.deleteByTicketAndUser(ticketId, callerId);
  }

  @Transactional(readOnly = true)
  public List<UUID> listWatcherIds(UUID ticketId) {
    requireWatchableTicket(ticketId);
    return watcherRepository.findUserIdsByTicketId(ticketId);
  }

  // ───────────────────────── helpers ─────────────────────────

  private void insert(UUID ticketId, UUID userId, Instant now) {
    watcherRepository.insertIgnoreConflict(UUID.randomUUID(), ticketId, userId, now);
  }

  /**
   * Flag-off → 404 (so a disabled feature leaks no ticket existence), then load the
   * ticket and require project membership (load-then-guard; non-member → 404).
   */
  private Ticket requireWatchableTicket(UUID ticketId) {
    if (!featureFlags.watchersEnabled()) {
      throw new TicketNotFoundException("ticket not found: " + ticketId);
    }
    Ticket ticket =
        ticketRepository
            .findById(ticketId)
            .orElseThrow(() -> new TicketNotFoundException("ticket not found: " + ticketId));
    guard.requireMember(ticket.getProjectId());
    return ticket;
  }
}
