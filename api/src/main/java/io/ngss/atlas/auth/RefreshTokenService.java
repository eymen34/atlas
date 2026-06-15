package io.ngss.atlas.auth;

import io.ngss.atlas.domain.RefreshToken;
import io.ngss.atlas.domain.RefreshTokenRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues, rotates, and revokes opaque refresh tokens. Only the SHA-256 hex of
 * the raw token is ever persisted (token_hash); the raw token is returned to
 * the client exactly once and never stored.
 *
 * <p>Rotation is race-safe: {@code markRevokedIfLive} returning affected==1 is
 * the gate, so two concurrent rotations of the same token yield exactly one
 * winner; the loser gets {@link InvalidCredentialsException} (401).
 */
@Service
public class RefreshTokenService {

  private static final SecureRandom SECURE_RANDOM = new SecureRandom();
  private static final int RAW_TOKEN_BYTES = 32;

  private final RefreshTokenRepository repo;
  private final Clock clock;
  private final long refreshTtlDays;

  /** Token pair carried back from {@link #rotate}. */
  public record RotateResult(UUID userId, String newRaw) {}

  @Autowired
  public RefreshTokenService(
      RefreshTokenRepository repo, @Value("${REFRESH_TOKEN_TTL_DAYS:30}") long refreshTtlDays) {
    this(repo, Clock.systemUTC(), refreshTtlDays);
  }

  // Package-private: tests inject a fixed Clock.
  RefreshTokenService(RefreshTokenRepository repo, Clock clock, long refreshTtlDays) {
    this.repo = repo;
    this.clock = clock;
    this.refreshTtlDays = refreshTtlDays;
  }

  @Transactional
  public String issue(UUID userId) {
    String raw = generateRawRefreshToken();
    Instant now = Instant.now(clock);
    repo.save(
        RefreshToken.create(
            userId, sha256Hex(raw), now, now.plus(refreshTtlDays, ChronoUnit.DAYS)));
    return raw;
  }

  @Transactional
  public RotateResult rotate(String rawToken) {
    RefreshToken current =
        repo.findByTokenHash(sha256Hex(rawToken)).orElseThrow(InvalidCredentialsException::new);
    if (current.getRevokedAt() != null) {
      // Reuse / replay of an already-rotated (or logged-out) token.
      throw new InvalidCredentialsException();
    }
    Instant now = Instant.now(clock);
    if (!current.getExpiresAt().isAfter(now)) {
      throw new InvalidCredentialsException();
    }
    if (repo.markRevokedIfLive(current.getId(), now) != 1) {
      // A concurrent rotation already revoked it — this caller lost the race.
      throw new InvalidCredentialsException();
    }
    String newRaw = issue(current.getUserId());
    UUID newId =
        repo.findByTokenHash(sha256Hex(newRaw))
            .orElseThrow(InvalidCredentialsException::new)
            .getId();
    repo.setReplacedBy(current.getId(), newId);
    repo.touchLastUsed(current.getId(), now);
    return new RotateResult(current.getUserId(), newRaw);
  }

  @Transactional
  public void revokeByRawToken(String rawToken, UUID callerUserId) {
    RefreshToken row = repo.findByTokenHash(sha256Hex(rawToken)).orElse(null);
    if (row == null) {
      // Idempotent and no existence oracle: unknown token logs out as a no-op.
      return;
    }
    if (!row.getUserId().equals(callerUserId)) {
      throw new ForbiddenTokenAccessException();
    }
    // Idempotent: an already-revoked token yields 0 affected rows, still 204.
    repo.markRevokedIfLive(row.getId(), Instant.now(clock));
  }

  /**
   * Revoke ALL of a user's live refresh tokens ("log out everywhere", T-032). Uses the same
   * host {@code Instant.now(clock)} as the single-token revoke (a one-shot revoke needs no DB
   * now()). Idempotent: 0 rows revoked (everything already logged out) is a valid no-op.
   */
  @Transactional
  public void logoutAll(UUID userId) {
    repo.revokeAllLive(userId, Instant.now(clock));
  }

  static String generateRawRefreshToken() {
    byte[] bytes = new byte[RAW_TOKEN_BYTES];
    SECURE_RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  static String sha256Hex(String input) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
