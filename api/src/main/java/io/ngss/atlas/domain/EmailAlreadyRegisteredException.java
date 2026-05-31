package io.ngss.atlas.domain;

/**
 * Thrown by {@link RegistrationService} when an email is already registered.
 * Mapped to HTTP 409 by GlobalExceptionHandler. Carries the (normalized) email
 * for logging context; the message is deliberately generic.
 */
public class EmailAlreadyRegisteredException extends RuntimeException {

  private final String email;

  public EmailAlreadyRegisteredException(String email) {
    super("email already registered");
    this.email = email;
  }

  public String getEmail() {
    return email;
  }
}
