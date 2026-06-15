package io.ngss.atlas.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.ngss.atlas.domain.RefreshToken;
import io.ngss.atlas.domain.RefreshTokenRepository;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Unit tests for the T-031 reuse-detection logic in {@link RefreshTokenService#rotate}: which
 * replay paths trigger the user-scoped mass revoke, that the revoke runs through the
 * {@link TokenTheftRevocationHelper} proxy, and that a 401 is ALWAYS returned (never a 500) even
 * when the helper fails. Constructed via the package-private fixed-{@link Clock} constructor.
 */
@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceRotateUnitTest {

  private static final Instant FIXED_INSTANT = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant FUTURE = FIXED_INSTANT.plusSeconds(3600);
  private static final Instant PAST = FIXED_INSTANT.minusSeconds(3600);
  private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

  private final Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

  @Mock RefreshTokenRepository repo;
  @Mock TokenTheftRevocationHelper helper;

  private RefreshTokenService service() {
    return new RefreshTokenService(repo, clock, 30L, helper);
  }

  // ───────────────────────── AC-4.1: lost concurrent race → no mass revoke ─────────────────────────

  @Test
  void concurrentRotation_loserGets401_noMassRevoke() {
    RefreshToken token = mock(RefreshToken.class);
    when(token.getExpiresAt()).thenReturn(FUTURE); // live, revokedAt defaults to null
    when(token.getId()).thenReturn(UUID.randomUUID());
    when(repo.findByTokenHash(anyString())).thenReturn(Optional.of(token));
    when(repo.markRevokedIfLive(any(), any())).thenReturn(0); // lost the CAS

    assertThatThrownBy(() -> service().rotate("raw"))
        .isInstanceOf(InvalidCredentialsException.class);

    verify(helper, never()).revokeAllLive(any(), any());
  }

  // ───────────────────────── AC-5: expired (never revoked) → step 3, no revoke ─────────────────────

  @Test
  void expiredToken_step3_noMassRevoke() {
    RefreshToken token = mock(RefreshToken.class);
    when(token.getExpiresAt()).thenReturn(PAST); // expired, revokedAt null
    when(repo.findByTokenHash(anyString())).thenReturn(Optional.of(token));

    assertThatThrownBy(() -> service().rotate("raw"))
        .isInstanceOf(InvalidCredentialsException.class);

    verify(helper, never()).revokeAllLive(any(), any());
  }

  // ───────────────────────── theft signal → helper called, then 401 ────────────────────────────────

  @Test
  void revokedRotatedToken_callsHelper_thenThrows() {
    RefreshToken token = mock(RefreshToken.class);
    when(token.getRevokedAt()).thenReturn(PAST);
    when(token.getReplacedById()).thenReturn(UUID.randomUUID()); // was rotated → theft signal
    when(token.getUserId()).thenReturn(USER_ID);
    when(repo.findByTokenHash(anyString())).thenReturn(Optional.of(token));

    assertThatThrownBy(() -> service().rotate("raw"))
        .isInstanceOf(InvalidCredentialsException.class);

    verify(helper).revokeAllLive(eq(USER_ID), eq(FIXED_INSTANT));
  }

  @Test
  void revokedNoSuccessor_helperNeverCalled_thenThrows() {
    RefreshToken token = mock(RefreshToken.class);
    when(token.getRevokedAt()).thenReturn(PAST); // replacedById defaults to null → logged-out head
    when(repo.findByTokenHash(anyString())).thenReturn(Optional.of(token));

    assertThatThrownBy(() -> service().rotate("raw"))
        .isInstanceOf(InvalidCredentialsException.class);

    verify(helper, never()).revokeAllLive(any(), any());
  }

  // ───────────────────────── EC-1: helper throws → still 401, not 500 ──────────────────────────────

  @Test
  void theftBranch_helperThrows_InvalidCredentialsExceptionStillReturned() {
    RefreshToken token = mock(RefreshToken.class);
    when(token.getRevokedAt()).thenReturn(PAST);
    when(token.getReplacedById()).thenReturn(UUID.randomUUID());
    when(token.getUserId()).thenReturn(USER_ID);
    when(repo.findByTokenHash(anyString())).thenReturn(Optional.of(token));
    doThrow(new RuntimeException("DB timeout")).when(helper).revokeAllLive(any(), any());

    assertThatThrownBy(() -> service().rotate("raw"))
        .isInstanceOf(InvalidCredentialsException.class); // NOT RuntimeException

    verify(helper).revokeAllLive(any(), any());
  }

  // ───────────────────────── SEC-1: WARN "reuse detected" logged BEFORE the failure ERROR ──────────

  @Test
  void theftDetectionWarn_logsBeforeRevokeFailureError() {
    RefreshToken token = mock(RefreshToken.class);
    when(token.getRevokedAt()).thenReturn(PAST);
    when(token.getReplacedById()).thenReturn(UUID.randomUUID());
    when(token.getUserId()).thenReturn(USER_ID);
    when(repo.findByTokenHash(anyString())).thenReturn(Optional.of(token));
    doThrow(new RuntimeException("DB timeout")).when(helper).revokeAllLive(any(), any());

    ch.qos.logback.classic.Logger serviceLogger =
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(RefreshTokenService.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    serviceLogger.addAppender(appender);
    try {
      assertThatThrownBy(() -> service().rotate("raw"))
          .isInstanceOf(InvalidCredentialsException.class);
    } finally {
      serviceLogger.detachAppender(appender);
    }

    List<ILoggingEvent> events = appender.list;
    int detectIdx = indexOf(events, Level.WARN, "reuse detected");
    int failIdx = indexOf(events, Level.ERROR, "revokeAllLive failed");
    assertThat(detectIdx).as("WARN 'reuse detected' present").isNotNegative();
    assertThat(failIdx).as("ERROR 'revokeAllLive failed' present").isNotNegative();
    assertThat(detectIdx).as("detection WARN precedes failure ERROR").isLessThan(failIdx);
  }

  // ───────────────────────── REG-3: REQUIRES_NEW annotation guard ──────────────────────────────────

  @Test
  void helperRevokeAllLive_isAnnotatedRequiresNew() throws Exception {
    Method m =
        TokenTheftRevocationHelper.class.getDeclaredMethod(
            "revokeAllLive", UUID.class, Instant.class);
    Transactional tx = m.getAnnotation(Transactional.class);
    assertThat(tx).as("@Transactional present on revokeAllLive").isNotNull();
    assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
  }

  private static int indexOf(List<ILoggingEvent> events, Level level, String needle) {
    for (int i = 0; i < events.size(); i++) {
      ILoggingEvent e = events.get(i);
      if (e.getLevel() == level && e.getFormattedMessage().contains(needle)) {
        return i;
      }
    }
    return -1;
  }
}
