package io.ngss.atlas.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * AC-6: email-enqueue gating, driven over real HTTP (so the AFTER_COMMIT fan-out fires). An
 * assignment to an opted-IN recipient writes the in-app notification AND an EMAIL_NOTIFICATION
 * outbox row in the same REQUIRES_NEW transaction; an opted-OUT recipient gets the notification
 * only. The enqueued subject is built from the real ticket/project (e.g. "[ENG-1] Fix login bug").
 */
class EmailEnqueueIT extends OutboxITBase {

  @Test
  void enabledRecipientGetsNotificationAndEmailOutboxRow() {
    UUID alice = register("alice@example.com", "Alice");
    UUID bob = register("bob@example.com", "Bob");
    String tokenAlice = sign(alice);
    String eng = createProject(tokenAlice, "ENG", "Engineering");
    addMember(tokenAlice, eng, "bob@example.com");
    String ticket = createTicket(tokenAlice, eng, "{\"title\":\"Fix login bug\"}");

    patch(tokenAlice, ticket, "{\"assigneeId\":\"" + bob + "\"}").then().statusCode(200);

    assertThat(notificationCount(bob)).isEqualTo(1);
    assertThat(countOutboxByKind(OutboxKind.EMAIL_NOTIFICATION)).isEqualTo(1L);

    String subject =
        jdbc.queryForObject(
            "SELECT payload->>'subject' FROM outbox WHERE kind = 'EMAIL_NOTIFICATION'",
            String.class);
    assertThat(subject).isEqualTo("[ENG-1] Fix login bug"); // [<projectKey>-<number>] <title>
  }

  @Test
  void disabledRecipientGetsNotificationButNoEmailOutboxRow() {
    UUID alice = register("alice@example.com", "Alice");
    UUID bob = register("bob@example.com", "Bob");
    String tokenAlice = sign(alice);
    String eng = createProject(tokenAlice, "ENG", "Engineering");
    addMember(tokenAlice, eng, "bob@example.com");
    String ticket = createTicket(tokenAlice, eng, "{\"title\":\"Fix login bug\"}");

    jdbc.update(
        "UPDATE users SET email_notifications_enabled = false WHERE id = ?::uuid", bob.toString());

    patch(tokenAlice, ticket, "{\"assigneeId\":\"" + bob + "\"}").then().statusCode(200);

    assertThat(notificationCount(bob)).isEqualTo(1);
    assertThat(countOutboxByKind(OutboxKind.EMAIL_NOTIFICATION)).isZero();
  }

  private int notificationCount(UUID userId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM notifications WHERE user_id = ?::uuid",
        Integer.class,
        userId.toString());
  }
}
