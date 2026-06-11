package io.ngss.atlas.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Dedup window behaviour (T-024, D2: (user,kind,ticket) within 60s; EC-3 cross-kind). */
class NotificationDedupIT extends NotificationITBase {

  @Test
  void twoSameKindTriggersWithinWindowCollapseToOneRow() {
    UUID alice = register("alice@example.com", "Alice");
    UUID bob = register("bob@example.com", "Bob");
    String tokenAlice = sign(alice);
    String tokenBob = sign(bob);
    String eng = createProject(tokenAlice, "ENG", "Engineering");
    addMember(tokenAlice, eng, "bob@example.com");
    String ticket = createTicket(tokenAlice, eng, "{\"title\":\"T\"}");
    putWatch(tokenBob, ticket);

    transition(tokenAlice, ticket, "IN_PROGRESS").then().statusCode(200);
    transition(tokenAlice, ticket, "IN_REVIEW").then().statusCode(200);

    // Both fire WATCHED for bob within the 60s window → deduped to one.
    assertThat(countByKindAndUser("WATCHED_STATUS_CHANGED", bob)).isEqualTo(1);
  }

  @Test
  void triggerOutsideTheWindowOfAnExistingRowInsertsASecondRow() {
    UUID alice = register("alice@example.com", "Alice");
    UUID bob = register("bob@example.com", "Bob");
    String tokenAlice = sign(alice);
    String tokenBob = sign(bob);
    String eng = createProject(tokenAlice, "ENG", "Engineering");
    addMember(tokenAlice, eng, "bob@example.com");
    String ticket = createTicket(tokenAlice, eng, "{\"title\":\"T\"}");
    putWatch(tokenBob, ticket);

    // Synthetic pre-existing row created 61s ago — just OUTSIDE the [now-60s, now] window.
    jdbc.update(
        "INSERT INTO notifications "
            + "(id, user_id, kind, ticket_id, source_event_id, payload, read_at, created_at) "
            + "VALUES (?::uuid, ?::uuid, 'WATCHED_STATUS_CHANGED', ?::uuid, NULL, '{}', NULL, "
            + "now() - interval '61 seconds')",
        UUID.randomUUID().toString(),
        bob.toString(),
        ticket);

    transition(tokenAlice, ticket, "IN_PROGRESS").then().statusCode(200);

    assertThat(countByKindAndUser("WATCHED_STATUS_CHANGED", bob)).isEqualTo(2);
  }

  @Test
  void differentKindsForSameUserAndTicketAreNotDeduped() {
    // EC-3: assignee + description-mention in one create → ASSIGNED and MENTIONED_TICKET coexist.
    UUID alice = register("alice@example.com", "Alice");
    UUID bob = register("bob@example.com", "Bob"); // handle "bob"
    String tokenAlice = sign(alice);
    String eng = createProject(tokenAlice, "ENG", "Engineering");
    addMember(tokenAlice, eng, "bob@example.com");

    String ticket =
        createTicket(
            tokenAlice,
            eng,
            "{\"title\":\"T\",\"description\":\"<p>cc @bob</p>\",\"assigneeId\":\"" + bob + "\"}");

    assertThat(countByKindAndUser("ASSIGNED", bob)).isEqualTo(1);
    assertThat(countByKindAndUser("MENTIONED_TICKET", bob)).isEqualTo(1);
    assertThat(countByTicket(ticket)).isEqualTo(2);
  }
}
