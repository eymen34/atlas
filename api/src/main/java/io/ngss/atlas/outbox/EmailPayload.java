package io.ngss.atlas.outbox;

/**
 * Outbox payload for an {@link OutboxKind#EMAIL_NOTIFICATION} (T-029). The {@code subject}
 * (e.g. {@code "[ENG-42] Fix login bug"}) and {@code body} are built at ENQUEUE time in
 * {@code NotificationEventListener} and stored verbatim — the handler does NOT re-construct
 * them, so the email reflects the ticket state at the moment of the change.
 */
public record EmailPayload(String toEmail, String subject, String body) {}
