package io.ngss.atlas.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.ngss.atlas.security.TooManyLoginAttemptsException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Unit tests for the T-033 429 handler — plain Mockito, direct method call. */
class GlobalExceptionHandlerLoginThrottleTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  private HttpServletRequest request() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRequestURI()).thenReturn("/api/auth/login");
    return request;
  }

  @Test
  void handle_returns429WithRetryAfterHeader() {
    Instant lockedUntil = Instant.now().plusSeconds(300);

    ResponseEntity<GlobalExceptionHandler.ErrorBody> response =
        handler.handleTooManyLoginAttempts(
            new TooManyLoginAttemptsException("locked", lockedUntil), request());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    long retryAfter = Long.parseLong(response.getHeaders().getFirst("Retry-After"));
    assertThat(retryAfter).isGreaterThanOrEqualTo(1L).isLessThanOrEqualTo(300L);

    GlobalExceptionHandler.ErrorBody body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.status()).isEqualTo(429);
    assertThat(body.error()).isEqualTo("Too Many Requests");
    assertThat(body.path()).isEqualTo("/api/auth/login");
  }

  @Test
  void handle_retryAfterIsAtLeastOne() {
    // Already-expired lockedUntil → ChronoUnit.between <= 0 → Math.max(1, …) clamps to 1.
    ResponseEntity<GlobalExceptionHandler.ErrorBody> response =
        handler.handleTooManyLoginAttempts(
            new TooManyLoginAttemptsException("locked", Instant.now()), request());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(Long.parseLong(response.getHeaders().getFirst("Retry-After"))).isEqualTo(1L);
  }
}
