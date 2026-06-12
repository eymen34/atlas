package io.ngss.atlas.notification;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.http.ContentType;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** MENTIONED_COMMENT / MENTIONED_TICKET fan-out (T-024, AC-3, EC-1). */
class NotificationFanoutMentionsIT extends NotificationITBase {

  private UUID alice;
  private UUID bob;
  private UUID carol;
  private String tokenAlice;
  private String eng;

  private void seed() {
    alice = register("alice@example.com", "Alice");
    bob = register("bob@example.com", "Bob"); // handle "bob"
    carol = register("carol@example.com", "Carol"); // handle "carol"
    tokenAlice = sign(alice);
    eng = createProject(tokenAlice, "ENG", "Engineering");
    addMember(tokenAlice, eng, "bob@example.com");
    addMember(tokenAlice, eng, "carol@example.com");
  }

  private String editComment(String token, String commentId, String body) {
    return given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body("{\"body\":\"" + body + "\"}")
        .patch("/api/comments/" + commentId)
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getString("id");
  }

  @Test
  void commentMentionNotifiesMember_andSkipsSelfMention() {
    seed();
    String ticket = createTicket(tokenAlice, eng, "{\"title\":\"T\"}");

    postComment(tokenAlice, ticket, "<p>hey @bob and @alice</p>").then().statusCode(201);

    assertThat(countByKindAndUser("MENTIONED_COMMENT", bob)).isEqualTo(1);
    assertThat(countByKindAndUser("MENTIONED_COMMENT", alice)).isZero(); // self-mention skipped
  }

  @Test
  void ticketDescriptionMentionOnCreateNotifies() {
    seed();
    createTicket(tokenAlice, eng, "{\"title\":\"T\",\"description\":\"<p>cc @bob</p>\"}");
    assertThat(countByKindAndUser("MENTIONED_TICKET", bob)).isEqualTo(1);
  }

  @Test
  void descriptionEditAddsNewMention_notifiesNewUser_withNullSourceEventAndNoDescriptionActivity() {
    // EC-1 + CORRECTION-A.
    seed();
    String ticket =
        createTicket(tokenAlice, eng, "{\"title\":\"T\",\"description\":\"<p>cc @bob</p>\"}");

    patch(tokenAlice, ticket, "{\"description\":\"<p>now @carol</p>\"}").then().statusCode(200);

    assertThat(countByKindAndUser("MENTIONED_TICKET", carol)).isEqualTo(1);

    UUID sourceEventId =
        jdbc.queryForObject(
            "SELECT source_event_id FROM notifications "
                + "WHERE kind='MENTIONED_TICKET' AND user_id=?::uuid",
            UUID.class,
            carol.toString());
    assertThat(sourceEventId).isNull();

    Integer descriptionActivity =
        jdbc.queryForObject(
            "SELECT count(*) FROM activity_events WHERE event_type='DESCRIPTION_CHANGED'",
            Integer.class);
    assertThat(descriptionActivity).isZero(); // there is no DESCRIPTION_CHANGED type
  }

  @Test
  void editCommentSameHandle_producesNoNewNotification() {
    seed();
    String ticket = createTicket(tokenAlice, eng, "{\"title\":\"T\"}");
    String commentId =
        postComment(tokenAlice, ticket, "<p>@bob</p>")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("id");
    assertThat(countByKindAndUser("MENTIONED_COMMENT", bob)).isEqualTo(1);

    editComment(tokenAlice, commentId, "<p>@bob still here</p>"); // same mention → diff empty

    assertThat(countByKindAndUser("MENTIONED_COMMENT", bob)).isEqualTo(1);
  }

  @Test
  void editCommentNewHandle_notifiesOnlyTheNewlyAddedUser() {
    seed();
    String ticket = createTicket(tokenAlice, eng, "{\"title\":\"T\"}");
    String commentId =
        postComment(tokenAlice, ticket, "<p>@bob</p>")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("id");

    editComment(tokenAlice, commentId, "<p>@bob @carol</p>"); // carol newly added

    assertThat(countByKindAndUser("MENTIONED_COMMENT", carol)).isEqualTo(1);
    assertThat(countByKindAndUser("MENTIONED_COMMENT", bob)).isEqualTo(1); // unchanged
  }
}
