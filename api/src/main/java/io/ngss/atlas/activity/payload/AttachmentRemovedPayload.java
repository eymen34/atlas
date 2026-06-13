package io.ngss.atlas.activity.payload;

import java.util.UUID;

/**
 * Activity payload for {@code ATTACHMENT_REMOVED} (T-025). Serialized to the
 * {@code activity_events.payload} text column by Jackson.
 */
public record AttachmentRemovedPayload(UUID attachmentId) {}
