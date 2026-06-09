package io.ngss.atlas.label;

/**
 * Thrown for service-level label validation failures the DTO bean-validation layer
 * cannot express — concretely, a PATCH with BOTH {@code name} and {@code color}
 * null (nothing to update). Mapped to HTTP 400 by GlobalExceptionHandler. The
 * message is safe to surface.
 */
public class LabelValidationException extends RuntimeException {

  public LabelValidationException(String message) {
    super(message);
  }
}
