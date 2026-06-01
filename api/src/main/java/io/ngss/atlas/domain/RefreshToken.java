package io.ngss.atlas.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Third JPA entity (T-012). Maps the V2 {@code refresh_tokens} table.
 *
 * <p>AppCDS cold-start hard rule (N6): the {@code id} is application-generated
 * via {@link #create} — NO {@code @GeneratedValue}. {@code replacedById} is a
 * plain UUID column, NOT a {@code @OneToOne} self-association, to keep the
 * entity graph trivial and the no-DB EntityManagerFactory boot deterministic.
 *
 * <p>{@code token_hash} is a {@code CHAR(64)} column (V2), so it is mapped with
 * {@link JdbcTypeCode}({@link SqlTypes#CHAR}) — a default String maps to VARCHAR
 * and would fail Hibernate schema validation (ddl-auto=validate) against CHAR.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "token_hash", nullable = false, length = 64, unique = true)
  @JdbcTypeCode(SqlTypes.CHAR)
  private String tokenHash;

  @Column(name = "issued_at", nullable = false, updatable = false)
  private Instant issuedAt;

  @Column(name = "last_used_at")
  private Instant lastUsedAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Column(name = "replaced_by_id")
  private UUID replacedById;

  /** JPA-only no-args constructor. Do not use directly. */
  protected RefreshToken() {}

  public static RefreshToken create(
      UUID userId, String tokenHash, Instant issuedAt, Instant expiresAt) {
    RefreshToken token = new RefreshToken();
    token.id = UUID.randomUUID();
    token.userId = userId;
    token.tokenHash = tokenHash;
    token.issuedAt = issuedAt;
    token.expiresAt = expiresAt;
    return token;
  }

  public UUID getId() {
    return id;
  }

  public UUID getUserId() {
    return userId;
  }

  public String getTokenHash() {
    return tokenHash;
  }

  public Instant getIssuedAt() {
    return issuedAt;
  }

  public Instant getLastUsedAt() {
    return lastUsedAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getRevokedAt() {
    return revokedAt;
  }

  public UUID getReplacedById() {
    return replacedById;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof RefreshToken other)) {
      return false;
    }
    return id != null && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }
}
