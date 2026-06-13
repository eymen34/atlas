package io.ngss.atlas.outbox;

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
import tools.jackson.databind.ObjectMapper;

/**
 * Shared harness for the T-029 outbox ITs (RestAssured over HTTP — real commits, so the
 * AFTER_COMMIT enqueue listeners actually fire). Guarded SINGLETON Postgres
 * (testcontainers_singleton_shared_base): started once, NOT a JUnit-managed {@code @Container}.
 * {@code OUTBOX_DRAIN_SHARED_SECRET} is set so the internal endpoint is "configured". Self-skips
 * without Docker.
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {"BCRYPT_COST=12", "spring.jpa.hibernate.ddl-auto=validate"})
abstract class OutboxITBase extends BaseIT {

  static final String SECRET = "outbox-it-jwt-secret-min-32-characters-long-x!";
  static final String DRAIN_SECRET = "outbox-drain-shared-secret-min-32-characters!";

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
    registry.add("OUTBOX_DRAIN_SHARED_SECRET", () -> DRAIN_SECRET);
  }

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired OutboxRepository outbox;
  @Autowired ObjectMapper objectMapper;

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

  /** POST the drain endpoint; sends {@code X-Internal-Secret} only when {@code secret != null}. */
  Response drainOutbox(String secret) {
    var spec = given();
    if (secret != null) {
      spec = spec.header("X-Internal-Secret", secret);
    }
    return spec.post("/internal/tasks/drain-outbox");
  }

  // ───────────────────────── outbox seeding / assertion helpers ─────────────────────────

  UUID enqueueEmail(String toEmail, String subject, String body) {
    return outbox.enqueue(
        OutboxKind.EMAIL_NOTIFICATION,
        objectMapper.valueToTree(new EmailPayload(toEmail, subject, body)));
  }

  /** Forces a row to look already-due with a given prior attempt count (for backoff/poison ITs). */
  void ageDueWithAttempts(UUID id, int attemptCount) {
    jdbc.update(
        "UPDATE outbox SET attempt_count = ?, status = 'PENDING', "
            + "next_attempt_at = now() - interval '1 second' WHERE id = ?::uuid",
        attemptCount,
        id.toString());
  }

  String outboxStatus(UUID id) {
    return jdbc.queryForObject(
        "SELECT status FROM outbox WHERE id = ?::uuid", String.class, id.toString());
  }

  int outboxAttemptCount(UUID id) {
    return jdbc.queryForObject(
        "SELECT attempt_count FROM outbox WHERE id = ?::uuid", Integer.class, id.toString());
  }

  Instant outboxNextAttemptAt(UUID id) {
    return jdbc.queryForObject(
        "SELECT next_attempt_at FROM outbox WHERE id = ?::uuid", Instant.class, id.toString());
  }

  String outboxLastError(UUID id) {
    return jdbc.queryForObject(
        "SELECT last_error FROM outbox WHERE id = ?::uuid", String.class, id.toString());
  }

  long countOutboxByKind(OutboxKind kind) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM outbox WHERE kind = ?", Long.class, kind.name());
  }
}
