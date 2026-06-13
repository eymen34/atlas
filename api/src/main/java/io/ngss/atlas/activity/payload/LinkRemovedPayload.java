package io.ngss.atlas.activity.payload;

import io.ngss.atlas.domain.LinkRelation;
import java.util.UUID;

/**
 * Activity payload for {@code LINK_REMOVED} (T-026). Mirror of {@link LinkAddedPayload}:
 * written once per ticket in the pair, each from that ticket's perspective.
 */
public record LinkRemovedPayload(UUID otherTicketId, LinkRelation relation) {}
