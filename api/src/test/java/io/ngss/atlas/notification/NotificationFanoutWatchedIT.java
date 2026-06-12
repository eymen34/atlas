package io.ngss.atlas.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** WATCHED_STATUS_CHANGED fan-out (T-024, AC-4). */
class NotificationFanoutWatchedIT extends NotificationITBase {

  @Test
  void statusChangeNotifiesAllWatchersExceptActor_andStillWritesActivity() {
    UUID alice = register("alice@example.com", "Alice");
    UUID bob = register("bob@example.com", "Bob");
    String tokenAlice = sign(alice);
    String tokenBob = sign(bob);
    String eng = createProject(tokenAlice, "ENG", "Engineering");
    addMember(tokenAlice, eng, "bob@example.com");
    String ticket = createTicket(tokenAlice, eng, "{\"title\":\"T\"}"); // alice auto-watches

    putWatch(tokenBob, ticket); // bob now watches too

    transition(tokenAlice, ticket, "IN_PROGRESS").then().statusCode(200);

    assertThat(countByKindAndUser("WATCHED_STATUS_CHANGED", bob)).isEqualTo(1);
    assertThat(countByKindAndUser("WATCHED_STATUS_CHANGED", alice)).isZero(); // actor excluded

    Integer statusActivity =
        jdbc.queryForObject(
            "SELECT count(*) FROM activity_events "
                + "WHERE event_type='STATUS_CHANGED' AND ticket_id=?::uuid",
            Integer.class,
            ticket);
    assertThat(statusActivity).isEqualTo(1); // core write unaffected by fan-out
  }

  @Test
  void actorWhoIsAlsoTheOnlyWatcherGetsNoNotification() {
    UUID alice = register("alice@example.com", "Alice");
    String tokenAlice = sign(alice);
    String eng = createProject(tokenAlice, "ENG", "Engineering");
    String ticket = createTicket(tokenAlice, eng, "{\"title\":\"T\"}"); // alice auto-watches

    transition(tokenAlice, ticket, "IN_PROGRESS").then().statusCode(200);

    assertThat(countByKindAndUser("WATCHED_STATUS_CHANGED", alice)).isZero();
    assertThat(countByTicket(ticket)).isZero();
  }
}
