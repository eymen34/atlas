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
 * Watcher endpoint coverage (T-023): idempotent watch/unwatch (UNIQUE + ON
 * CONFLICT / no-op delete), the non-member 404 split (existence-leak prevention),
 * and unauthenticated 401. Self-skips without Docker.
 */
@SpringBootTest(
    classes = Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {"BCRYPT_COST=12", "spring.jpa.hibernate.ddl-auto=validate"})
class WatcherIT {

  private static final String SECRET = "watcherit-secret-min-32-characters-long-okay!";

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
  private UUID carol; // stranger (valid token, never a member)
  private String tokenAlice;
  private String tokenBob;
  private String tokenCarol;
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
    String engId = createProject(tokenAlice, "ENG", "Engineering");
    addMember(engId, "bob@example.com");
    ticketId = createTicket(tokenAlice, engId, "Ticket one");
  }

  @AfterEach
  void reset() {
    RestAssured.reset();
  }

  private int watcherCount(String tId, UUID userId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM ticket_watchers WHERE ticket_id=?::uuid AND user_id=?::uuid",
        Integer.class,
        tId,
        userId.toString());
  }

  @Test
  void doublePut_isIdempotent_oneRow() {
    // Use bob (member who is NOT auto-watched by ticket create) for a clean count.
    putWatch(tokenBob, ticketId).then().statusCode(204);
    putWatch(tokenBob, ticketId).then().statusCode(204);
    assertThat(watcherCount(ticketId, bob)).isEqualTo(1);
  }

  @Test
  void doubleDelete_isIdempotent_zeroRows() {
    putWatch(tokenBob, ticketId).then().statusCode(204);
    deleteWatch(tokenBob, ticketId).then().statusCode(204);
    deleteWatch(tokenBob, ticketId).then().statusCode(204);
    assertThat(watcherCount(ticketId, bob)).isZero();
  }

  @Test
  void listWatchers_returnsBareArray_includingAutoWatchedCreator() {
    given()
        .header("Authorization", "Bearer " + tokenAlice)
        .get("/api/tickets/" + ticketId + "/watchers")
        .then()
        .statusCode(200)
        // alice (creator) was auto-watched on create.
        .body("$", org.hamcrest.Matchers.hasItem(alice.toString()));
  }

  @Test
  void nonMember_gets404_onPutAndGet_notLeakingExistence() {
    putWatch(tokenCarol, ticketId).then().statusCode(404);
    given()
        .header("Authorization", "Bearer " + tokenCarol)
        .get("/api/tickets/" + ticketId + "/watchers")
        .then()
        .statusCode(404);
  }

  @Test
  void unauthenticated_gets401() {
    given().put("/api/tickets/" + ticketId + "/watch").then().statusCode(401);
    given().get("/api/tickets/" + ticketId + "/watchers").then().statusCode(401);
  }

  // ───────────────────────── helpers ─────────────────────────

  private Response putWatch(String token, String tId) {
    return given().header("Authorization", "Bearer " + token).put("/api/tickets/" + tId + "/watch");
  }

  private Response deleteWatch(String token, String tId) {
    return given()
        .header("Authorization", "Bearer " + token)
        .delete("/api/tickets/" + tId + "/watch");
  }

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

  private void addMember(String projectId, String email) {
    given()
        .header("Authorization", "Bearer " + tokenAlice)
        .contentType(ContentType.JSON)
        .body("{\"email\":\"" + email + "\",\"role\":\"MEMBER\"}")
        .post("/api/projects/" + projectId + "/members")
        .then()
        .statusCode(201);
  }

  private String createTicket(String token, String projectId, String title) {
    return given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body("{\"title\":\"" + title + "\"}")
        .post("/api/projects/" + projectId + "/tickets")
        .then()
        .statusCode(201)
        .extract()
        .jsonPath()
        .getString("id");
  }
}
