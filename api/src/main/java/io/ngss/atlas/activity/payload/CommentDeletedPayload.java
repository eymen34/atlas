package io.ngss.atlas.activity.payload;

import java.util.UUID;

/**
 * Activity payload for {@code COMMENT_DELETED} (T-022). Serialized to the
 * {@code activity_events.payload} text column by Jackson.
 */
public record CommentDeletedPayload(UUID commentId) {}
