package io.ngss.atlas.auth;

/**
 * Generic authentication failure (unknown email, wrong password, invalid /
 * expired / revoked refresh token, malformed principal). The message is
 * deliberately uniform so login/refresh responses cannot be used to enumerate
 * accounts. Mapped to HTTP 401 by GlobalExceptionHandler.
 */
public class InvalidCredentialsException extends RuntimeException {

  public InvalidCredentialsException() {
    super("Invalid credentials");
  }
}
