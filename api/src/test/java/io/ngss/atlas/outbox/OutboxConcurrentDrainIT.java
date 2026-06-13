package io.ngss.atlas.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.restassured.response.Response;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * AC-5: {@code FOR UPDATE SKIP LOCKED} disjointness. Two concurrent drains over 20 PENDING rows
 * partition the work — the union is all 20 and no row is processed twice (exactly 20 sends, all
 * SENT, nothing left live).
 */
class OutboxConcurrentDrainIT extends OutboxITBase {

  @MockitoBean JavaMailSender mailSender;

  @Test
  void twoConcurrentDrainsPartitionTheWorkWithNoDoubleProcessing() throws Exception {
    for (int i = 0; i < 20; i++) {
      enqueueEmail("r" + i + "@example.com", "[P-1] t" + i, "b");
    }

    CountDownLatch startGate = new CountDownLatch(1);
    Callable<Response> drainTask =
        () -> {
          startGate.await();
          return drainOutbox(DRAIN_SECRET);
        };
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Response> f1 = pool.submit(drainTask);
      Future<Response> f2 = pool.submit(drainTask);
      startGate.countDown();

      int processed1 = f1.get().jsonPath().getInt("processed");
      int processed2 = f2.get().jsonPath().getInt("processed");

      // Disjoint subsets whose union is the full pool.
      assertThat(processed1 + processed2).isEqualTo(20);
    } finally {
      pool.shutdown();
    }

    // No row processed twice: exactly 20 sends across both drains, all rows SENT, none live.
    verify(mailSender, times(20)).send(any(SimpleMailMessage.class));
    Long sent = jdbc.queryForObject("SELECT count(*) FROM outbox WHERE status = 'SENT'", Long.class);
    assertThat(sent).isEqualTo(20L);
    Long live =
        jdbc.queryForObject(
            "SELECT count(*) FROM outbox WHERE status IN ('PENDING','PROCESSING')", Long.class);
    assertThat(live).isZero();
  }
}
