package io.ngss.atlas.outbox;

/**
 * Strategy for processing one outbox row of a given {@link OutboxKind} (T-029).
 * Implementations perform the side-effecting I/O (SMTP send, S3 delete) and may throw —
 * the {@link OutboxDrainService} runs {@link #handle} OUTSIDE any transaction and turns a
 * thrown exception into a backed-off retry (or FAILED once the budget is exhausted).
 */
public interface OutboxHandler {

  OutboxKind kind();

  void handle(OutboxRow row) throws Exception;
}
