package io.ngss.atlas.link;

import static io.restassured.RestAssured.given;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared harness for the T-026 ticket-link ITs. Guarded SINGLETON Postgres
 * (testcontainers_singleton_shared_base) — started once, NOT a JUnit-managed
 * @Container — so the cached context shared across subclasses never points at a
 * stopped container. {@code hibernate.generate_statistics} is on so the no-N+1 test
 * can read the prepared-statement count. Self-skips without Docker.
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(
    properties = {
      "BCRYPT_COST=12",
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.jpa.properties.hibernate.generate_statistics=true"
    })
abstract class LinkITBase extends BaseIT {

  static final String SECRET = "ticket-link-it-secret-min-32-characters-long!";

  @SuppressWarnings("resource")
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"))
          .withDatabaseName("atlas")
          .withUsername("atlas")
          .withPassword("atlas");

  static {
    if (DockerClientFactory.instance().isDockerAvailable()) {
      POSTGRES.start();
    }
  }

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

  @BeforeEach
  void baseSetUp() {
    RestAssured.baseURI = "http://localhost";
    RestAssured.port = port;
    BaseIT.cleanDatabase(jdbc);
  }

  @AfterEach
  void baseReset() {
    RestAssured.reset();
  }

  // ───────────────────────── HTTP helpers ─────────────────────────

  record TicketRef(String id, int number) {}

  UUID register(String email, String displayName) {
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

  static String sign(UUID subject) {
    try {
      JWTClaimsSet claims =
          new JWTClaimsSet.Builder()
              .subject(subject.toString())
              .expirationTime(Date.from(Instant.now().plusSeconds(900)))
              .build();
      SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
      jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
      return jwt.serialize();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  String createProject(String token, String key, String name) {
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

  void addMember(String adminToken, String projectId, String email) {
    given()
        .header("Authorization", "Bearer " + adminToken)
        .contentType(ContentType.JSON)
        .body("{\"email\":\"" + email + "\",\"role\":\"MEMBER\"}")
        .post("/api/projects/" + projectId + "/members")
        .then()
        .statusCode(201);
  }

  TicketRef createTicket(String token, String projectId, String title) {
    var jp =
        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body("{\"title\":\"" + title + "\"}")
            .post("/api/projects/" + projectId + "/tickets")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath();
    return new TicketRef(jp.getString("id"), jp.getInt("number"));
  }

  Response createLink(String token, String fromTicketId, String toTicketKey, String relation) {
    return given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body("{\"toTicketKey\":\"" + toTicketKey + "\",\"relation\":\"" + relation + "\"}")
        .post("/api/tickets/" + fromTicketId + "/links");
  }

  Response listLinks(String token, String ticketId) {
    return given().header("Authorization", "Bearer " + token).get("/api/tickets/" + ticketId + "/links");
  }

  Response deleteLink(String token, String linkId) {
    return given().header("Authorization", "Bearer " + token).delete("/api/links/" + linkId);
  }

  // ───────────────────────── JDBC assertion helpers ─────────────────────────

  int linkRowCount() {
    return jdbc.queryForObject("SELECT count(*) FROM ticket_links", Integer.class);
  }

  int linkRelationCount(String relation) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM ticket_links WHERE relation=?", Integer.class, relation);
  }

  int activityCount(String ticketId, String eventType) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM activity_events WHERE ticket_id=?::uuid AND event_type=?",
        Integer.class,
        ticketId,
        eventType);
  }
}
