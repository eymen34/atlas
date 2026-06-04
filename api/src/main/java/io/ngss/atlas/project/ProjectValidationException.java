package io.ngss.atlas.project;

/**
 * Thrown for service-level validation failures that the DTO bean-validation
 * layer cannot express. Mapped to HTTP 400 by GlobalExceptionHandler.
 *
 * <p>Concretely: a PATCH that supplies a present-but-blank {@code name}.
 * {@code @NotBlank} cannot mean "non-blank only when present" on a nullable
 * record component (null = unchanged is valid), so the check lives in the
 * service and raises this exception. The message is safe to surface (no rejected
 * value echoed).
 */
public class ProjectValidationException extends RuntimeException {

  public ProjectValidationException(String message) {
    super(message);
  }
}
