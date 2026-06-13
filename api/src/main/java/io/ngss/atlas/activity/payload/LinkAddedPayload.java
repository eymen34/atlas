package io.ngss.atlas.activity.payload;

import io.ngss.atlas.domain.LinkRelation;
import java.util.UUID;

/**
 * Activity payload for {@code LINK_ADDED} (T-026). Written once per ticket in the
 * pair, each from THAT ticket's perspective: {@code otherTicketId} is the partner and
 * {@code relation} is this ticket's side of the relation (e.g. the blocking ticket
 * records {@code BLOCKS}, the blocked one records {@code IS_BLOCKED_BY}).
 */
public record LinkAddedPayload(UUID otherTicketId, LinkRelation relation) {}
