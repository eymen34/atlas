package io.ngss.atlas.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Eighth JPA entity (T-018). Maps the V7 {@code labels} table — a named, optionally
 * colored tag scoped to a single project.
 *
 * <p>AppCDS cold-start hard rule (see {@code package-info.java} / N6): the
 * {@code id} is application-generated via {@code UUID.randomUUID()} in the service
 * — NO {@code @GeneratedValue}. {@code projectId} is a plain UUID column, NOT a
 * {@code @ManyToOne} association, so the metamodel stays trivial and the no-DB
 * EntityManagerFactory boot is deterministic.
 *
 * <p>Case-insensitive name uniqueness within a project is enforced by the V7
 * functional unique index {@code labels_project_id_lower_name_uidx}, not here.
 */
@Entity
@Table(name = "labels")
public class Label {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "project_id", nullable = false, updatable = false)
  private UUID projectId;

  @Column(name = "name", nullable = false)
  private String name;

  @Column(name = "color")
  private String color;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  /** JPA-only no-args constructor. Do not use directly. */
  protected Label() {}

  public Label(UUID id, UUID projectId, String name, String color, Instant createdAt) {
    this.id = id;
    this.projectId = projectId;
    this.name = name;
    this.color = color;
    this.createdAt = createdAt;
  }

  /** Updates the editable fields. Callers resolve null-means-unchanged beforehand. */
  public void updateNameColor(String newName, String newColor) {
    this.name = newName;
    this.color = newColor;
  }

  public UUID getId() {
    return id;
  }

  public UUID getProjectId() {
    return projectId;
  }

  public String getName() {
    return name;
  }

  public String getColor() {
    return color;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Label other)) {
      return false;
    }
    return id != null && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }
}
