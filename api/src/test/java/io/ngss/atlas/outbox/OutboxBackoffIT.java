package io.ngss.atlas.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * EC-8: backoff caps at one hour. A row already attempted 7 times (60·2^8 = 15360s nominal) is
 * rescheduled ~3600s out, not the uncapped value.
 */
class OutboxBackoffIT extends OutboxITBase {

  @MockitoBean JavaMailSender mailSender;

  @BeforeEach
  void makeSendFail() {
    doThrow(new RuntimeException("still failing")).when(mailSender).send(any(SimpleMailMessage.class));
  }

  @Test
  void backoffIsCappedAtOneHour() {
    UUID id = enqueueEmail("r@example.com", "[P-1] t", "b");
    ageDueWithAttempts(id, 7); // attemptBefore=7 → backoff capped at 3600s

    Instant before = Instant.now();
    drainOutbox(DRAIN_SECRET).then().statusCode(200).body("retried", equalTo(1));

    assertThat(outboxAttemptCount(id)).isEqualTo(8);
    Instant next = outboxNextAttemptAt(id);
    assertThat(next).isAfter(before.plusSeconds(3595)).isBefore(before.plusSeconds(3605));
  }
}
