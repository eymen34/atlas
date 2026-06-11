package io.ngss.atlas.notification;

import io.restassured.path.json.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** GET /api/notifications — caller-scoping, enrichment, unread filter, paging (T-024, AC-5, D6). */
class NotificationListIT extends NotificationITBase {

  /** Gives bob two notifications (ASSIGNED then MENTIONED_COMMENT), both with actor=alice. */
  private record Fixture(UUID alice, UUID bob, String tokenAlice, String tokenBob, String ticket) {}

  private Fixture twoNotificationsForBob() {
    UUID alice = register("alice@example.com", "Alice");
    UUID bob = register("bob@example.com", "Bob");
    String tokenAlice = sign(alice);
    String tokenBob = sign(bob);
    String eng = createProject(tokenAlice, "ENG", "Engineering");
    addMember(tokenAlice, eng, "bob@example.com");
    String ticket =
        createTicket(tokenAlice, eng, "{\"title\":\"Login bug\",\"assigneeId\":\"" + bob + "\"}");
    postComment(tokenAlice, ticket, "<p>@bob please look</p>").then().statusCode(201);
    return new Fixture(alice, bob, tokenAlice, tokenBob, ticket);
  }

  @Test
  void listReturnsCallerScopedEnrichedEnvelopeNewestFirst() {
    Fixture f = twoNotificationsForBob();

    JsonPath body =
        listNotifications(f.tokenBob(), "").then().statusCode(200).extract().jsonPath();

    // Envelope shape.
    org.assertj.core.api.Assertions.assertThat(body.getInt("total")).isEqualTo(2);
    org.assertj.core.api.Assertions.assertThat(body.getInt("page")).isZero();
    org.assertj.core.api.Assertions.assertThat(body.getInt("size")).isEqualTo(20);
    org.assertj.core.api.Assertions.assertThat(body.getList("items")).hasSize(2);

    // Newest first: the comment mention was created after the assignment.
    org.assertj.core.api.Assertions.assertThat(body.getString("items[0].kind"))
        .isEqualTo("MENTIONED_COMMENT");
    org.assertj.core.api.Assertions.assertThat(body.getString("items[1].kind"))
        .isEqualTo("ASSIGNED");

    // Enrichment on the newest row.
    org.assertj.core.api.Assertions.assertThat(body.getString("items[0].ticketKey"))
        .isEqualTo("ENG-1");
    org.assertj.core.api.Assertions.assertThat(body.getString("items[0].projectKey"))
        .isEqualTo("ENG");
    org.assertj.core.api.Assertions.assertThat(body.getString("items[0].ticketTitle"))
        .isEqualTo("Login bug");
    org.assertj.core.api.Assertions.assertThat(body.getString("items[0].actorDisplayName"))
        .isEqualTo("Alice");
    org.assertj.core.api.Assertions.assertThat(body.getBoolean("items[0].read")).isFalse();
  }

  @Test
  void callerOnlySeesOwnNotifications() {
    Fixture f = twoNotificationsForBob();

    // alice is the actor on both → she has none of her own.
    listNotifications(f.tokenAlice(), "").then().statusCode(200).body("total", org.hamcrest.Matchers.equalTo(0));
    listNotifications(f.tokenBob(), "").then().statusCode(200).body("total", org.hamcrest.Matchers.equalTo(2));
  }

  @Test
  void unreadFilterExcludesRowsAlreadyMarkedRead() {
    Fixture f = twoNotificationsForBob();

    String firstId =
        listNotifications(f.tokenBob(), "")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getString("items[0].id");
    markRead(f.tokenBob(), firstId).then().statusCode(204);

    listNotifications(f.tokenBob(), "?unread=true")
        .then()
        .statusCode(200)
        .body("total", org.hamcrest.Matchers.equalTo(1));
    listNotifications(f.tokenBob(), "")
        .then()
        .statusCode(200)
        .body("total", org.hamcrest.Matchers.equalTo(2));
  }
}
