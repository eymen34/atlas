package io.ngss.atlas.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Eleventh JPA entity (T-022). Maps the V9 {@code comments} table — one HTML
 * comment on a ticket. Soft-deleted by stamping {@code deletedAt} (D5: the row is
 * retained and server-redacted on read, never hard-deleted).
 *
 * <p>AppCDS cold-start hard rule (see {@code package-info.java} / N6): the
 * {@code id} is application-generated via {@code UUID.randomUUID()} — NO
 * {@code @GeneratedValue}. {@code ticketId}/{@code authorId} are plain UUID
 * columns, NOT {@code @ManyToOne} associations. {@code body} is plain {@code text}
 * holding TipTap-emitted HTML (D1; never markdown).
 */
@Entity
@Table(name = "comments")
public class Comment {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "ticket_id", nullable = false, updatable = false)
  private UUID ticketId;

  @Column(name = "author_id", nullable = false, updatable = false)
  private UUID authorId;

  @Column(name = "body", nullable = false, columnDefinition = "text")
  private String body;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  /** JPA-only no-args constructor. Do not use directly. */
  protected Comment() {}

  public Comment(
      UUID id,
      UUID ticketId,
      UUID authorId,
      String body,
      Instant createdAt,
      Instant updatedAt,
      Instant deletedAt) {
    this.id = Objects.requireNonNull(id, "id");
    this.ticketId = Objects.requireNonNull(ticketId, "ticketId");
    this.authorId = Objects.requireNonNull(authorId, "authorId");
    this.body = Objects.requireNonNull(body, "body");
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    this.deletedAt = deletedAt;
  }

  /** Replaces the body and advances {@code updatedAt} (author/admin edit). */
  public void editBody(String newBody, Instant now) {
    this.body = Objects.requireNonNull(newBody, "newBody");
    this.updatedAt = now;
  }

  /** Server-redacted soft delete: stamp {@code deletedAt} (the row is retained). */
  public void softDelete(Instant now) {
    this.deletedAt = now;
  }

  public boolean isDeleted() {
    return deletedAt != null;
  }

  public UUID getId() {
    return id;
  }

  public UUID getTicketId() {
    return ticketId;
  }

  public UUID getAuthorId() {
    return authorId;
  }

  public String getBody() {
    return body;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public Instant getDeletedAt() {
    return deletedAt;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Comment other)) {
      return false;
    }
    return id != null && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }
}
