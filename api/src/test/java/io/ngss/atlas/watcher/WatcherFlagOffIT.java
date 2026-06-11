package io.ngss.atlas.watcher;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

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
 * Feature-flag OFF behaviour (T-023, AC-3): watcher endpoints 404, public config
 * reports watchers:false, and ticket create still succeeds writing ZERO watcher
 * rows (auto-watch is a flag-gated no-op). Self-skips without Docker.
 */
@SpringBootTest(
    classes = Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(
    properties = {
      "BCRYPT_COST=12",
      "spring.jpa.hibernate.ddl-auto=validate",
      "app.feature.watchers.enabled=false"
    })
class WatcherFlagOffIT {

  private static final String SECRET = "flagoffit-secret-min-32-characters-long-okay!";

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

  private String tokenAlice;
  private String engId;

  @BeforeEach
  void setUp() {
    RestAssured.baseURI = "http://localhost";
    RestAssured.port = port;
    BaseIT.cleanDatabase(jdbc);
    UUID alice = register("alice@example.com", "Alice");
    tokenAlice = sign(alice);
    engId = createProject(tokenAlice, "ENG", "Engineering");
  }

  @AfterEach
  void reset() {
    RestAssured.reset();
  }

  @Test
  void watcherEndpoints_return404_whenFlagOff() {
    String ticketId = createTicket("{\"title\":\"X\"}");
    given().header("Authorization", "Bearer " + tokenAlice).put("/api/tickets/" + ticketId + "/watch").then().statusCode(404);
    given().header("Authorization", "Bearer " + tokenAlice).delete("/api/tickets/" + ticketId + "/watch").then().statusCode(404);
    given().header("Authorization", "Bearer " + tokenAlice).get("/api/tickets/" + ticketId + "/watchers").then().statusCode(404);
  }

  @Test
  void publicConfig_reportsWatchersFalse() {
    given().get("/api/config/public").then().statusCode(200).body("features.watchers", equalTo(false));
  }

  @Test
  void ticketCreate_stillSucceeds_withZeroWatcherRows() {
    String ticketId = createTicket("{\"title\":\"NoWatch\",\"assigneeId\":\"" + UUID.randomUUID() + "\"}");
    Integer rows =
        jdbc.queryForObject(
            "SELECT count(*) FROM ticket_watchers WHERE ticket_id=?::uuid", Integer.class, ticketId);
    assertThat(rows).isZero();
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
