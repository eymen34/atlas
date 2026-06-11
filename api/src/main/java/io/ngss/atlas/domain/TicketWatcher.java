package io.ngss.atlas.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Fourteenth JPA entity (T-023). Maps the V10 {@code ticket_watchers} join table —
 * one (ticket, user) subscription for notifications (T-024).
 *
 * <p>AppCDS cold-start hard rule + join_entity_surrogate: a SURROGATE {@code id}
 * (app-generated) is used instead of a composite key; {@code ticketId}/{@code
 * userId} are plain UUID columns, NOT associations. Uniqueness of {@code (ticket_id,
 * user_id)} is enforced by the V10 constraint.
 *
 * <p>Rows are written via a native {@code INSERT ... ON CONFLICT DO NOTHING}
 * (WatcherRepository) — idempotent, no rollback-only trap — so the static factory
 * here is provided for read-side parity / future use; the field mapping exists so
 * Hibernate {@code ddl-auto=validate} (the ITs) and the HQL watcher query resolve.
 */
@Entity
@Table(
    name = "ticket_watchers",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_ticket_watchers",
            columnNames = {"ticket_id", "user_id"}))
public class TicketWatcher {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "ticket_id", nullable = false, updatable = false)
  private UUID ticketId;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  /** JPA-only no-args constructor. Do not use directly. */
  protected TicketWatcher() {}

  private TicketWatcher(UUID id, UUID ticketId, UUID userId, Instant createdAt) {
    this.id = id;
    this.ticketId = ticketId;
    this.userId = userId;
    this.createdAt = createdAt;
  }

  /** Builds a watcher row with a freshly generated surrogate id. */
  public static TicketWatcher newRow(UUID ticketId, UUID userId, Instant createdAt) {
    return new TicketWatcher(UUID.randomUUID(), ticketId, userId, createdAt);
  }

  public UUID getId() {
    return id;
  }

  public UUID getTicketId() {
    return ticketId;
  }

  public UUID getUserId() {
    return userId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof TicketWatcher other)) {
      return false;
    }
    return id != null && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }
}
