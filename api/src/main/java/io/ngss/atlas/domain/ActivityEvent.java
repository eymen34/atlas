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
 * Tenth JPA entity (T-019). Maps the V8 {@code activity_events} table — one
 * append-only, immutable row per recorded ticket-lifecycle change.
 *
 * <p>AppCDS cold-start hard rule (see {@code package-info.java} / N6): the
 * {@code id} is application-generated via {@code UUID.randomUUID()} — NO
 * {@code @GeneratedValue}. {@code ticketId}/{@code actorId} are plain UUID columns,
 * NOT {@code @ManyToOne} associations. {@code eventType} uses
 * {@code @Enumerated(STRING)} mapped to {@code varchar(32)} (no DB enum type).
 * {@code payload} is a plain {@code text} String holding Jackson-serialized JSON —
 * NOT a {@code jsonb} column (forbidden by the hard rule); it is parsed back to a
 * structured node only at the API edge.
 *
 * <p>Rows are never updated or deleted in normal operation (append-only), so all
 * columns are {@code updatable = false} and there are no mutators.
 */
@Entity
@Table(name = "activity_events")
public class ActivityEvent {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "ticket_id", nullable = false, updatable = false)
  private UUID ticketId;

  @Column(name = "actor_id", nullable = false, updatable = false)
  private UUID actorId;

  @Enumerated(EnumType.STRING)
  @Column(name = "event_type", nullable = false, updatable = false, length = 32)
  private ActivityEventType eventType;

  @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "text")
  private String payload;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  /** JPA-only no-args constructor. Do not use directly. */
  protected ActivityEvent() {}

  public ActivityEvent(
      UUID id,
      UUID ticketId,
      UUID actorId,
      ActivityEventType eventType,
      String payload,
      Instant createdAt) {
    this.id = Objects.requireNonNull(id, "id");
    this.ticketId = Objects.requireNonNull(ticketId, "ticketId");
    this.actorId = Objects.requireNonNull(actorId, "actorId");
    this.eventType = Objects.requireNonNull(eventType, "eventType");
    this.payload = Objects.requireNonNull(payload, "payload");
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
  }

  public UUID getId() {
    return id;
  }

  public UUID getTicketId() {
    return ticketId;
  }

  public UUID getActorId() {
    return actorId;
  }

  public ActivityEventType getEventType() {
    return eventType;
  }

  public String getPayload() {
    return payload;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ActivityEvent other)) {
      return false;
    }
    return id != null && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }
}
