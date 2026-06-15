package io.ngss.atlas.user;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

import io.ngss.atlas.Application;
import io.ngss.atlas.BaseIT;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
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
 * T-044 acceptance for GET /api/users/{id}. Verifies the display-only contract
 * ({id, displayName} with NO email/PII), that resolution is NOT project-scoped (the
 * requester and the target share no project), the 404 for an unknown id, and the
 * 401 for an unauthenticated caller. Self-skips without Docker (CI-authoritative).
 */
@SpringBootTest(
    classes = Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {"BCRYPT_COST=12", "spring.jpa.hibernate.ddl-auto=validate"})
class UserSummaryIT {

  private static final String PW = "correcthorsebattery";

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"))
          .withDatabaseName("atlas")
          .withUsername("atlas")
          .withPassword("atlas");

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("app.database.url", POSTGRES::getJdbcUrl);
    registry.add("app.database.username", POSTGRES::getUsername);
    registry.add("app.database.password", POSTGRES::getPassword);
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("JWT_SECRET", () -> "usersummaryit-secret-min-32-characters-long-ok!");
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

  /** Registers a user and returns their id. Registration does NOT log the user in. */
  private String register(String email, String displayName) {
    return given()
        .contentType(ContentType.JSON)
        .body(
            "{\"email\":\"" + email + "\",\"password\":\"" + PW + "\",\"displayName\":\""
                + displayName + "\"}")
        .when()
        .post("/api/auth/register")
        .then()
        .statusCode(201)
        .extract()
        .jsonPath()
        .getString("id");
  }

  private String login(String email) {
    return given()
        .contentType(ContentType.JSON)
        .body("{\"email\":\"" + email + "\",\"password\":\"" + PW + "\"}")
        .when()
        .post("/api/auth/login")
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getString("accessToken");
  }

  @Test
  void authenticatedCallerResolvesAnyUserIdToDisplayNameOnly() {
    String aliceId = register("alice@example.com", "Alice");
    // Bob shares no project with Alice — the endpoint is authenticated, not project-scoped.
    register("bob@example.com", "Bob");
    String bobToken = login("bob@example.com");

    given()
        .header("Authorization", "Bearer " + bobToken)
        .when()
        .get("/api/users/" + aliceId)
        .then()
        .statusCode(200)
        .contentType(ContentType.JSON)
        .body("id", equalTo(aliceId))
        .body("displayName", equalTo("Alice"))
        // Display-only: the body must NOT leak email or any other PII/credential field.
        .body("$", not(hasKey("email")))
        .body("$", not(hasKey("createdAt")))
        .body("$", not(hasKey("role")))
        .body("$", not(hasKey("mentionHandle")));
  }

  @Test
  void unknownIdReturns404() {
    register("alice@example.com", "Alice");
    String token = login("alice@example.com");

    given()
        .header("Authorization", "Bearer " + token)
        .when()
        .get("/api/users/" + UUID.randomUUID())
        .then()
        .statusCode(404)
        .body("message", notNullValue());
  }

  @Test
  void noBearerReturns401() {
    String aliceId = register("alice@example.com", "Alice");
    given().when().get("/api/users/" + aliceId).then().statusCode(401);
  }

  @Test
  void garbageBearerReturns401() {
    String aliceId = register("alice@example.com", "Alice");
    given()
        .header("Authorization", "Bearer not.a.valid.jwt")
        .when()
        .get("/api/users/" + aliceId)
        .then()
        .statusCode(401);
  }
}
