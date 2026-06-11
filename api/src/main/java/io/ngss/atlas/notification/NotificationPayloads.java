package io.ngss.atlas.notification;

import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Serializes/deserializes the {@code notifications.payload} JSON (T-024, D1) using
 * Jackson 3 ({@code tools.jackson.*} — NEVER {@code com.fasterxml.jackson.*}).
 *
 * <p>Schema v1: {@code {actorId, fromStatus?, toStatus?, commentId?}}. actorId is
 * denormalized into the payload (not an FK) so a notification survives even if the
 * actor is later removed. The version is implicit; the record is forward-compatible
 * (unknown future fields are ignored on read by the default ObjectMapper config).
 */
@Component
public class NotificationPayloads {

  public record PayloadV1(UUID actorId, String fromStatus, String toStatus, UUID commentId) {}

  private final ObjectMapper objectMapper;

  public NotificationPayloads(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public String forAssigned(UUID actorId) {
    return toJson(new PayloadV1(actorId, null, null, null));
  }

  public String forMentionedTicket(UUID actorId) {
    return toJson(new PayloadV1(actorId, null, null, null));
  }

  public String forMentionedComment(UUID actorId, UUID commentId) {
    return toJson(new PayloadV1(actorId, null, null, commentId));
  }

  public String forWatchedStatusChanged(UUID actorId, String fromStatus, String toStatus) {
    return toJson(new PayloadV1(actorId, fromStatus, toStatus, null));
  }

  public PayloadV1 fromJson(String json) {
    try {
      return objectMapper.readValue(json, PayloadV1.class);
    } catch (JacksonException e) {
      throw new IllegalStateException("notification payload deserialization failed", e);
    }
  }

  private String toJson(PayloadV1 payload) {
    try {
      return objectMapper.writeValueAsString(payload);
    } catch (JacksonException e) {
      throw new IllegalStateException("notification payload serialization failed", e);
    }
  }
}
