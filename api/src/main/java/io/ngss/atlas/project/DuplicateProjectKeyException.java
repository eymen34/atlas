package io.ngss.atlas.project;

/**
 * Thrown when a project key collides with an existing live (non-deleted)
 * project. Mapped to HTTP 409 by GlobalExceptionHandler.
 *
 * <p>Raised on the pre-check ({@code existsByKeyAndDeletedAtIsNull}) AND on the
 * concurrent-race path, where the partial unique index rejects the insert with a
 * {@code DataIntegrityViolationException} that the service catches and rethrows
 * as this exception (so a race surfaces as 409, never 500).
 */
public class DuplicateProjectKeyException extends RuntimeException {

  private final String key;

  public DuplicateProjectKeyException(String key) {
    super("Project key already in use");
    this.key = key;
  }

  public String getKey() {
    return key;
  }
}
