package io.ngss.atlas.label;

/**
 * Thrown when a label name collides (case-insensitively) with an existing label in
 * the same project. Mapped to HTTP 409 by GlobalExceptionHandler.
 *
 * <p>Raised on the create pre-check ({@code findByProjectIdAndNameIgnoreCase}) AND
 * on the concurrent-race / rename path, where the V7 functional unique index
 * rejects the write with a {@code DataIntegrityViolationException} that the service
 * catches and rethrows as this exception (so a race surfaces as 409, never 500).
 */
public class DuplicateLabelNameException extends RuntimeException {

  public DuplicateLabelNameException(String message) {
    super(message);
  }
}
