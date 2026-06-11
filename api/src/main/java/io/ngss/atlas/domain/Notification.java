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
 * Fifteenth JPA entity (T-024). Maps the V11 {@code notifications} table — one
 * in-app notification for a recipient.
 *
 * <p>AppCDS cold-start hard rule: the {@code id} is application-generated via
 * {@code UUID.randomUUID()} — NO {@code @GeneratedValue}. {@code userId} /
 * {@code ticketId} / {@code sourceEventId} are plain UUID columns, NOT
 * {@code @ManyToOne} associations. {@code kind} is {@code @Enumerated(STRING)}
 * mapped to {@code varchar(32)}; {@code payload} is plain {@code text} holding
 * Jackson-serialized JSON (json_payload_as_text), NOT jsonb.
 */
@Entity
@Table(name = "notifications")
public class Notification {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, updatable = false, length = 32)
  private NotificationKind kind;

  @Column(name = "ticket_id", nullable = false, updatable = false)
  private UUID ticketId;

  @Column(name = "source_event_id", updatable = false)
  private UUID sourceEventId;

  @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "text")
  private String payload;

  @Column(name = "read_at")
  private Instant readAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  /** JPA-only no-args constructor. Do not use directly. */
  protected Notification() {}

  public Notification(
      UUID id,
      UUID userId,
      NotificationKind kind,
      UUID ticketId,
      UUID sourceEventId,
      String payload,
      Instant readAt,
      Instant createdAt) {
    this.id = Objects.requireNonNull(id, "id");
    this.userId = Objects.requireNonNull(userId, "userId");
    this.kind = Objects.requireNonNull(kind, "kind");
    this.ticketId = Objects.requireNonNull(ticketId, "ticketId");
    this.sourceEventId = sourceEventId;
    this.payload = Objects.requireNonNull(payload, "payload");
    this.readAt = readAt;
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
  }

  /** Stamps {@code readAt} (idempotent at the DB via the markRead query). */
  public void markRead(Instant now) {
    this.readAt = now;
  }

  public UUID getId() {
    return id;
  }

  public UUID getUserId() {
    return userId;
  }

  public NotificationKind getKind() {
    return kind;
  }

  public UUID getTicketId() {
    return ticketId;
  }

  public UUID getSourceEventId() {
    return sourceEventId;
  }

  public String getPayload() {
    return payload;
  }

  public Instant getReadAt() {
    return readAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Notification other)) {
      return false;
    }
    return id != null && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }
}
