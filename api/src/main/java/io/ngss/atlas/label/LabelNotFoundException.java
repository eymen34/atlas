package io.ngss.atlas.label;

/**
 * Thrown when a label cannot be resolved for the caller (genuinely missing, or —
 * after the load-then-guard step — in a project the caller is not a member of).
 * Mapped to HTTP 404 by GlobalExceptionHandler. Uniform 404 prevents existence
 * leakage, consistent with {@code ProjectNotFoundException}.
 */
public class LabelNotFoundException extends RuntimeException {

  public LabelNotFoundException(String message) {
    super(message);
  }
}
