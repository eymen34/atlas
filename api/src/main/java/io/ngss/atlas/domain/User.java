package io.ngss.atlas.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * First JPA entity in the project. Maps the V1 {@code users} table (V3 adds
 * {@code display_name}).
 *
 * <p>AppCDS cold-start hard rule (see {@code package-info.java} / N6): the
 * {@code id} is application-generated — NO {@code @GeneratedValue}, no custom
 * Hibernate types, no JSON columns. Identifier values are assigned by
 * {@code RegistrationService} via {@code UUID.randomUUID()} so the
 * EntityManagerFactory initializes during the Dockerfile stage-3 no-DB boot
 * without ever needing a database (no identity/sequence generator metadata).
 */
@Entity
@Table(name = "users")
public class User {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "email", nullable = false)
  private String email;

  @Column(name = "display_name", nullable = false, length = 80)
  private String displayName;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /**
   * Global-unique @mention handle (T-022, D3), derived ONCE from the email
   * local-part at registration. It is deliberately NOT {@code updatable = false}
   * (so the registration retry can re-set it before the first flush), but the
   * value is FROZEN after registration: no email-change code path may call
   * {@link #setMentionHandle} — re-deriving on email change would break existing
   * mentions and is explicitly out of scope.
   */
  @Column(name = "mention_handle", nullable = false, length = 64)
  private String mentionHandle;

  /**
   * Per-recipient email opt-out (T-029, V15). DEFAULT true at the DB; the Java field
   * defaults to {@code true} so a newly-registered {@link User} (constructed without
   * touching this flag) persists as opt-in. The notification fan-out gates email
   * enqueue on this; the in-app notification row is written regardless.
   */
  @Column(name = "email_notifications_enabled", nullable = false)
  private boolean emailNotificationsEnabled = true;

  /** JPA-only no-args constructor. Do not use directly. */
  protected User() {}

  public User(UUID id, String email, String displayName, Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.email = email;
    this.displayName = displayName;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public String getEmail() {
    return email;
  }

  public String getDisplayName() {
    return displayName;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public String getMentionHandle() {
    return mentionHandle;
  }

  public boolean isEmailNotificationsEnabled() {
    return emailNotificationsEnabled;
  }

  public void setEmailNotificationsEnabled(boolean emailNotificationsEnabled) {
    this.emailNotificationsEnabled = emailNotificationsEnabled;
  }

  /**
   * Sets the @mention handle. MUST be called exactly once, at registration only —
   * see the field javadoc (D3: frozen on email change).
   */
  public void setMentionHandle(String mentionHandle) {
    this.mentionHandle = mentionHandle;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof User other)) {
      return false;
    }
    return id != null && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }
}
