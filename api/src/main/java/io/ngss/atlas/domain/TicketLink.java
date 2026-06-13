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
 * Seventeenth JPA entity (T-026). Maps the V13 {@code ticket_links} table — one
 * directional relation row. A user action persists TWO of these (the relation + its
 * {@link LinkRelation#inverse}); they are paired by (from,to) ↔ (to,from).
 *
 * <p>AppCDS cold-start hard rule: {@code id} is application-generated via
 * {@code UUID.randomUUID()} — NO {@code @GeneratedValue}; {@code fromTicketId} /
 * {@code toTicketId} / {@code createdBy} are plain UUID columns, NOT {@code @ManyToOne}
 * associations; {@code relation} is {@code @Enumerated(STRING)} → {@code varchar(32)}.
 */
@Entity
@Table(name = "ticket_links")
public class TicketLink {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "from_ticket_id", nullable = false, updatable = false)
  private UUID fromTicketId;

  @Column(name = "to_ticket_id", nullable = false, updatable = false)
  private UUID toTicketId;

  @Enumerated(EnumType.STRING)
  @Column(name = "relation", nullable = false, updatable = false, length = 32)
  private LinkRelation relation;

  @Column(name = "created_by", nullable = false, updatable = false)
  private UUID createdBy;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  /** JPA-only no-args constructor. Do not use directly. */
  protected TicketLink() {}

  public TicketLink(
      UUID id,
      UUID fromTicketId,
      UUID toTicketId,
      LinkRelation relation,
      UUID createdBy,
      Instant createdAt) {
    this.id = Objects.requireNonNull(id, "id");
    this.fromTicketId = Objects.requireNonNull(fromTicketId, "fromTicketId");
    this.toTicketId = Objects.requireNonNull(toTicketId, "toTicketId");
    this.relation = Objects.requireNonNull(relation, "relation");
    this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
  }

  public UUID getId() {
    return id;
  }

  public UUID getFromTicketId() {
    return fromTicketId;
  }

  public UUID getToTicketId() {
    return toTicketId;
  }

  public LinkRelation getRelation() {
    return relation;
  }

  public UUID getCreatedBy() {
    return createdBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof TicketLink other)) {
      return false;
    }
    return id != null && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }
}
