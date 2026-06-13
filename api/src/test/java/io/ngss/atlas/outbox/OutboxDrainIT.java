package io.ngss.atlas.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * AC-3: full EMAIL_NOTIFICATION drain lifecycle. A PENDING row → SENT; JavaMailSender.send()
 * called once with the verbatim subject; the response is {processed:1,succeeded:1,failed:0,retried:0}.
 */
class OutboxDrainIT extends OutboxITBase {

  @MockitoBean JavaMailSender mailSender;

  @Test
  void emailNotificationDrainsToSentAndSendsOnce() {
    Instant testStart = Instant.now();
    UUID id =
        enqueueEmail(
            "recipient@example.com",
            "[PROJ-42] Fix login bug",
            "Alice assigned this ticket to you\nhttp://localhost:8080/projects/PROJ/tickets/PROJ-42");

    drainOutbox(DRAIN_SECRET)
        .then()
        .statusCode(200)
        .body("processed", equalTo(1))
        .body("succeeded", equalTo(1))
        .body("failed", equalTo(0))
        .body("retried", equalTo(0));

    ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(mailSender).send(captor.capture());
    SimpleMailMessage sent = captor.getValue();
    assertThat(sent.getSubject()).isEqualTo("[PROJ-42] Fix login bug"); // verbatim from payload
    assertThat(sent.getTo()).containsExactly("recipient@example.com");

    assertThat(outboxStatus(id)).isEqualTo("SENT");
    Instant sentAt =
        jdbc.queryForObject(
            "SELECT sent_at FROM outbox WHERE id = ?::uuid", Instant.class, id.toString());
    assertThat(sentAt).isAfter(testStart);
  }
}
