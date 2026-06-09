package io.ngss.atlas.activity.dto;

import io.ngss.atlas.domain.ActivityEvent;
import io.ngss.atlas.domain.ActivityEventType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Response element for GET /api/tickets/{id}/activity (T-019).
 *
 * <p>{@code payload} is a parsed {@link JsonNode}, NOT the raw stored string, so the
 * HTTP body carries a real nested JSON object (e.g. {@code "payload":{"from":...}})
 * rather than an escaped string literal ({@code "payload":"{\"from\":...}"}).
 */
public record ActivityEventResponse(
    UUID id,
    UUID ticketId,
    UUID actorId,
    ActivityEventType eventType,
    @Schema(
            description =
                "Structured, event-type-specific payload. CREATED={title,status,priority}; "
                    + "STATUS_CHANGED={from,to}; ASSIGNEE_CHANGED={from,to} (nullable UUIDs); "
                    + "PRIORITY_CHANGED={from,to}; LABELS_CHANGED={added:[UUID],removed:[UUID]}.",
            type = "object")
        JsonNode payload,
    Instant createdAt) {

  /** Parses the stored JSON payload into a {@link JsonNode}; a parse failure is a 500. */
  public static ActivityEventResponse from(ActivityEvent ev, ObjectMapper om) {
    JsonNode node;
    try {
      node = om.readTree(ev.getPayload());
    } catch (JacksonException e) {
      throw new IllegalStateException("activity payload parse failed for id=" + ev.getId(), e);
    }
    return new ActivityEventResponse(
        ev.getId(), ev.getTicketId(), ev.getActorId(), ev.getEventType(), node, ev.getCreatedAt());
  }
}
