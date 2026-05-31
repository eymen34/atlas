package io.ngss.atlas.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Maps the V2 {@code password_credentials} table (one row per user, keyed by
 * the user's id). Stores only the BCrypt hash, never the raw password.
 *
 * <p>Same AppCDS hard rule as {@link User}: {@code userId} is the
 * application-assigned primary key — NO {@code @GeneratedValue}.
 */
@Entity
@Table(name = "password_credentials")
public class PasswordCredential {

  @Id
  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "bcrypt_hash", nullable = false, length = 60)
  private String bcryptHash;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /** JPA-only no-args constructor. Do not use directly. */
  protected PasswordCredential() {}

  public PasswordCredential(UUID userId, String bcryptHash, Instant updatedAt) {
    this.userId = userId;
    this.bcryptHash = bcryptHash;
    this.updatedAt = updatedAt;
  }

  public UUID getUserId() {
    return userId;
  }

  public String getBcryptHash() {
    return bcryptHash;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof PasswordCredential other)) {
      return false;
    }
    return userId != null && userId.equals(other.userId);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(userId);
  }
}
