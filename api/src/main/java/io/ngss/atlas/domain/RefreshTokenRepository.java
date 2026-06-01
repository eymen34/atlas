package io.ngss.atlas.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

  Optional<RefreshToken> findByTokenHash(String tokenHash);

  /**
   * Revoke a token only if it is currently live. Returns 1 when this call won
   * the revoke, 0 if it was already revoked / gone — the basis for race-safe
   * rotation (only the affected_rows==1 caller proceeds).
   */
  @Modifying
  @Transactional
  @Query(
      "update RefreshToken t set t.revokedAt = :now where t.id = :id and t.revokedAt is null")
  int markRevokedIfLive(@Param("id") UUID id, @Param("now") Instant now);

  @Modifying
  @Transactional
  @Query("update RefreshToken t set t.replacedById = :newId where t.id = :oldId")
  int setReplacedBy(@Param("oldId") UUID oldId, @Param("newId") UUID newId);

  @Modifying
  @Transactional
  @Query("update RefreshToken t set t.lastUsedAt = :now where t.id = :id")
  int touchLastUsed(@Param("id") UUID id, @Param("now") Instant now);
}
