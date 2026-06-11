package io.ngss.atlas.domain;

/**
 * The kind of in-app notification (T-024). Persisted as a string via
 * {@code @Enumerated(EnumType.STRING)} and constrained by the V11
 * {@code notifications.kind} CHECK — the names here MUST match that constraint
 * exactly.
 */
public enum NotificationKind {
  ASSIGNED,
  MENTIONED_TICKET,
  MENTIONED_COMMENT,
  WATCHED_STATUS_CHANGED
}
