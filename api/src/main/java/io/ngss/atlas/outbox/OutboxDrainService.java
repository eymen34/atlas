package io.ngss.atlas.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drains the outbox (T-029). {@link #drain} claims a batch (one short transaction) and then,
 * for each row, runs the handler's I/O OUTSIDE any transaction and writes the single resulting
 * status in its OWN {@code REQUIRES_NEW} transaction.
 *
 * <p>This split is deliberate (jpa_rollback_only_trap): running the handler inside the
 * status-write transaction would mean a status-write failure poisons a transaction that also
 * did the side effect, and a long SMTP/S3 call would hold a DB transaction open. So the handler
 * runs transaction-free, and {@link #writeSent}/{@link #writeRetry}/{@link #writeFailed} each
 * open a fresh transaction containing ONLY one UPDATE. Every status write is wrapped
 * exception-safe ({@code status_write_is_not_an_exception}): a write failure is logged and the
 * loop continues — it never propagates out of {@link #drain}.
 *
 * <p>Backoff uses the attempt count BEFORE this attempt: {@code delay = min(60·2^before, 3600)}
 * (before=0 → 60s, before=1 → 120s, …, capped at 1h). A row that has already been attempted
 * {@link #MAX_ATTEMPTS} times (i.e. {@code before + 1 >= 10}) goes to FAILED instead of retry.
 */
@Service
public class OutboxDrainService {

  private static final Logger log = LoggerFactory.getLogger(OutboxDrainService.class);

  static final int MAX_ATTEMPTS = 10;
  static final long BASE_DELAY_SECONDS = 60;
  static final long MAX_DELAY_SECONDS = 3600;
  private static final int MAX_ERROR_LENGTH = 1000;

  private final OutboxRepository repository;
  private final OutboxDispatcher dispatcher;
  private final OutboxDrainService self;

  /**
   * {@code self} is this same bean, injected {@code @Lazy} so calls to the
   * {@code @Transactional(REQUIRES_NEW)} status-write methods go through the Spring proxy and
   * actually open a new transaction (an in-class {@code this.writeSent(...)} would bypass the
   * proxy and run in no transaction).
   */
  public OutboxDrainService(
      OutboxRepository repository,
      OutboxDispatcher dispatcher,
      @Lazy OutboxDrainService self) {
    this.repository = repository;
    this.dispatcher = dispatcher;
    this.self = self;
  }

  /** Claims up to {@code batchSize} due rows and processes each; returns the outcome tally. */
  public DrainResult drain(int batchSize) {
    List<OutboxRow> claimed = repository.claimBatch(batchSize);
    int succeeded = 0;
    int failed = 0;
    int retried = 0;
    for (OutboxRow row : claimed) {
      switch (process(row)) {
        case SUCCEEDED -> succeeded++;
        case FAILED -> failed++;
        case RETRIED -> retried++;
      }
    }
    return new DrainResult(claimed.size(), succeeded, failed, retried);
  }

  enum Outcome {
    SUCCEEDED,
    FAILED,
    RETRIED
  }

  /**
   * Runs one row's handler (transaction-free) and records the outcome. Package-private so the
   * unit test can drive it directly. Never throws — a handler failure becomes RETRY/FAILED and a
   * status-write failure is swallowed (logged).
   */
  Outcome process(OutboxRow row) {
    Instant now = Instant.now();
    Exception handlerError = null;
    try {
      dispatcher.handlerFor(row.kind()).handle(row);
    } catch (Exception e) {
      handlerError = e;
    }

    if (handlerError == null) {
      safeStatusWrite(row.id(), () -> self.writeSent(row.id(), now));
      return Outcome.SUCCEEDED;
    }

    int attemptBefore = row.attemptCount();
    String error = summarize(handlerError);
    if (attemptBefore + 1 >= MAX_ATTEMPTS) {
      log.error("outbox row {} ({}) exhausted retries — marking FAILED: {}", row.id(), row.kind(), error);
      safeStatusWrite(row.id(), () -> self.writeFailed(row.id(), error, now));
      return Outcome.FAILED;
    }
    Instant nextAttemptAt = now.plusSeconds(backoffSeconds(attemptBefore));
    log.warn(
        "outbox row {} ({}) failed attempt {} — retrying at {}: {}",
        row.id(),
        row.kind(),
        attemptBefore + 1,
        nextAttemptAt,
        error);
    safeStatusWrite(
        row.id(), () -> self.writeRetry(row.id(), attemptBefore + 1, nextAttemptAt, error, now));
    return Outcome.RETRIED;
  }

  private void safeStatusWrite(UUID id, Runnable write) {
    try {
      write.run();
    } catch (RuntimeException e) {
      // A status-write failure must never fail the drain or the side effect already performed.
      log.error("outbox status write failed for row {} (left for re-claim/manual review)", id, e);
    }
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void writeSent(UUID id, Instant now) {
    repository.markSent(id, now);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void writeRetry(
      UUID id, int newAttemptCount, Instant nextAttemptAt, String error, Instant now) {
    repository.scheduleRetry(id, newAttemptCount, nextAttemptAt, error, now);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void writeFailed(UUID id, String error, Instant now) {
    repository.markFailed(id, error, now);
  }

  /** Exponential backoff in seconds keyed on the attempt count BEFORE this attempt (capped 1h). */
  static long backoffSeconds(int attemptCountBefore) {
    int safeExponent = Math.min(Math.max(attemptCountBefore, 0), 30);
    long factor = 1L << safeExponent;
    return Math.min(BASE_DELAY_SECONDS * factor, MAX_DELAY_SECONDS);
  }

  private static String summarize(Throwable e) {
    String message = e.getMessage() == null ? "" : e.getMessage();
    String summary = e.getClass().getSimpleName() + ": " + message;
    return summary.length() > MAX_ERROR_LENGTH ? summary.substring(0, MAX_ERROR_LENGTH) : summary;
  }
}
