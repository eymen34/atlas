package io.ngss.atlas.activity;

import io.ngss.atlas.domain.ActivityEvent;
import io.ngss.atlas.domain.ActivityEventType;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes activity-log rows SYNCHRONOUSLY inside the transaction of the originating
 * ticket change (T-019). The payload object is serialized to JSON (Jackson 3,
 * {@code tools.jackson.*}) and stored in the {@code activity_events.payload} text
 * column.
 *
 * <p>{@code @Transactional(propagation = MANDATORY)}: this MUST be called from
 * within an existing transaction (e.g. a {@code TicketService} write method). If
 * there is no active transaction Spring throws {@code IllegalTransactionStateException}
 * — BY DESIGN, so an activity row can never be persisted independently of the
 * change it records, and a rollback of the originating change discards the activity
 * row too. It is deliberately NOT an {@code @EventListener} /
 * {@code @TransactionalEventListener} — those would break that atomicity.
 */
@Component
public class ActivityEventWriter {

  private final ActivityEventRepository repository;
  private final ObjectMapper objectMapper;

  public ActivityEventWriter(ActivityEventRepository repository, ObjectMapper objectMapper) {
    this.repository = repository;
    this.objectMapper = objectMapper;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void record(
      UUID ticketId, UUID actorId, ActivityEventType type, Object payloadObject, Instant at) {
    Objects.requireNonNull(ticketId, "ticketId");
    Objects.requireNonNull(actorId, "actorId");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(payloadObject, "payloadObject");
    Objects.requireNonNull(at, "at");
    String json;
    try {
      json = objectMapper.writeValueAsString(payloadObject);
    } catch (JacksonException e) {
      // A payload type that cannot be serialized is a programming error, not a
      // client error — fail loud rather than persist a corrupt row.
      throw new IllegalStateException("activity payload serialization failed for " + type, e);
    }
    repository.save(new ActivityEvent(UUID.randomUUID(), ticketId, actorId, type, json, at));
  }
}
