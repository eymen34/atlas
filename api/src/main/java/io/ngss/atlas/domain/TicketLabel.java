package io.ngss.atlas.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Ninth JPA entity (T-018). Maps the V7 {@code ticket_labels} join table — a single
 * ticket↔label association.
 *
 * <p>AppCDS cold-start hard rule (see {@code package-info.java} / N6): a SURROGATE
 * {@code id} (app-generated via {@code UUID.randomUUID()} in the constructor) is
 * used deliberately INSTEAD of a composite {@code @EmbeddedId}/{@code @IdClass} —
 * the surrogate keeps the metamodel a plain single-column key with no DB
 * introspection. {@code ticketId}/{@code labelId} are plain UUID columns, NOT
 * {@code @ManyToOne} associations. Uniqueness of {@code (ticket_id, label_id)} is
 * enforced by the V7 unique constraint, not here.
 */
@Entity
@Table(name = "ticket_labels")
public class TicketLabel {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "ticket_id", nullable = false, updatable = false)
  private UUID ticketId;

  @Column(name = "label_id", nullable = false, updatable = false)
  private UUID labelId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  /** JPA-only no-args constructor. Do not use directly. */
  protected TicketLabel() {}

  /** Generates the surrogate id; {@code createdAt} is supplied by the service. */
  public TicketLabel(UUID ticketId, UUID labelId, Instant createdAt) {
    this.id = UUID.randomUUID();
    this.ticketId = ticketId;
    this.labelId = labelId;
    this.createdAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getTicketId() {
    return ticketId;
  }

  public UUID getLabelId() {
    return labelId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof TicketLabel other)) {
      return false;
    }
    return id != null && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }
}
