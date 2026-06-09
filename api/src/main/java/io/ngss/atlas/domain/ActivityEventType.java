package io.ngss.atlas.domain;

/**
 * The kind of change recorded in a ticket's activity log (T-019). Persisted as a
 * string via {@code @Enumerated(EnumType.STRING)} and constrained by the V8
 * {@code activity_events.event_type} CHECK.
 *
 * <p>T-019 actually writes only {@link #CREATED}, {@link #STATUS_CHANGED},
 * {@link #ASSIGNEE_CHANGED}, {@link #PRIORITY_CHANGED}, and {@link #LABELS_CHANGED};
 * the comment/attachment/link values are declared ahead of the tickets that will
 * emit them so the CHECK constraint need not be migrated repeatedly.
 *
 * <p>IMPORTANT: a future ticket adding a NEW event type MUST add the value here AND
 * extend the CHECK constraint in {@code activity_events} via a new Flyway
 * migration. Adding it in only one place makes runtime INSERTs of the new type fail
 * (DB) or makes a persisted value unmappable (Java).
 */
public enum ActivityEventType {
  CREATED,
  STATUS_CHANGED,
  ASSIGNEE_CHANGED,
  PRIORITY_CHANGED,
  LABELS_CHANGED,
  COMMENT_ADDED,
  COMMENT_EDITED,
  COMMENT_DELETED,
  ATTACHMENT_ADDED,
  ATTACHMENT_REMOVED,
  LINK_ADDED,
  LINK_REMOVED
}
