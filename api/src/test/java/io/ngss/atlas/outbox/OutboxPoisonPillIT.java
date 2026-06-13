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
 * AC-4: a handler that always throws increments {@code attempt_count}, backs off
 * (isAfter/isBefore — never exact equality), goes FAILED on the 10th attempt, and is never
 * re-picked after FAILED. The poison is a {@code @MockitoBean JavaMailSender} whose send()
 * throws (preferred over mocking the handler bean, which would break dispatcher construction).
 */
class OutboxPoisonPillIT extends OutboxITBase {

  @MockitoBean JavaMailSender mailSender;

  @BeforeEach
  void makeSendFail() {
    doThrow(new RuntimeException("poison")).when(mailSender).send(any(SimpleMailMessage.class));
  }

  @Test
  void firstFailureIncrementsAttemptCountAndBacksOffAboutSixtySeconds() {
    UUID id = enqueueEmail("r@example.com", "[P-1] t", "b"); // attempt 0, due now

    Instant before = Instant.now();
    drainOutbox(DRAIN_SECRET)
        .then()
        .statusCode(200)
        .body("processed", equalTo(1))
        .body("retried", equalTo(1))
        .body("failed", equalTo(0));

    assertThat(outboxAttemptCount(id)).isEqualTo(1);
    assertThat(outboxStatus(id)).isEqualTo("PENDING");
    Instant next = outboxNextAttemptAt(id);
    assertThat(next).isAfter(before.plusSeconds(55)).isBefore(before.plusSeconds(65));
  }

  @Test
  void tenthAttemptMarksFailedAndIsNeverPickedAgain() {
    UUID id = enqueueEmail("r@example.com", "[P-1] t", "b");
    ageDueWithAttempts(id, 9); // attemptBefore=9 → 9+1 >= 10 → FAILED

    drainOutbox(DRAIN_SECRET).then().statusCode(200).body("failed", equalTo(1));

    assertThat(outboxStatus(id)).isEqualTo("FAILED");
    assertThat(outboxLastError(id)).isNotNull();
    assertThat(outboxLastError(id).length()).isLessThanOrEqualTo(1000);

    // A FAILED row is not PENDING, so a subsequent drain claims nothing.
    drainOutbox(DRAIN_SECRET).then().statusCode(200).body("processed", equalTo(0));
  }
}
