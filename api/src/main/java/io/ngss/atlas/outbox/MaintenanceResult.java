package io.ngss.atlas.outbox;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Summary of one maintenance sweep (T-053): how many stuck PROCESSING outbox rows were
 * reclaimed to PENDING, how many were moved to FAILED (attempt budget exhausted), and how
 * many abandoned PENDING attachment uploads were expired (soft-deleted + S3-delete enqueued).
 */
@Schema(name = "MaintenanceResult")
public record MaintenanceResult(
    long reclaimedToPending, long reclaimedToFailed, long expiredUploads) {}
