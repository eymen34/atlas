package io.ngss.atlas.outbox;

/**
 * The kinds of deferred work the outbox carries (T-029).
 *
 * <p>{@code ATTACHMENT_THUMBNAIL} is reserved in the V15 {@code kind} CHECK constraint but is
 * NEVER enqueued in T-029 (D2: the AFTER_COMMIT {@code AttachmentThumbnailListener} is left
 * as-is). No handler is registered for it; draining one would fail fast via the dispatcher.
 */
public enum OutboxKind {
  EMAIL_NOTIFICATION,
  ATTACHMENT_DELETE_OBJECT,
  ATTACHMENT_THUMBNAIL
}
