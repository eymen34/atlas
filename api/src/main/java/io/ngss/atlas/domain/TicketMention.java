package io.ngss.atlas.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.Objects;
import java.util.UUID;

/**
 * Thirteenth JPA entity (T-022). Maps the V9 {@code ticket_mentions} join table —
 * one resolved @mention of a user within a ticket's HTML description.
 *
 * <p>AppCDS cold-start hard rule + join_entity_surrogate: a SURROGATE
 * {@code id} (app-generated in the constructor) is used instead of a composite key;
 * {@code ticketId}/{@code userId} are plain UUID columns, NOT associations.
 * Uniqueness of {@code (ticket_id, user_id)} is enforced by the V9 constraint.
 */
@Entity
@Table(
    name = "ticket_mentions",
    uniqueConstraints =
        @UniqueConstraint(
            name = "ticket_mentions_ticket_user_key",
            columnNames = {"ticket_id", "user_id"}))
public class TicketMention {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "ticket_id", nullable = false, updatable = false)
  private UUID ticketId;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  /** JPA-only no-args constructor. Do not use directly. */
  protected TicketMention() {}

  /** Generates the surrogate id; the (ticketId, userId) pair is supplied. */
  public TicketMention(UUID ticketId, UUID userId) {
    this.id = UUID.randomUUID();
    this.ticketId = Objects.requireNonNull(ticketId, "ticketId");
    this.userId = Objects.requireNonNull(userId, "userId");
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

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof TicketMention other)) {
      return false;
    }
    return id != null && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }
}
