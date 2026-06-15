package io.ngss.atlas.outbox;

import io.ngss.atlas.attachment.AttachmentService;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbox maintenance sweeps (T-053), driven by the external cron via
 * {@code POST /internal/tasks/run-maintenance}:
 *
 * <ol>
 *   <li>RECLAIM stuck PROCESSING outbox rows (older than {@code OUTBOX_RECLAIM_AFTER_MINUTES})
 *       back to PENDING if retries remain, else to FAILED — UNIFORM across all kinds.
 *   <li>EXPIRE never-finalized PENDING attachment uploads (older than
 *       {@code ATTACHMENT_PENDING_EXPIRY_HOURS}) by soft-deleting them, which enqueues the S3
 *       object removal via the existing {@link AttachmentService} path (exactly one enqueue).
 * </ol>
 *
 * <p>{@code propagation = NEVER}: the method itself runs with NO transaction so each reclaim
 * statement autocommits independently and each per-attachment {@link AttachmentService#softDeleteSystem}
 * call is its OWN atomic transaction — a failure mid-loop leaves earlier rows committed (partial
 * progress), and the next cron tick resumes. AT-LEAST-ONCE: an EMAIL_NOTIFICATION reclaimed after
 * a crash-before-SENT-write may double-send; ATTACHMENT_DELETE_OBJECT is idempotent. See
 * docs/outbox.md.
 */
@Service
public class MaintenanceService {

  private static final Logger log = LoggerFactory.getLogger(MaintenanceService.class);

  /** Bounds each sweep so a large backlog drains over multiple cron ticks (no unbounded scan). */
  static final int MAX_BATCH = 500;

  /** Attempt budget shared with the drain handler: at/above this, reclaim goes to FAILED. */
  static final int ATTEMPT_CAP = 10;

  private final MaintenanceRepository maintenanceRepo;
  private final AttachmentService attachmentService;
  private final int reclaimAfterMinutes;
  private final int pendingExpiryHours;

  public MaintenanceService(
      MaintenanceRepository maintenanceRepo,
      AttachmentService attachmentService,
      @Value("${OUTBOX_RECLAIM_AFTER_MINUTES:15}") int reclaimAfterMinutes,
      @Value("${ATTACHMENT_PENDING_EXPIRY_HOURS:24}") int pendingExpiryHours) {
    this.maintenanceRepo = maintenanceRepo;
    this.attachmentService = attachmentService;
    this.reclaimAfterMinutes = reclaimAfterMinutes;
    this.pendingExpiryHours = pendingExpiryHours;
  }

  @Transactional(propagation = Propagation.NEVER)
  public MaintenanceResult runMaintenance() {
    // Lazy validation (appcds_boot_safety): the numeric @Value defaults are AppCDS-safe; a
    // misconfigured (non-positive) override is caught here at first use, never at boot.
    if (reclaimAfterMinutes <= 0 || pendingExpiryHours <= 0) {
      throw new IllegalStateException(
          "Maintenance env vars must be positive integers "
              + "(OUTBOX_RECLAIM_AFTER_MINUTES, ATTACHMENT_PENDING_EXPIRY_HOURS)");
    }

    int toPending = maintenanceRepo.reclaimToPending(reclaimAfterMinutes, MAX_BATCH, ATTEMPT_CAP);
    int toFailed = maintenanceRepo.reclaimToFailed(reclaimAfterMinutes, MAX_BATCH, ATTEMPT_CAP);

    List<UUID> expiredIds =
        maintenanceRepo.selectExpiredPendingAttachmentIds(pendingExpiryHours, MAX_BATCH);
    long expired = 0;
    for (UUID id : expiredIds) {
      try {
        attachmentService.softDeleteSystem(id); // own REQUIRES tx: soft-delete + single enqueue
        expired++;
      } catch (RuntimeException ex) {
        // Do NOT rethrow — earlier rows are already committed; the next sweep retries this one.
        log.warn("expiry soft-delete failed for attachment {}", id, ex);
      }
    }

    return new MaintenanceResult(toPending, toFailed, expired);
  }
}
