package io.ngss.atlas.attachment.dto;

import io.ngss.atlas.domain.Attachment;
import java.time.Instant;
import java.util.UUID;

/**
 * A READY attachment as returned by GET /api/tickets/{id}/attachments (T-025).
 * {@code hasThumbnail} tells the grid whether to request a thumbnail download-url.
 */
public record AttachmentResponse(
    UUID id,
    UUID ticketId,
    String filename,
    String contentType,
    long sizeBytes,
    UUID uploadedBy,
    boolean hasThumbnail,
    Instant createdAt) {

  public static AttachmentResponse from(Attachment a) {
    return new AttachmentResponse(
        a.getId(),
        a.getTicketId(),
        a.getFilename(),
        a.getContentType(),
        a.getSizeBytes(),
        a.getUploadedBy(),
        a.getThumbnailObjectKey() != null,
        a.getCreatedAt());
  }
}
