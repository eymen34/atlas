package io.ngss.atlas.auth;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import io.ngss.atlas.Application;
import io.ngss.atlas.BaseIT;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.Locale;
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

/** T-033 AC1-AC5 + EC-9 + SEC-7: per-account login throttle end to end (default config: max 5 / 15 min). */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {"BCRYPT_COST=12", "spring.jpa.hibernate.ddl-auto=validate"})
class LoginAttemptIT {

  private static final String SECRET = "loginattemptit-secret-min-32-characters-ok!";
  private static final String EMAIL = "alice@example.com";
  private static final String PW = "AlicePass123!";

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

  @BeforeEach
  void setUp() {
    RestAssured.baseURI = "http://localhost";
    RestAssured.port = port;
    BaseIT.cleanDatabase(jdbc);
  }

  @AfterEach
  void reset() {
    RestAssured.reset();
  }

  // ───────────────────────── AC1 ─────────────────────────

  @Test
  void testAccountLockoutAfterFiveFailedAttempts() {
    register(EMAIL, PW, "Alice");
    for (int i = 0; i < 5; i++) {
      login(EMAIL, "WrongPass" + i + "!").then().statusCode(401);
    }
    Response sixth = login(EMAIL, "WrongPassX!");
    sixth
        .then()
        .statusCode(429)
        .body("status", equalTo(429))
        .body("error", equalTo("Too Many Requests"))
        .body("path", equalTo("/api/auth/login"));
    assertThat(Integer.parseInt(sixth.getHeader("Retry-After"))).isGreaterThanOrEqualTo(1);
  }

  // ───────────────────────── AC2 / SEC-1 ─────────────────────────

  @Test
  void testCorrectPasswordDuringLockoutStillReturns429() {
    register(EMAIL, PW, "Alice");
    for (int i = 0; i < 5; i++) {
      login(EMAIL, "WrongPass" + i + "!").then().statusCode(401);
    }
    // Correct password during the active lockout → still 429, never a token (throttle precedes creds).
    login(EMAIL, PW).then().statusCode(429).body("accessToken", nullValue());
  }

  // ───────────────────────── AC3 (no real-time wait — backdate via JDBC) ─────────────────────────

  @Test
  void testExpiredLockoutAllowsCorrectPassword_noSleep() {
    register(EMAIL, PW, "Alice");
    backdateAccountRow(EMAIL, 5, "16 minutes", "1 minute"); // window + lockout both elapsed
    login(EMAIL, PW).then().statusCode(200).body("accessToken", notNullValue());
  }

  @Test
  void testExpiredLockoutWrongPasswordReturns401AndResetsCounter() {
    register(EMAIL, PW, "Alice");
    backdateAccountRow(EMAIL, 5, "16 minutes", "1 minute");
    login(EMAIL, "WrongPass!").then().statusCode(401);
    // Window expired → the UPSERT reset the counter to 1 (a fresh window), not locked.
    assertThat(accountCount(EMAIL)).isEqualTo(1);
  }

  // ───────────────────────── AC4 ─────────────────────────

  @Test
  void testSuccessfulLoginClearsAccountBucket() {
    register(EMAIL, PW, "Alice");
    for (int i = 0; i < 4; i++) {
      login(EMAIL, "WrongPass" + i + "!").then().statusCode(401);
    }
    login(EMAIL, PW).then().statusCode(200); // success → account bucket cleared
    assertThat(accountCount(EMAIL)).as("account row deleted on success").isNull();
    // The next failure starts a fresh count → 401, NOT 429.
    login(EMAIL, "WrongPassZ!").then().statusCode(401);
  }

  // ───────────────────────── AC5a ─────────────────────────

  @Test
  void testUnknownEmailThrottles() {
    String ghost = "ghost-never-existed@example.com";
    for (int i = 0; i < 5; i++) {
      login(ghost, "WrongPass" + i + "!").then().statusCode(401);
    }
    login(ghost, "WrongPassX!").then().statusCode(429);
    assertThat(accountCount(ghost)).isEqualTo(5); // bucket exists despite no such user
  }

  // ───────────────────────── EC-9 ─────────────────────────

  @Test
  void testEmailCaseNormalization() {
    register(EMAIL, PW, "Alice");
    // Five mixed-case variants all normalize to one bucket.
    login("Alice@Example.com", "WrongPass1!").then().statusCode(401);
    login("ALICE@EXAMPLE.COM", "WrongPass2!").then().statusCode(401);
    login("alice@example.com", "WrongPass3!").then().statusCode(401);
    login("Alice@example.COM", "WrongPass4!").then().statusCode(401);
    login("  alice@example.com  ", "WrongPass5!").then().statusCode(401); // trimmed too
    login("alice@example.com", PW).then().statusCode(429); // 6th (any case) → locked
    assertThat(accountCount(EMAIL)).isEqualTo(5);
  }

  // ───────────────────────── SEC-7 ─────────────────────────

  @Test
  void testSqlInjectionViaEmail() {
    String injection = "x'; DROP TABLE login_attempts; --@example.com";
    login(injection, "WrongPass!")
        .then()
        .statusCode(anyOf(equalTo(400), equalTo(401), equalTo(429))); // never 500
    // Parameterized SQL → no injection; the table still exists.
    Integer tableExists =
        jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_name = 'login_attempts'",
            Integer.class);
    assertThat(tableExists).isEqualTo(1);
  }

  // ───────────────────────── helpers ─────────────────────────

  private void register(String email, String password, String displayName) {
    given()
        .contentType(ContentType.JSON)
        .body(
            String.format(
                "{\"email\":\"%s\",\"password\":\"%s\",\"displayName\":\"%s\"}",
                email, password, displayName))
        .when()
        .post("/api/auth/register")
        .then()
        .statusCode(201);
  }

  private Response login(String email, String password) {
    return given()
        .contentType(ContentType.JSON)
        .body(String.format("{\"email\":\"%s\",\"password\":\"%s\"}", email, password))
        .when()
        .post("/api/auth/login");
  }

  /** Inserts a pre-aged ACCOUNT bucket row directly (AC3: backdated, no real-time wait). */
  private void backdateAccountRow(String email, int count, String firstAgo, String lockedAgo) {
    jdbc.update(
        "INSERT INTO login_attempts "
            + "(id, attempt_key, key_type, attempt_count, first_attempt_at, locked_until) "
            + "VALUES (gen_random_uuid(), ?, 'ACCOUNT', ?, now() - CAST(? AS interval), "
            + "        now() - CAST(? AS interval))",
        email.trim().toLowerCase(Locale.ROOT),
        count,
        firstAgo,
        lockedAgo);
  }

  /** Current attempt_count for the email's ACCOUNT bucket, or null if no row. */
  private Integer accountCount(String email) {
    return jdbc
        .query(
            "SELECT attempt_count FROM login_attempts WHERE attempt_key = ? AND key_type = 'ACCOUNT'",
            (rs, n) -> rs.getInt("attempt_count"),
            email.trim().toLowerCase(Locale.ROOT))
        .stream()
        .findFirst()
        .orElse(null);
  }
}
