package io.ngss.atlas.notification;

import java.util.UUID;

/**
 * Thrown when a mark-read targets a notification that does not exist OR is not the
 * caller's (the caller-scoped UPDATE affected 0 rows). Mapped to 404 — a uniform
 * 404 (not 403) avoids leaking the existence of another user's notification (IDOR).
 */
public class NotificationNotFoundException extends RuntimeException {
  public NotificationNotFoundException(UUID id) {
    super("notification not found: " + id);
  }
}
