package io.ngss.atlas.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Fifth JPA entity (T-015). Maps the V5 {@code project_members} table — a
 * user's membership + role within a project.
 *
 * <p>AppCDS cold-start hard rule (see {@code package-info.java} / N6): the
 * {@code id} is application-generated via {@code UUID.randomUUID()} — NO
 * {@code @GeneratedValue}. {@code projectId}/{@code userId}/{@code invitedBy} are
 * plain UUID columns, NOT {@code @ManyToOne} associations (same pattern as
 * {@link Project#getCreatedBy()} and {@code RefreshToken.replacedById}) so the
 * metamodel stays trivial and the no-DB EntityManagerFactory boot is
 * deterministic. {@code role} uses {@code @Enumerated(STRING)} (AppCDS-safe — no
 * DB introspection).
 */
@Entity
@Table(
    name = "project_members",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_project_members_project_user",
            columnNames = {"project_id", "user_id"}))
public class ProjectMember {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "project_id", nullable = false, updatable = false)
  private UUID projectId;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Enumerated(EnumType.STRING)
  @Column(name = "role", nullable = false)
  private ProjectRole role;

  @Column(name = "invited_by", updatable = false)
  private UUID invitedBy;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  /** JPA-only no-args constructor. Do not use directly. */
  protected ProjectMember() {}

  public ProjectMember(
      UUID id,
      UUID projectId,
      UUID userId,
      ProjectRole role,
      UUID invitedBy,
      Instant createdAt) {
    this.id = id;
    this.projectId = projectId;
    this.userId = userId;
    this.role = role;
    this.invitedBy = invitedBy;
    this.createdAt = createdAt;
  }

  /** Changes the member's role. The only mutable field. */
  public void changeRole(ProjectRole newRole) {
    this.role = newRole;
  }

  public UUID getId() {
    return id;
  }

  public UUID getProjectId() {
    return projectId;
  }

  public UUID getUserId() {
    return userId;
  }

  public ProjectRole getRole() {
    return role;
  }

  public UUID getInvitedBy() {
    return invitedBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ProjectMember other)) {
      return false;
    }
    return id != null && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }
}
