package io.ngss.atlas.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Sixteenth JPA entity (T-025). Maps the V12 {@code attachments} table — one
 * uploaded file whose bytes live in S3/MinIO under {@code object_key}.
 *
 * <p>AppCDS cold-start hard rule: {@code id} is application-generated via
 * {@code UUID.randomUUID()} — NO {@code @GeneratedValue}; {@code ticketId} /
 * {@code uploadedBy} are plain UUID columns, NOT {@code @ManyToOne} associations;
 * {@code status} is {@code @Enumerated(STRING)} → {@code varchar(32)}; the text
 * columns declare {@code columnDefinition = "text"} so {@code ddl-auto=validate}
 * matches the migration (json_payload_as_text alignment rule).
 */
@Entity
@Table(name = "attachments")
public class Attachment {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "ticket_id", nullable = false, updatable = false)
  private UUID ticketId;

  @Column(name = "uploaded_by", nullable = false, updatable = false)
  private UUID uploadedBy;

  @Column(name = "object_key", nullable = false, updatable = false, columnDefinition = "text")
  private String objectKey;

  @Column(name = "filename", nullable = false, updatable = false, columnDefinition = "text")
  private String filename;

  @Column(name = "content_type", nullable = false, updatable = false, columnDefinition = "text")
  private String contentType;

  @Column(name = "size_bytes", nullable = false, updatable = false)
  private long sizeBytes;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 32)
  private AttachmentStatus status;

  @Column(name = "thumbnail_object_key", columnDefinition = "text")
  private String thumbnailObjectKey;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "finalized_at")
  private Instant finalizedAt;

  /** JPA-only no-args constructor. Do not use directly. */
  protected Attachment() {}

  public Attachment(
      UUID id,
      UUID ticketId,
      UUID uploadedBy,
      String objectKey,
      String filename,
      String contentType,
      long sizeBytes,
      AttachmentStatus status,
      Instant createdAt) {
    this.id = Objects.requireNonNull(id, "id");
    this.ticketId = Objects.requireNonNull(ticketId, "ticketId");
    this.uploadedBy = Objects.requireNonNull(uploadedBy, "uploadedBy");
    this.objectKey = Objects.requireNonNull(objectKey, "objectKey");
    this.filename = Objects.requireNonNull(filename, "filename");
    this.contentType = Objects.requireNonNull(contentType, "contentType");
    this.sizeBytes = sizeBytes;
    this.status = Objects.requireNonNull(status, "status");
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
  }

  /** Transition PENDING → READY (finalize verified the object). */
  public void markReady(Instant finalizedAt) {
    this.status = AttachmentStatus.READY;
    this.finalizedAt = finalizedAt;
  }

  /** Transition → FAILED (finalize found a size/content-type mismatch or missing object). */
  public void markFailed() {
    this.status = AttachmentStatus.FAILED;
  }

  /** Record the generated thumbnail's object key (thumbnail worker, T-025). */
  public void attachThumbnail(String thumbnailObjectKey) {
    this.thumbnailObjectKey = thumbnailObjectKey;
  }

  /** Soft-delete (the S3 object removal is deferred to the T-029 outbox sweeper). */
  public void softDelete(Instant deletedAt) {
    this.deletedAt = deletedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getTicketId() {
    return ticketId;
  }

  public UUID getUploadedBy() {
    return uploadedBy;
  }

  public String getObjectKey() {
    return objectKey;
  }

  public String getFilename() {
    return filename;
  }

  public String getContentType() {
    return contentType;
  }

  public long getSizeBytes() {
    return sizeBytes;
  }

  public AttachmentStatus getStatus() {
    return status;
  }

  public String getThumbnailObjectKey() {
    return thumbnailObjectKey;
  }

  public Instant getDeletedAt() {
    return deletedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getFinalizedAt() {
    return finalizedAt;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Attachment other)) {
      return false;
    }
    return id != null && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }
}
