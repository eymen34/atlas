package io.ngss.atlas.notification;

import static io.restassured.RestAssured.given;

import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/** Mark-read endpoints — idempotency, IDOR, mark-all, auth (T-024, AC-6, D7, SEC). */
class NotificationReadIT extends NotificationITBase {

  /** Returns {bobToken, the id of bob's single ASSIGNED notification}. */
  private record Setup(String tokenAlice, String tokenBob, String notificationId) {}

  private Setup oneNotificationForBob() {
    UUID alice = register("alice@example.com", "Alice");
    UUID bob = register("bob@example.com", "Bob");
    String tokenAlice = sign(alice);
    String tokenBob = sign(bob);
    String eng = createProject(tokenAlice, "ENG", "Engineering");
    addMember(tokenAlice, eng, "bob@example.com");
    createTicket(tokenAlice, eng, "{\"title\":\"T\",\"assigneeId\":\"" + bob + "\"}");
    String id =
        listNotifications(tokenBob, "")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getString("items[0].id");
    return new Setup(tokenAlice, tokenBob, id);
  }

  @Test
  void markReadIsIdempotentAndReturns204() {
    Setup s = oneNotificationForBob();

    markRead(s.tokenBob(), s.notificationId()).then().statusCode(204);
    markRead(s.tokenBob(), s.notificationId()).then().statusCode(204); // again → still 204

    listNotifications(s.tokenBob(), "?unread=true")
        .then()
        .statusCode(200)
        .body("total", Matchers.equalTo(0));
  }

  @Test
  void markingAnotherUsersNotificationReturns404_idorSafe() {
    Setup s = oneNotificationForBob();

    // alice tries to mark bob's notification read → uniform 404, and bob's row stays unread.
    markRead(s.tokenAlice(), s.notificationId()).then().statusCode(404);
    listNotifications(s.tokenBob(), "?unread=true")
        .then()
        .statusCode(200)
        .body("total", Matchers.equalTo(1));
  }

  @Test
  void markingAnUnknownIdReturns404() {
    Setup s = oneNotificationForBob();
    markRead(s.tokenBob(), UUID.randomUUID().toString()).then().statusCode(404);
  }

  @Test
  void markAllReadClearsEveryUnreadRow() {
    UUID alice = register("alice@example.com", "Alice");
    UUID bob = register("bob@example.com", "Bob");
    String tokenAlice = sign(alice);
    String tokenBob = sign(bob);
    String eng = createProject(tokenAlice, "ENG", "Engineering");
    addMember(tokenAlice, eng, "bob@example.com");
    String ticket =
        createTicket(tokenAlice, eng, "{\"title\":\"T\",\"assigneeId\":\"" + bob + "\"}");
    postComment(tokenAlice, ticket, "<p>@bob</p>").then().statusCode(201); // bob now has 2

    markAllRead(tokenBob).then().statusCode(204);

    listNotifications(tokenBob, "?unread=true")
        .then()
        .statusCode(200)
        .body("total", Matchers.equalTo(0));
    listNotifications(tokenBob, "")
        .then()
        .statusCode(200)
        .body("total", Matchers.equalTo(2)); // still listed, just read
  }

  @Test
  void allEndpointsRequireAuthentication() {
    given().get("/api/notifications").then().statusCode(401);
    given().post("/api/notifications/" + UUID.randomUUID() + "/read").then().statusCode(401);
    given().post("/api/notifications/read-all").then().statusCode(401);
  }
}
