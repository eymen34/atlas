package io.ngss.atlas.ticket;

/**
 * Thrown when a ticket-list query parameter has a syntactically invalid value that
 * bean-validation / type-conversion does not catch — concretely, an
 * {@code assigneeId} that is neither the literal {@code "unassigned"} nor a valid
 * UUID. Mapped to HTTP 400 by GlobalExceptionHandler. The message names the
 * parameter but never echoes a sensitive value.
 */
public class InvalidQueryParamException extends RuntimeException {

  public InvalidQueryParamException(String message) {
    super(message);
  }
}
