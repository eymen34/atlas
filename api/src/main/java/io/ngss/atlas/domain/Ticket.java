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
 * Sixth JPA entity (T-017). Maps the V6 {@code tickets} table — a work item
 * within a project, with a per-project monotonic {@code number}.
 *
 * <p>AppCDS cold-start hard rule (see {@code package-info.java} / N6): the
 * {@code id} is application-generated via {@code UUID.randomUUID()} in the service
 * — NO {@code @GeneratedValue}. {@code number} is also app-set (the value is
 * claimed from {@code project_ticket_counters} at create time, NOT a
 * {@code @GeneratedValue}/sequence). {@code projectId}/{@code assigneeId}/
 * {@code reporterId} are plain UUID columns, NOT {@code @ManyToOne} associations
 * (same pattern as {@link ProjectMember}), so the metamodel stays trivial and the
 * no-DB EntityManagerFactory boot is deterministic. {@code status}/{@code priority}
 * use {@code @Enumerated(STRING)} (AppCDS-safe — no DB introspection).
 *
 * <p>Timestamps are PLAIN fields set EXPLICITLY by the service layer (no
 * {@code @PrePersist}/{@code @PreUpdate}, no DB trigger), mirroring {@link Project}.
 * The narrow mutators ({@link #updateFields}, {@link #transition},
 * {@link #softDelete}) advance {@code updatedAt} themselves. There are no open
 * setters.
 */
@Entity
@Table(name = "tickets")
public class Ticket {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "project_id", nullable = false, updatable = false)
  private UUID projectId;

  @Column(name = "number", nullable = false, updatable = false)
  private int number;

  @Column(name = "title", nullable = false)
  private String title;

  @Column(name = "description")
  private String description;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false)
  private TicketStatus status;

  @Enumerated(EnumType.STRING)
  @Column(name = "priority", nullable = false)
  private TicketPriority priority;

  @Column(name = "assignee_id")
  private UUID assigneeId;

  @Column(name = "reporter_id", nullable = false, updatable = false)
  private UUID reporterId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  /** JPA-only no-args constructor. Do not use directly. */
  protected Ticket() {}

  public Ticket(
      UUID id,
      UUID projectId,
      int number,
      String title,
      String description,
      TicketStatus status,
      TicketPriority priority,
      UUID assigneeId,
      UUID reporterId,
      Instant createdAt,
      Instant updatedAt,
      Instant deletedAt) {
    this.id = id;
    this.projectId = projectId;
    this.number = number;
    this.title = title;
    this.description = description;
    this.status = status;
    this.priority = priority;
    this.assigneeId = assigneeId;
    this.reporterId = reporterId;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
    this.deletedAt = deletedAt;
  }

  /**
   * PATCH-style partial update of the editable fields and advances
   * {@code updatedAt} to {@code now}. Status is NOT changed here (the transition
   * endpoint owns status); {@code createdAt}/{@code number}/{@code reporterId} are
   * never touched. Callers resolve null-means-unchanged semantics before calling.
   */
  public void updateFields(
      String newTitle,
      String newDescription,
      UUID newAssigneeId,
      TicketPriority newPriority,
      Instant now) {
    this.title = newTitle;
    this.description = newDescription;
    this.assigneeId = newAssigneeId;
    this.priority = newPriority;
    this.updatedAt = now;
  }

  /** Transitions to {@code toStatus} and advances {@code updatedAt} to {@code now}. */
  public void transition(TicketStatus toStatus, Instant now) {
    this.status = toStatus;
    this.updatedAt = now;
  }

  /** Soft-deletes the ticket: stamps {@code deletedAt} and advances {@code updatedAt}. */
  public void softDelete(Instant now) {
    this.deletedAt = now;
    this.updatedAt = now;
  }

  public UUID getId() {
    return id;
  }

  public UUID getProjectId() {
    return projectId;
  }

  public int getNumber() {
    return number;
  }

  public String getTitle() {
    return title;
  }

  public String getDescription() {
    return description;
  }

  public TicketStatus getStatus() {
    return status;
  }

  public TicketPriority getPriority() {
    return priority;
  }

  public UUID getAssigneeId() {
    return assigneeId;
  }

  public UUID getReporterId() {
    return reporterId;
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
    if (!(o instanceof Ticket other)) {
      return false;
    }
    return id != null && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }
}
