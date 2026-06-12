package io.ngss.atlas.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** ASSIGNED fan-out (T-024, AC-2, D5, EC-2). */
class NotificationFanoutAssignedIT extends NotificationITBase {

  @Test
  void createWithAssigneeCreatesAssignedNotification() {
    UUID alice = register("alice@example.com", "Alice");
    UUID bob = register("bob@example.com", "Bob");
    String tokenAlice = sign(alice);
    String eng = createProject(tokenAlice, "ENG", "Engineering");
    addMember(tokenAlice, eng, "bob@example.com");

    createTicket(tokenAlice, eng, "{\"title\":\"T\",\"assigneeId\":\"" + bob + "\"}");

    assertThat(countByKindAndUser("ASSIGNED", bob)).isEqualTo(1);
    assertThat(countByKindAndUser("ASSIGNED", alice)).isZero(); // creator/actor not assigned-notified
  }

  @Test
  void selfAssignProducesNoNotification() {
    UUID alice = register("alice@example.com", "Alice");
    String tokenAlice = sign(alice);
    String eng = createProject(tokenAlice, "ENG", "Engineering");
    String ticket = createTicket(tokenAlice, eng, "{\"title\":\"T\"}");

    patch(tokenAlice, ticket, "{\"assigneeId\":\"" + alice + "\"}").then().statusCode(200);

    assertThat(countByKindAndUser("ASSIGNED", alice)).isZero();
  }

  @Test
  void assigneeChangeOnUpdateNotifiesNewAssignee() {
    UUID alice = register("alice@example.com", "Alice");
    UUID bob = register("bob@example.com", "Bob");
    String tokenAlice = sign(alice);
    String eng = createProject(tokenAlice, "ENG", "Engineering");
    addMember(tokenAlice, eng, "bob@example.com");
    String ticket = createTicket(tokenAlice, eng, "{\"title\":\"T\"}");

    patch(tokenAlice, ticket, "{\"assigneeId\":\"" + bob + "\"}").then().statusCode(200);

    assertThat(countByKindAndUser("ASSIGNED", bob)).isEqualTo(1);
  }

  @Test
  void createTicketWithBothDescriptionMentionAndAssigneeProducesBothNotifications() {
    // EC-2: one HTTP create with @mention AND assignee → MENTIONED_TICKET + ASSIGNED.
    UUID alice = register("alice@example.com", "Alice");
    UUID bob = register("bob@example.com", "Bob"); // handle "bob", mentioned
    UUID carol = register("carol@example.com", "Carol"); // assignee
    String tokenAlice = sign(alice);
    String eng = createProject(tokenAlice, "ENG", "Engineering");
    addMember(tokenAlice, eng, "bob@example.com");
    addMember(tokenAlice, eng, "carol@example.com");

    String ticket =
        createTicket(
            tokenAlice,
            eng,
            "{\"title\":\"T\",\"description\":\"<p>cc @bob</p>\",\"assigneeId\":\"" + carol + "\"}");

    assertThat(countByKindAndUser("MENTIONED_TICKET", bob)).isEqualTo(1);
    assertThat(countByKindAndUser("ASSIGNED", carol)).isEqualTo(1);
    assertThat(countByTicket(ticket)).isEqualTo(2);
  }
}
