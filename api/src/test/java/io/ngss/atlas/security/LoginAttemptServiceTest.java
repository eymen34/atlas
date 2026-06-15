package io.ngss.atlas.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link LoginAttemptService} (T-033) — plain Mockito, no Spring context. */
@ExtendWith(MockitoExtension.class)
class LoginAttemptServiceTest {

  private static final String EMAIL = "user@example.com";
  private static final String IP = "203.0.113.9";

  @Mock LoginAttemptRepository repo;

  /** Default-config service (5 / 15 / 20, no trusted proxies). */
  private LoginAttemptService service() {
    return new LoginAttemptService(repo, 5, 15, 20, "");
  }

  @Test
  void checkThrottle_noRow_passes() {
    when(repo.findByKeyAndType(EMAIL, LoginAttemptKey.ACCOUNT)).thenReturn(Optional.empty());
    when(repo.findByKeyAndType(IP, LoginAttemptKey.IP)).thenReturn(Optional.empty());

    service().checkThrottle(EMAIL, IP); // does not throw
  }

  @Test
  void checkThrottle_activeLockout_throws() {
    LoginAttemptRecord locked =
        new LoginAttemptRecord(5, Instant.now().minusSeconds(60), Instant.now().plusSeconds(300));
    when(repo.findByKeyAndType(EMAIL, LoginAttemptKey.ACCOUNT)).thenReturn(Optional.of(locked));

    assertThatThrownBy(() -> service().checkThrottle(EMAIL, IP))
        .isInstanceOf(TooManyLoginAttemptsException.class);
  }

  @Test
  void checkThrottle_expiredLockout_passes() {
    LoginAttemptRecord expired =
        new LoginAttemptRecord(5, Instant.now().minusSeconds(2000), Instant.now().minusSeconds(60));
    when(repo.findByKeyAndType(EMAIL, LoginAttemptKey.ACCOUNT)).thenReturn(Optional.of(expired));
    when(repo.findByKeyAndType(IP, LoginAttemptKey.IP)).thenReturn(Optional.empty());

    service().checkThrottle(EMAIL, IP); // past locked_until → not locked → passes
  }

  @Test
  void recordFailure_callsUpsertForBothBuckets() {
    service().recordFailure(EMAIL, IP);

    verify(repo).upsertFailedAttempt(EMAIL, LoginAttemptKey.ACCOUNT, 5, 15); // account cap
    verify(repo).upsertFailedAttempt(IP, LoginAttemptKey.IP, 20, 15); // IP cap
    verify(repo, times(2)).upsertFailedAttempt(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
        org.mockito.ArgumentMatchers.anyInt());
  }

  @Test
  void recordFailure_doesNotReadAfterUpsert() {
    service().recordFailure(EMAIL, IP);
    verify(repo, never()).findByKeyAndType(any(), any());
  }

  @Test
  void clearAccountBucket_deletesOnlyAccountRow() {
    service().clearAccountBucket(EMAIL);
    verify(repo).deleteByKeyAndType(EMAIL, LoginAttemptKey.ACCOUNT);
    verify(repo, never()).deleteByKeyAndType(any(), eq(LoginAttemptKey.IP));
  }

  @Test
  void extractClientIp_noTrustedCidrs_returnsRemoteAddr() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRemoteAddr()).thenReturn("198.51.100.7");
    // XFF present but MUST be ignored (blank trust list → SEC-4).
    lenient().when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.7");

    assertThat(service().extractClientIp(request)).isEqualTo("198.51.100.7");
  }

  @Test
  void extractClientIp_remoteAddrInTrustedCidr_returnsLeftmostXFF() {
    LoginAttemptService trusting = new LoginAttemptService(repo, 5, 15, 20, "10.0.0.0/8");
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRemoteAddr()).thenReturn("10.1.2.3"); // within 10.0.0.0/8
    when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.7, 10.0.0.1");

    assertThat(trusting.extractClientIp(request)).isEqualTo("203.0.113.7"); // leftmost, trimmed
  }
}
