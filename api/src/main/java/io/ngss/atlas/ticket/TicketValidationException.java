package io.ngss.atlas.ticket;

/**
 * Thrown for service-level ticket validation failures that the DTO bean-validation
 * layer cannot express. Mapped to HTTP 400 by GlobalExceptionHandler.
 *
 * <p>Concretely: a PATCH that supplies a present-but-blank {@code title}.
 * {@code @NotBlank} cannot mean "non-blank only when present" on a nullable record
 * component (null = unchanged is valid), so the check lives in the service
 * (mirrors {@code ProjectValidationException}). The message is safe to surface (no
 * rejected value echoed).
 */
public class TicketValidationException extends RuntimeException {

  public TicketValidationException(String message) {
    super(message);
  }
}
