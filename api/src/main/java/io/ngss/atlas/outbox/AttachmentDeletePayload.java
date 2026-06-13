package io.ngss.atlas.outbox;

/**
 * Outbox payload for an {@link OutboxKind#ATTACHMENT_DELETE_OBJECT} (T-029). Carries the S3
 * object key(s) to remove after a soft-delete. {@code thumbnailObjectKey} is null when the
 * attachment never had a generated thumbnail.
 */
public record AttachmentDeletePayload(String objectKey, String thumbnailObjectKey) {}
