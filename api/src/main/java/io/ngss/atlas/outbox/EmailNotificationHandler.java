package io.ngss.atlas.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Sends a notification email for an {@link OutboxKind#EMAIL_NOTIFICATION} row (T-029).
 * Plaintext {@link SimpleMailMessage} — no Thymeleaf. Reads the {@code subject}/{@code body}
 * VERBATIM from the {@link EmailPayload} (they were composed at enqueue time); a send failure
 * propagates so the drain backs off and retries.
 */
@Component
public class EmailNotificationHandler implements OutboxHandler {

  private final JavaMailSender mailSender;
  private final ObjectMapper objectMapper;
  private final String from;

  public EmailNotificationHandler(
      JavaMailSender mailSender,
      ObjectMapper objectMapper,
      @Value("${app.mail.from:noreply@atlas.local}") String from) {
    this.mailSender = mailSender;
    this.objectMapper = objectMapper;
    this.from = from;
  }

  @Override
  public OutboxKind kind() {
    return OutboxKind.EMAIL_NOTIFICATION;
  }

  @Override
  public void handle(OutboxRow row) throws Exception {
    EmailPayload payload = objectMapper.treeToValue(row.payload(), EmailPayload.class);
    SimpleMailMessage message = new SimpleMailMessage();
    message.setFrom(from);
    message.setTo(payload.toEmail());
    message.setSubject(payload.subject());
    message.setText(payload.body());
    mailSender.send(message);
  }
}
