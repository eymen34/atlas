package io.ngss.atlas.activity.payload;

import java.util.UUID;

/**
 * Activity payload for {@code ASSIGNEE_CHANGED}. Either field MAY be null:
 * {@code from=null} means the ticket was previously unassigned, {@code to=null}
 * means it was unassigned by this change. Jackson serializes a null as JSON null.
 */
public record AssigneeChangedPayload(UUID from, UUID to) {}
