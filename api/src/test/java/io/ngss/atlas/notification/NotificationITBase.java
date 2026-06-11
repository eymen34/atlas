package io.ngss.atlas.notification;

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
 * Shared harness for the T-024 notification ITs (RestAssured over HTTP — real
 * commits, so AFTER_COMMIT listeners actually fire). Subclasses add @Test methods.
 * Self-skips without Docker.
 */
@SpringBootTest(
    classes = Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {"BCRYPT_COST=12", "spring.jpa.hibernate.ddl-auto=validate"})
abstract class NotificationITBase extends BaseIT {

  static final String SECRET = "notification-it-secret-min-32-characters-long!";

  // SINGLETON container: started ONCE for the whole JVM and shared by every
  // NotificationITBase subclass — deliberately NOT a JUnit-managed @Container. A
  // shared static @Container on an abstract base is stopped by the @Testcontainers
  // extension after the FIRST subclass's afterAll, while the cached Spring context
  // (reused by the remaining subclasses) keeps a Hikari pool bound to the now-dead
  // mapped port → "Connection refused" (the T-024 CI red). A manually-started
  // singleton stays up until JVM exit (Ryuk reaps it). The start is guarded by Docker
  // availability so the class still self-skips (disabledWithoutDocker) on Docker-less
  // dev machines instead of throwing ExceptionInInitializerError at class load.
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

  String createTicket(String token, String projectId, String body) {
    return given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body(body)
        .post("/api/projects/" + projectId + "/tickets")
        .then()
        .statusCode(201)
        .extract()
        .jsonPath()
        .getString("id");
  }

  Response patch(String token, String ticketId, String body) {
    return given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body(body)
        .patch("/api/tickets/" + ticketId);
  }

  Response transition(String token, String ticketId, String toStatus) {
    return given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body("{\"toStatus\":\"" + toStatus + "\"}")
        .post("/api/tickets/" + ticketId + "/transition");
  }

  Response postComment(String token, String ticketId, String htmlBody) {
    return given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body("{\"body\":\"" + htmlBody + "\"}")
        .post("/api/tickets/" + ticketId + "/comments");
  }

  void putWatch(String token, String ticketId) {
    given()
        .header("Authorization", "Bearer " + token)
        .put("/api/tickets/" + ticketId + "/watch")
        .then()
        .statusCode(204);
  }

  Response listNotifications(String token, String query) {
    return given()
        .header("Authorization", "Bearer " + token)
        .get("/api/notifications" + query);
  }

  Response markRead(String token, String notificationId) {
    return given()
        .header("Authorization", "Bearer " + token)
        .post("/api/notifications/" + notificationId + "/read");
  }

  Response markAllRead(String token) {
    return given()
        .header("Authorization", "Bearer " + token)
        .post("/api/notifications/read-all");
  }

  // ───────────────────────── JDBC assertion helpers ─────────────────────────

  int countByKindAndUser(String kind, UUID userId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM notifications WHERE kind=? AND user_id=?::uuid",
        Integer.class,
        kind,
        userId.toString());
  }

  int countByTicket(String ticketId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM notifications WHERE ticket_id=?::uuid", Integer.class, ticketId);
  }
}
