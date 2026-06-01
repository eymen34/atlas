package io.ngss.atlas.auth;

/**
 * Raised when a caller tries to revoke a refresh token that belongs to a
 * different user. Mapped to HTTP 403 by GlobalExceptionHandler. (Accepted
 * existence-oracle trade-off; 256-bit token entropy makes probing infeasible.)
 */
public class ForbiddenTokenAccessException extends RuntimeException {

  public ForbiddenTokenAccessException() {
    super("Forbidden");
  }
}
