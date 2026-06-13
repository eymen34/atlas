package io.ngss.atlas.outbox;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Native-SQL implementation of {@link OutboxRepository} (T-029), modelled on
 * {@code ProjectTicketCounterRepositoryImpl}: {@code @PersistenceContext EntityManager} +
 * native queries + manual row mapping. {@code payload} is serialized/parsed with the
 * shared Jackson 3 {@link ObjectMapper}; {@code id} is application-generated.
 */
@Repository
public class OutboxRepositoryImpl implements OutboxRepository {

  private static final String CLAIM_SQL =
      "UPDATE outbox SET status = 'PROCESSING', updated_at = now() "
          + "WHERE id IN ("
          + "  SELECT id FROM outbox "
          + "  WHERE status = 'PENDING' AND next_attempt_at <= now() "
          + "  ORDER BY next_attempt_at "
          + "  LIMIT :max "
          + "  FOR UPDATE SKIP LOCKED"
          + ") "
          + "RETURNING id, kind, status, payload::text, attempt_count, next_attempt_at, "
          + "          last_error, created_at, updated_at, sent_at";

  @PersistenceContext private EntityManager entityManager;

  private final ObjectMapper objectMapper;

  public OutboxRepositoryImpl(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  @Transactional
  public UUID enqueue(OutboxKind kind, JsonNode payload) {
    UUID id = UUID.randomUUID();
    entityManager
        .createNativeQuery(
            "INSERT INTO outbox (id, kind, status, payload) "
                + "VALUES (:id, :kind, 'PENDING', CAST(:payload AS jsonb))")
        .setParameter("id", id)
        .setParameter("kind", kind.name())
        .setParameter("payload", objectMapper.writeValueAsString(payload))
        .executeUpdate();
    return id;
  }

  @Override
  @Transactional
  @SuppressWarnings("unchecked")
  public List<OutboxRow> claimBatch(int max) {
    List<Object[]> rows =
        entityManager.createNativeQuery(CLAIM_SQL).setParameter("max", max).getResultList();
    return rows.stream().map(this::mapRow).toList();
  }

  @Override
  @Transactional
  public void markSent(UUID id, Instant now) {
    entityManager
        .createNativeQuery(
            "UPDATE outbox SET status = 'SENT', sent_at = :now, updated_at = :now WHERE id = :id")
        .setParameter("now", now)
        .setParameter("id", id)
        .executeUpdate();
  }

  @Override
  @Transactional
  public void scheduleRetry(
      UUID id, int newAttemptCount, Instant nextAttemptAt, String lastError, Instant now) {
    entityManager
        .createNativeQuery(
            "UPDATE outbox SET status = 'PENDING', attempt_count = :attempt, "
                + "next_attempt_at = :next, last_error = :err, updated_at = :now WHERE id = :id")
        .setParameter("attempt", newAttemptCount)
        .setParameter("next", nextAttemptAt)
        .setParameter("err", lastError)
        .setParameter("now", now)
        .setParameter("id", id)
        .executeUpdate();
  }

  @Override
  @Transactional
  public void markFailed(UUID id, String lastError, Instant now) {
    entityManager
        .createNativeQuery(
            "UPDATE outbox SET status = 'FAILED', last_error = :err, updated_at = :now "
                + "WHERE id = :id")
        .setParameter("err", lastError)
        .setParameter("now", now)
        .setParameter("id", id)
        .executeUpdate();
  }

  @Override
  @Transactional(readOnly = true)
  public long countByStatus(OutboxStatus status) {
    Object count =
        entityManager
            .createNativeQuery("SELECT count(*) FROM outbox WHERE status = :status")
            .setParameter("status", status.name())
            .getSingleResult();
    return ((Number) count).longValue();
  }

  private OutboxRow mapRow(Object[] r) {
    return new OutboxRow(
        toUuid(r[0]),
        OutboxKind.valueOf((String) r[1]),
        OutboxStatus.valueOf((String) r[2]),
        // payload::text in the RETURNING clause → a JSON string; never null (NOT NULL column).
        objectMapper.readTree(r[3].toString()),
        ((Number) r[4]).intValue(),
        toInstant(r[5]),
        (String) r[6],
        toInstant(r[7]),
        toInstant(r[8]),
        toInstant(r[9]));
  }

  private static UUID toUuid(Object value) {
    if (value instanceof UUID uuid) {
      return uuid;
    }
    return UUID.fromString(value.toString());
  }

  private static Instant toInstant(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Instant instant) {
      return instant;
    }
    if (value instanceof OffsetDateTime odt) {
      return odt.toInstant();
    }
    if (value instanceof Timestamp ts) {
      return ts.toInstant();
    }
    throw new IllegalStateException(
        "unexpected timestamp type from native query: " + value.getClass());
  }
}
