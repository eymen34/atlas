package io.ngss.atlas.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/**
 * Seventh JPA entity (T-017). Maps the V6 {@code project_ticket_counters} table —
 * one row per project holding {@code next_number}, the next ticket number to
 * assign. Seeded to {@code 1} at project creation ({@code ProjectService.create})
 * and backfilled for existing live projects by V6.
 *
 * <p>AppCDS cold-start hard rule (see {@code package-info.java} / N6): the
 * {@code projectId} IS the primary key (it equals {@link Project#getId()}) — NO
 * {@code @GeneratedValue}, no associations, no custom types.
 *
 * <p>This entity exists for the seed insert + {@code ddl-auto=validate} mapping.
 * Number CLAIMING does NOT mutate it through JPA — it uses an atomic native
 * {@code UPDATE ... RETURNING} (see {@code ProjectTicketCounterRepository}) so
 * concurrent claimers serialize on the row lock. Hence no mutator here.
 */
@Entity
@Table(name = "project_ticket_counters")
public class ProjectTicketCounter {

  @Id
  @Column(name = "project_id", nullable = false, updatable = false)
  private UUID projectId;

  @Column(name = "next_number", nullable = false)
  private int nextNumber;

  /** JPA-only no-args constructor. Do not use directly. */
  protected ProjectTicketCounter() {}

  public ProjectTicketCounter(UUID projectId, int nextNumber) {
    this.projectId = projectId;
    this.nextNumber = nextNumber;
  }

  public UUID getProjectId() {
    return projectId;
  }

  public int getNextNumber() {
    return nextNumber;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ProjectTicketCounter other)) {
      return false;
    }
    return projectId != null && projectId.equals(other.projectId);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(projectId);
  }
}
