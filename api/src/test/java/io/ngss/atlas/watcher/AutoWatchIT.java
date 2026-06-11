package io.ngss.atlas.watcher;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * Auto-watch trigger coverage (T-023): creator + assignee on create (incl.
 * creator==assignee → ONE row, no rollback — AC-2.3 / jpa_rollback_only_trap),
 * new assignee on update (actor NOT added), commenter on comment create (dedup —
 * EC-5), and timestamp parity between the watcher row and the originating activity
 * row (EC-10). Self-skips without Docker.
 */
@SpringBootTest(
    classes = Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {"BCRYPT_COST=12", "spring.jpa.hibernate.ddl-auto=validate"})
class AutoWatchIT {

  private static final String SECRET = "autowatchit-secret-min-32-characters-long-ok!";

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

  private UUID alice; // creator/admin
  private UUID bob; // member
  private UUID dave; // member
  private String tokenAlice;
  private String tokenBob;
  private String engId;

  @BeforeEach
  void setUp() {
    RestAssured.baseURI = "http://localhost";
    RestAssured.port = port;
    BaseIT.cleanDatabase(jdbc);
    alice = register("alice@example.com", "Alice");
    bob = register("bob@example.com", "Bob");
    dave = register("dave@example.com", "Dave");
    tokenAlice = sign(alice);
    tokenBob = sign(bob);
    engId = createProject(tokenAlice, "ENG", "Engineering");
    addMember("bob@example.com");
    addMember("dave@example.com");
  }

  @AfterEach
  void reset() {
    RestAssured.reset();
  }

  private int count(String tId, UUID userId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM ticket_watchers WHERE ticket_id=?::uuid AND user_id=?::uuid",
        Integer.class,
        tId,
        userId.toString());
  }

  private int total(String tId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM ticket_watchers WHERE ticket_id=?::uuid", Integer.class, tId);
  }

  @Test
  void create_watchesCreator() {
    String id = createTicket("{\"title\":\"Solo\"}");
    assertThat(count(id, alice)).isEqualTo(1);
    assertThat(total(id)).isEqualTo(1);
  }

  @Test
  void create_creatorEqualsAssignee_oneRowNoError() {
    // AC-2.3: assigneeId == creator → exactly ONE row, HTTP 201, no rollback.
    String id = createTicket("{\"title\":\"Mine\",\"assigneeId\":\"" + alice + "\"}");
    assertThat(count(id, alice)).isEqualTo(1);
    assertThat(total(id)).isEqualTo(1);
  }

  @Test
  void create_withDistinctAssignee_watchesBoth() {
    String id = createTicket("{\"title\":\"Assigned\",\"assigneeId\":\"" + bob + "\"}");
    assertThat(count(id, alice)).isEqualTo(1);
    assertThat(count(id, bob)).isEqualTo(1);
    assertThat(total(id)).isEqualTo(2);
  }

  @Test
  void update_assigneeChange_watchesNewAssignee_notTheActor() {
    String id = createTicket("{\"title\":\"Unassigned\"}"); // only alice watches
    // bob (member, NOT watching) assigns dave → dave watched, bob (actor) NOT added.
    given()
        .header("Authorization", "Bearer " + tokenBob)
        .contentType(ContentType.JSON)
        .body("{\"assigneeId\":\"" + dave + "\"}")
        .patch("/api/tickets/" + id)
        .then()
        .statusCode(200);
    assertThat(count(id, dave)).isEqualTo(1);
    assertThat(count(id, bob)).isZero();
    assertThat(count(id, alice)).isEqualTo(1);
  }

  @Test
  void commentCreate_byAlreadyWatchingUser_keepsExactlyOneRow() {
    // EC-5: bob watches, then comments → ON CONFLICT DO NOTHING, still one row.
    String id = createTicket("{\"title\":\"Talk\"}");
    given().header("Authorization", "Bearer " + tokenBob).put("/api/tickets/" + id + "/watch").then().statusCode(204);
    given()
        .header("Authorization", "Bearer " + tokenBob)
        .contentType(ContentType.JSON)
        .body("{\"body\":\"<p>hi</p>\"}")
        .post("/api/tickets/" + id + "/comments")
        .then()
        .statusCode(201);
    assertThat(count(id, bob)).isEqualTo(1);
  }

  @Test
  void update_assigneeChange_watcherTimestampMatchesActivityTimestamp() {
    // EC-10: the new assignee's watcher row shares the ASSIGNEE_CHANGED instant.
    String id = createTicket("{\"title\":\"Stamp\"}");
    given()
        .header("Authorization", "Bearer " + tokenAlice)
        .contentType(ContentType.JSON)
        .body("{\"assigneeId\":\"" + bob + "\"}")
        .patch("/api/tickets/" + id)
        .then()
        .statusCode(200);

    Instant watcherTs =
        jdbc.queryForObject(
            "SELECT created_at FROM ticket_watchers WHERE ticket_id=?::uuid AND user_id=?::uuid",
            Instant.class,
            id,
            bob.toString());
    Instant activityTs =
        jdbc.queryForObject(
            "SELECT created_at FROM activity_events WHERE ticket_id=?::uuid AND event_type='ASSIGNEE_CHANGED'",
            Instant.class,
            id);
    assertThat(watcherTs).isNotNull();
    assertThat(activityTs).isNotNull();
    assertThat(watcherTs.truncatedTo(ChronoUnit.MICROS))
        .isEqualTo(activityTs.truncatedTo(ChronoUnit.MICROS));
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
        .post("/api/projects")
        .then()
        .statusCode(201)
        .extract()
        .jsonPath()
        .getString("id");
  }

  private void addMember(String email) {
    given()
        .header("Authorization", "Bearer " + tokenAlice)
        .contentType(ContentType.JSON)
        .body("{\"email\":\"" + email + "\",\"role\":\"MEMBER\"}")
        .post("/api/projects/" + engId + "/members")
        .then()
        .statusCode(201);
  }

  private String createTicket(String body) {
    return given()
        .header("Authorization", "Bearer " + tokenAlice)
        .contentType(ContentType.JSON)
        .body(body)
        .post("/api/projects/" + engId + "/tickets")
        .then()
        .statusCode(201)
        .extract()
        .jsonPath()
        .getString("id");
  }
}
