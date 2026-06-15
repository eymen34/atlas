package io.ngss.atlas.auth;

import io.ngss.atlas.domain.RefreshTokenRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Isolates the {@code revokeAllLive} write in its own {@code REQUIRES_NEW} transaction so it
 * COMMITS independently before the outer {@link RefreshTokenService#rotate} transaction rolls back
 * on {@link InvalidCredentialsException} (jpa_rollback_only_trap, T-022). It MUST be called as a
 * Spring proxy bean — never via a {@code this.} self-invocation, which would bypass the proxy and
 * run the UPDATE in the outer transaction, where the subsequent throw silently discards it.
 *
 * <p>Pool size: {@code REQUIRES_NEW} acquires a SECOND HikariCP connection while the outer
 * {@code rotate()} transaction still holds one. This happens only on the (rare) theft path, so the
 * HikariCP default max pool size (10) is comfortably safe at current concurrency; raise it if this
 * path ever becomes hot.
 */
@Component
public class TokenTheftRevocationHelper {

  private final RefreshTokenRepository repo;

  public TokenTheftRevocationHelper(RefreshTokenRepository repo) {
    this.repo = repo;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void revokeAllLive(UUID userId, Instant now) {
    repo.revokeAllLive(userId, now);
  }
}
