package io.ngss.atlas.domain;

/**
 * Priority of a {@link Ticket} (T-017). Persisted as a string via
 * {@code @Enumerated(EnumType.STRING)} (text + CHECK column in V6) and emitted as
 * a string enum in the OpenAPI spec. Mirrors {@link ProjectRole}.
 *
 * <p>P0 is the highest priority, P3 the lowest. The default when a create request
 * omits a priority is {@code P2} (applied in {@code TicketService}, not here).
 */
public enum TicketPriority {
  P0,
  P1,
  P2,
  P3
}
