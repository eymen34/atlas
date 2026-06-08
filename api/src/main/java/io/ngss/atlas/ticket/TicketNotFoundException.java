package io.ngss.atlas.ticket;

/**
 * Thrown when a ticket cannot be resolved for the caller. Mapped to HTTP 404 by
 * GlobalExceptionHandler.
 *
 * <p>Raised for genuinely-missing tickets, soft-deleted tickets, an unparsable
 * id-or-key segment, AND (after a two-step load + {@code guard.requireMember})
 * tickets in a project the caller is not a member of — collapsing all of these to
 * a uniform 404 prevents existence leakage, consistent with
 * {@code ProjectNotFoundException}.
 */
public class TicketNotFoundException extends RuntimeException {

  public TicketNotFoundException(String message) {
    super(message);
  }
}
