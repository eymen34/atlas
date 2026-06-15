package io.ngss.atlas.attachment;

import java.util.UUID;

/**
 * Outbox payload for an {@link io.ngss.atlas.outbox.OutboxKind#ATTACHMENT_THUMBNAIL} row (T-040).
 * Carries ONLY the attachment id; the drain handler re-loads the attachment to read its current
 * {@code object_key} (a re-load is cheaper than a fat payload and never carries a stale key).
 *
 * <p>Plain record — Jackson 3 ({@code tools.jackson}) maps the {@code attachmentId} component by
 * name via {@code valueToTree}/{@code treeToValue}, exactly like {@code AttachmentDeletePayload}.
 * No serialization annotation (and no legacy-Jackson import) is needed.
 */
public record AttachmentThumbnailPayload(String attachmentId) {

  public static AttachmentThumbnailPayload of(UUID id) {
    return new AttachmentThumbnailPayload(id.toString());
  }
}
