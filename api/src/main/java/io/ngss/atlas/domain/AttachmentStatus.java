package io.ngss.atlas.domain;

/**
 * Lifecycle state of an {@link Attachment} (T-025). Persisted as a string via
 * {@code @Enumerated(EnumType.STRING)} (text + CHECK column in V12), mirroring
 * {@link NotificationKind} / {@link TicketStatus}.
 *
 * <ul>
 *   <li>{@code PENDING} — row created at upload-init; the presigned PUT has been
 *       issued but the object is not yet verified.
 *   <li>{@code READY} — the server HEADed the uploaded object and verified its size
 *       and content-type; the attachment is listable and downloadable.
 *   <li>{@code FAILED} — finalize found a size/content-type mismatch or a missing
 *       object; never listed, retry allowed.
 * </ul>
 */
public enum AttachmentStatus {
  PENDING,
  READY,
  FAILED
}
