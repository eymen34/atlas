package io.ngss.atlas.label;

/**
 * Thrown when a PUT /api/tickets/{id}/labels request references a label that does
 * not exist or belongs to a DIFFERENT project than the ticket. Mapped to HTTP 400
 * by GlobalExceptionHandler — a label may only be attached to tickets within its
 * own project (prevents cross-project label leakage).
 */
public class CrossProjectLabelException extends RuntimeException {

  public CrossProjectLabelException(String message) {
    super(message);
  }
}
