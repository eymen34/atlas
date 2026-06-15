package io.ngss.atlas.security;

import java.time.Instant;

/**
 * Thrown by {@link LoginAttemptService#checkThrottle} when the caller's account- or IP-bucket
 * is in an active lockout (T-033). {@link io.ngss.atlas.error.GlobalExceptionHandler} maps it to
 * HTTP 429 with a {@code Retry-After} header derived from {@link #getLockedUntil()}.
 */
public class TooManyLoginAttemptsException extends RuntimeException {

  private final Instant lockedUntil;

  public TooManyLoginAttemptsException(String message, Instant lockedUntil) {
    super(message);
    this.lockedUntil = lockedUntil;
  }

  public Instant getLockedUntil() {
    return lockedUntil;
  }
}
