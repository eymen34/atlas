package io.ngss.atlas.domain;

/**
 * Workflow status of a {@link Ticket} (T-017). Persisted as a string via
 * {@code @Enumerated(EnumType.STRING)} (text + CHECK column in V6) and emitted as
 * a string enum in the OpenAPI spec. Mirrors {@link ProjectRole}.
 *
 * <p>MVP workflow is unrestricted (any status may transition to any other); there
 * is no state-machine. There is deliberately NO {@code CANCELED} state.
 */
public enum TicketStatus {
  TODO,
  IN_PROGRESS,
  IN_REVIEW,
  DONE
}
