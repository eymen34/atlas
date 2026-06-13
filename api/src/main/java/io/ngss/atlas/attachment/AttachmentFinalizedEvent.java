package io.ngss.atlas.attachment;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by {@link AttachmentService} AFTER an attachment reaches READY (T-025).
 * Consumed AFTER_COMMIT by the thumbnail worker (events-are-facts; the listener owns
 * the "only images" policy and the feature-flag check). The originating finalize is
 * never affected by a thumbnail failure (after_commit_requires_new).
 *
 * @param attachmentId the finalized attachment
 * @param ticketId its ticket (for logging/context)
 * @param objectKey the stored object's key (the worker GETs it)
 * @param contentType the verified content type (the worker only acts on image/*)
 * @param occurredAt the finalize instant
 */
public record AttachmentFinalizedEvent(
    UUID attachmentId, UUID ticketId, String objectKey, String contentType, Instant occurredAt) {}
