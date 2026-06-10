package io.ngss.atlas.comment;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.ngss.atlas.Application;
import io.ngss.atlas.BaseIT;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end comment coverage (T-022): create with @mention resolution (member,
 * non-member, bare email, trailing dot), newest-first paged list with
 * server-redacted soft-deletes, author/admin edit + delete authorization, the
 * COMMENT_ADDED/EDITED/DELETED activity rows (asserted via JDBC), and the
 * non-member 404 / non-author-non-admin 403 split.
 */
@SpringBootTest(
    classes = Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {"BCRYPT_COST=12", "spring.jpa.hibernate.ddl-auto=validate"})
class CommentControllerIT {

  private static final String SECRET = "commentit-secret-min-32-characters-long-okay!";

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"))
          .withDatabaseName("atlas")
          .withUsername("atlas")
          .withPassword("atlas");

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("app.database.url", POSTGRES::getJdbcUrl);
    registry.add("app.database.username", POSTGRES::getUsername);
    registry.add("app.database.password", POSTGRES::getPassword);
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("JWT_SECRET", () -> SECRET);
  }

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;

  private UUID alice; // creator → ADMIN, handle "alice"
  private UUID bob; // added as MEMBER, handle "bob"
  private UUID carol; // registered but NOT a member, handle "carol"
  private String tokenAlice;
  private String tokenBob;
  private String tokenCarol;
  private String engId;
  private String ticketId;

  @BeforeEach
  void setUp() {
    RestAssured.baseURI = "http://localhost";
    RestAssured.port = port;
    BaseIT.cleanDatabase(jdbc);

    alice = register("alice@example.com", "Alice");
    bob = register("bob@example.com", "Bob");
    carol = register("carol@example.com", "Carol");
    tokenAlice = sign(alice);
    tokenBob = sign(bob);
    tokenCarol = sign(carol);
    engId = createProject(tokenAlice, "ENG", "Engineering");
    addMember(engId, "bob@example.com", "MEMBER");
    ticketId = createTicket(tokenAlice, engId, "Ticket one");
  }

  @AfterEach
  void reset() {
    RestAssured.reset();
  }

  // ───────────────────────── create + mentions ─────────────────────────

  @Test
  void create_resolvesMemberMention_persistsRow_andWritesCommentAddedActivity() {
    Response created = postComment(tokenAlice, ticketId, "<p>hey @bob look</p>");
    String commentId =
        created
            .then()
            .statusCode(201)
            .body("id", notNullValue())
            .body("deleted", equalTo(false))
            .body("mentionedUserIds", hasItem(bob.toString()))
            .extract()
            .jsonPath()
            .getString("id");

    Integer rows =
        jdbc.queryForObject(
            "SELECT count(*) FROM comment_mentions WHERE comment_id=?::uuid AND user_id=?::uuid",
            Integer.class,
            commentId,
            bob.toString());
    assertThat(rows).isEqualTo(1);

    Integer activity =
        jdbc.queryForObject(
            "SELECT count(*) FROM activity_events WHERE ticket_id=?::uuid AND event_type='COMMENT_ADDED'",
            Integer.class,
            ticketId);
    assertThat(activity).isEqualTo(1);
  }

  @Test
  void create_trailingDotMention_stillResolves() {
    postComment(tokenAlice, ticketId, "<p>thanks @bob.</p>")
        .then()
        .statusCode(201)
        .body("mentionedUserIds", hasItem(bob.toString()));
  }

  @Test
  void create_nonMemberAndBareEmail_produceNoMentionRows() {
    String commentId =
        postComment(tokenAlice, ticketId, "<p>@carol email foo@bar.com @ghost</p>")
            .then()
            .statusCode(201)
            .body("mentionedUserIds", not(hasItem(carol.toString())))
            .extract()
            .jsonPath()
            .getString("id");

    Integer rows =
        jdbc.queryForObject(
            "SELECT count(*) FROM comment_mentions WHERE comment_id=?::uuid",
            Integer.class,
            commentId);
    assertThat(rows).isZero();
  }

  @Test
  void create_byNonMember_returns404() {
    postComment(tokenCarol, ticketId, "<p>sneaky</p>").then().statusCode(404);
  }

  @Test
  void create_blankBody_returns400() {
    postComment(tokenAlice, ticketId, "   ").then().statusCode(400);
  }

  // ───────────────────────── list ─────────────────────────

  @Test
  void list_isNewestFirst_redactsSoftDeleted_clampsSize_and404sNonMembers()
      throws InterruptedException {
    String first = createComment(tokenAlice, ticketId, "<p>first</p>");
    Thread.sleep(10);
    createComment(tokenAlice, ticketId, "<p>second</p>");
    Thread.sleep(10);
    createComment(tokenBob, ticketId, "<p>third</p>");

    // Newest-first.
    given()
        .header("Authorization", "Bearer " + tokenAlice)
        .get("/api/tickets/" + ticketId + "/comments")
        .then()
        .statusCode(200)
        .body("total", equalTo(3))
        .body("items[0].body", equalTo("<p>third</p>"))
        .body("items[2].body", equalTo("<p>first</p>"));

    // Soft-delete the first → still present, redacted.
    given().header("Authorization", "Bearer " + tokenAlice).delete("/api/comments/" + first).then().statusCode(204);
    given()
        .header("Authorization", "Bearer " + tokenAlice)
        .get("/api/tickets/" + ticketId + "/comments")
        .then()
        .statusCode(200)
        .body("total", equalTo(3))
        .body("items.find { it.deleted == true }.body", nullValue());

    // Size is clamped to >= 1.
    given()
        .header("Authorization", "Bearer " + tokenAlice)
        .get("/api/tickets/" + ticketId + "/comments?size=0")
        .then()
        .statusCode(200)
        .body("size", equalTo(1));

    // Non-member → 404.
    given()
        .header("Authorization", "Bearer " + tokenCarol)
        .get("/api/tickets/" + ticketId + "/comments")
        .then()
        .statusCode(404);
  }

  // ───────────────────────── update ─────────────────────────

  @Test
  void update_byAuthor_rederivesMentions_andWritesCommentEdited() {
    String id = createComment(tokenAlice, ticketId, "<p>plain</p>");
    given()
        .header("Authorization", "Bearer " + tokenAlice)
        .contentType(ContentType.JSON)
        .body("{\"body\":\"<p>now @bob</p>\"}")
        .patch("/api/comments/" + id)
        .then()
        .statusCode(200)
        .body("mentionedUserIds", hasItem(bob.toString()));

    Integer edited =
        jdbc.queryForObject(
            "SELECT count(*) FROM activity_events WHERE ticket_id=?::uuid AND event_type='COMMENT_EDITED'",
            Integer.class,
            ticketId);
    assertThat(edited).isEqualTo(1);
  }

  @Test
  void update_byNonAuthorNonAdmin_returns403_andBodyUnchanged() {
    String id = createComment(tokenBob, ticketId, "<p>bobs comment</p>");
    // Carol is a stranger → 404 (cannot even see it); make a member-non-admin case
    // with a second member. Here: alice is ADMIN so she CAN edit; assert the 403
    // path with bob editing alice's comment instead.
    String aliceComment = createComment(tokenAlice, ticketId, "<p>alice comment</p>");
    given()
        .header("Authorization", "Bearer " + tokenBob)
        .contentType(ContentType.JSON)
        .body("{\"body\":\"<p>hijack</p>\"}")
        .patch("/api/comments/" + aliceComment)
        .then()
        .statusCode(403);

    String body =
        jdbc.queryForObject(
            "SELECT body FROM comments WHERE id=?::uuid", String.class, aliceComment);
    assertThat(body).isEqualTo("<p>alice comment</p>");
    // bob's own comment id is unused beyond setup; reference it to avoid a warning.
    assertThat(id).isNotBlank();
  }

  @Test
  void update_byAdmin_canEditAnyComment() {
    String id = createComment(tokenBob, ticketId, "<p>bob wrote this</p>");
    given()
        .header("Authorization", "Bearer " + tokenAlice)
        .contentType(ContentType.JSON)
        .body("{\"body\":\"<p>admin edited</p>\"}")
        .patch("/api/comments/" + id)
        .then()
        .statusCode(200)
        .body("body", equalTo("<p>admin edited</p>"));
  }

  // ───────────────────────── delete ─────────────────────────

  @Test
  void delete_softDeletes_clearsMentions_writesActivity_andIsIdempotentBoundary() {
    String id = createComment(tokenAlice, ticketId, "<p>delete @bob me</p>");
    given().header("Authorization", "Bearer " + tokenAlice).delete("/api/comments/" + id).then().statusCode(204);

    Instant deletedAt =
        jdbc.queryForObject("SELECT deleted_at FROM comments WHERE id=?::uuid", Instant.class, id);
    assertThat(deletedAt).isNotNull();

    Integer mentionRows =
        jdbc.queryForObject(
            "SELECT count(*) FROM comment_mentions WHERE comment_id=?::uuid", Integer.class, id);
    assertThat(mentionRows).isZero();

    Integer deletedActivity =
        jdbc.queryForObject(
            "SELECT count(*) FROM activity_events WHERE ticket_id=?::uuid AND event_type='COMMENT_DELETED'",
            Integer.class,
            ticketId);
    assertThat(deletedActivity).isEqualTo(1);

    // Second delete → 404 (already soft-deleted).
    given().header("Authorization", "Bearer " + tokenAlice).delete("/api/comments/" + id).then().statusCode(404);
  }

  @Test
  void delete_byNonAuthorNonAdmin_returns403() {
    String id = createComment(tokenAlice, ticketId, "<p>alice owns</p>");
    given().header("Authorization", "Bearer " + tokenBob).delete("/api/comments/" + id).then().statusCode(403);
  }

  // ───────────────────────── security ─────────────────────────

  @Test
  void unauthenticated_returns401() {
    given().get("/api/tickets/" + ticketId + "/comments").then().statusCode(401);
    given().contentType(ContentType.JSON).body("{\"body\":\"<p>x</p>\"}")
        .post("/api/tickets/" + ticketId + "/comments").then().statusCode(401);
  }

  // ───────────────────────── helpers ─────────────────────────

  private UUID register(String email, String displayName) {
    String id =
        given()
            .contentType(ContentType.JSON)
            .body(
                "{\"email\":\""
                    + email
                    + "\",\"password\":\"Password123!\",\"displayName\":\""
                    + displayName
                    + "\"}")
            .when()
            .post("/api/auth/register")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("id");
    return UUID.fromString(id);
  }

  private static String sign(UUID subject) {
    try {
      JWTClaimsSet claims =
          new JWTClaimsSet.Builder()
              .subject(subject.toString())
              .expirationTime(Date.from(Instant.now().plusSeconds(900)))
              .build();
      SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
      jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
      return jwt.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  private String createProject(String token, String key, String name) {
    return given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body("{\"key\":\"" + key + "\",\"name\":\"" + name + "\"}")
        .when()
        .post("/api/projects")
        .then()
        .statusCode(201)
        .extract()
        .jsonPath()
        .getString("id");
  }

  private void addMember(String projectId, String email, String role) {
    given()
        .header("Authorization", "Bearer " + tokenAlice)
        .contentType(ContentType.JSON)
        .body("{\"email\":\"" + email + "\",\"role\":\"" + role + "\"}")
        .post("/api/projects/" + projectId + "/members")
        .then()
        .statusCode(201);
  }

  private String createTicket(String token, String projectId, String title) {
    return given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body("{\"title\":\"" + title + "\"}")
        .when()
        .post("/api/projects/" + projectId + "/tickets")
        .then()
        .statusCode(201)
        .extract()
        .jsonPath()
        .getString("id");
  }

  private Response postComment(String token, String tId, String body) {
    return given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body("{\"body\":\"" + body + "\"}")
        .when()
        .post("/api/tickets/" + tId + "/comments");
  }

  private String createComment(String token, String tId, String body) {
    return postComment(token, tId, body)
        .then()
        .statusCode(201)
        .extract()
        .jsonPath()
        .getString("id");
  }
}
