package io.ngss.atlas.auth;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.ngss.atlas.Application;
import io.ngss.atlas.BaseIT;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
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
 * T-033 AC5b / EC-1: the per-IP cap fires across DISTINCT emails. Account cap is raised to 100 so
 * the IP cap (3) is the proven trigger (post_implementation_note 6).
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(
    properties = {
      "BCRYPT_COST=12",
      "spring.jpa.hibernate.ddl-auto=validate",
      "LOGIN_IP_MAX_ATTEMPTS=3",
      "LOGIN_MAX_ATTEMPTS=100"
    })
class LoginAttemptIpCapIT {

  private static final String SECRET = "loginipcapit-secret-min-32-characters-okay!";
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

  @Test
  void testIpCapFiresAcrossDistinctEmails() {
    // 3 distinct (unknown) emails, one wrong attempt each — each 401, IP counter 1→2→3.
    login("a1@example.com", "WrongPass1!").then().statusCode(401);
    login("a2@example.com", "WrongPass2!").then().statusCode(401);
    login("a3@example.com", "WrongPass3!").then().statusCode(401);
    // A 4th DIFFERENT email: its own account bucket is empty (well under 100), but the IP is locked.
    login("a4@example.com", "WrongPass4!").then().statusCode(429);
    assertThat(ipCount()).isEqualTo(3); // the 4th 429'd before incrementing
  }

  @Test
  void testIpBucketNotClearedOnSuccessfulLogin() {
    register("real@example.com", "Real User");
    login("real@example.com", "WrongPass1!").then().statusCode(401); // IP → 1
    login("ghost@example.com", "WrongPass2!").then().statusCode(401); // IP → 2
    // A successful login clears the ACCOUNT bucket but must NOT clear the IP bucket.
    login("real@example.com", PW).then().statusCode(200);
    assertThat(ipCount()).as("IP bucket retained across a successful login").isEqualTo(2);
  }

  // ───────────────────────── helpers ─────────────────────────

  private void register(String email, String displayName) {
    given()
        .contentType(ContentType.JSON)
        .body(
            String.format(
                "{\"email\":\"%s\",\"password\":\"%s\",\"displayName\":\"%s\"}",
                email, PW, displayName))
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

  /** attempt_count for the single IP bucket row (cleanDatabase leaves at most one). */
  private Integer ipCount() {
    return jdbc
        .query(
            "SELECT attempt_count FROM login_attempts WHERE key_type = 'IP'",
            (rs, n) -> rs.getInt("attempt_count"))
        .stream()
        .findFirst()
        .orElse(null);
  }
}
