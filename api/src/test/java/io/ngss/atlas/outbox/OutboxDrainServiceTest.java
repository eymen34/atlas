package io.ngss.atlas.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.node.NullNode;

/**
 * Unit tests for {@link OutboxDrainService} (Docker-free, pure Mockito). Pins the backoff
 * formula, the {@code processed = succeeded + failed + retried} invariant, and the
 * status-write exception-safety (EC-5).
 */
@ExtendWith(MockitoExtension.class)
class OutboxDrainServiceTest {

  @Mock OutboxRepository repository;
  @Mock OutboxDispatcher dispatcher;
  @Mock OutboxDrainService self;
  @Mock OutboxHandler handler;

  private OutboxDrainService service;

  @BeforeEach
  void init() {
    service = new OutboxDrainService(repository, dispatcher, self);
  }

  private static OutboxRow row(int attemptCount) {
    Instant now = Instant.parse("2026-06-13T00:00:00Z");
    return new OutboxRow(
        UUID.randomUUID(),
        OutboxKind.EMAIL_NOTIFICATION,
        OutboxStatus.PROCESSING,
        NullNode.getInstance(),
        attemptCount,
        now,
        null,
        now,
        now,
        null);
  }

  @Test
  void backoffDoublesPerPriorAttemptAndCapsAtOneHour() {
    assertThat(OutboxDrainService.backoffSeconds(0)).isEqualTo(60);
    assertThat(OutboxDrainService.backoffSeconds(1)).isEqualTo(120);
    assertThat(OutboxDrainService.backoffSeconds(2)).isEqualTo(240);
    assertThat(OutboxDrainService.backoffSeconds(7)).isEqualTo(3600); // 60*128=7680 → capped
    assertThat(OutboxDrainService.backoffSeconds(10)).isEqualTo(3600); // capped
  }

  @Test
  void drainResultHonoursProcessedEqualsSucceededPlusFailedPlusRetried() throws Exception {
    OutboxRow s1 = row(0);
    OutboxRow s2 = row(0);
    OutboxRow s3 = row(0);
    OutboxRow r1 = row(0); // attemptBefore 0 → retry
    OutboxRow r2 = row(1); // attemptBefore 1 → retry
    OutboxRow f1 = row(9); // attemptBefore 9 → 9+1>=10 → FAILED
    when(repository.claimBatch(anyInt())).thenReturn(List.of(s1, s2, s3, r1, r2, f1));
    when(dispatcher.handlerFor(OutboxKind.EMAIL_NOTIFICATION)).thenReturn(handler);
    doNothing().when(handler).handle(s1);
    doNothing().when(handler).handle(s2);
    doNothing().when(handler).handle(s3);
    doThrow(new RuntimeException("boom")).when(handler).handle(r1);
    doThrow(new RuntimeException("boom")).when(handler).handle(r2);
    doThrow(new RuntimeException("boom")).when(handler).handle(f1);

    DrainResult result = service.drain(50);

    assertThat(result.processed()).isEqualTo(6);
    assertThat(result.succeeded()).isEqualTo(3);
    assertThat(result.retried()).isEqualTo(2);
    assertThat(result.failed()).isEqualTo(1);
    assertThat(result.processed())
        .isEqualTo(result.succeeded() + result.failed() + result.retried());
  }

  @Test
  void statusWriteFailureDoesNotPropagateOutOfProcess() throws Exception {
    OutboxRow row = row(0);
    when(dispatcher.handlerFor(OutboxKind.EMAIL_NOTIFICATION)).thenReturn(handler);
    doNothing().when(handler).handle(row); // handler SUCCEEDS
    // …but the SENT status write blows up — must be swallowed (status_write_is_not_an_exception).
    doThrow(new RuntimeException("db down")).when(self).writeSent(eq(row.id()), any());

    assertThatCode(() -> service.process(row)).doesNotThrowAnyException();
  }
}
