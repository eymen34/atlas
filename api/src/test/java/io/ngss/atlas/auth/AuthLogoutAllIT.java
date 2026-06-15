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
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * T-032: POST /api/auth/logout-all revokes ALL of the caller's live refresh tokens, is
 * authenticated, idempotent, and cross-user isolated. Guarded SINGLETON Postgres
 * (testcontainers_singleton_shared_base): started once in a static block, NOT a @Container;
 * self-skips without Docker.
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {"BCRYPT_COST=12", "spring.jpa.hibernate.ddl-auto=validate"})
class AuthLogoutAllIT {

  private static final String SECRET = "logoutallit-secret-min-32-characters-long-x!";
  private static final String PW = "AlicePass123!";

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
  void setUp() {
    RestAssured.baseURI = "http://localhost";
    RestAssured.port = port;
    BaseIT.cleanDatabase(jdbc);
  }

  @AfterEach
  void reset() {
    RestAssured.reset();
  }

  // ───────────────────────── helpers ─────────────────────────

  private void register(String email, String displayName) {
    given()
        .contentType(ContentType.JSON)
        .body(
            "{\"email\":\"" + email + "\",\"password\":\"" + PW + "\",\"displayName\":\""
                + displayName + "\"}")
        .when()
        .post("/api/auth/register")
        .then()
        .statusCode(201);
  }

  /** Each login issues ONE fresh refresh token, so N logins = N live tokens for the user. */
  private Response login(String email) {
    return given()
        .contentType(ContentType.JSON)
        .body("{\"email\":\"" + email + "\",\"password\":\"" + PW + "\"}")
        .when()
        .post("/api/auth/login")
        .then()
        .statusCode(200)
        .extract()
        .response();
  }

  private Response logout(String bearer, String refreshToken) {
    return given()
        .header("Authorization", "Bearer " + bearer)
        .contentType(ContentType.JSON)
        .body("{\"refreshToken\":\"" + refreshToken + "\"}")
        .when()
        .post("/api/auth/logout");
  }

  /** POST /api/auth/logout-all; sends the bearer only when non-null (null → no-auth case). */
  private Response logoutAll(String bearer) {
    var spec = given();
    if (bearer != null) {
      spec = spec.header("Authorization", "Bearer " + bearer);
    }
    return spec.when().post("/api/auth/logout-all");
  }

  /** revoked_at (as text) for the row whose token_hash is sha256(refresh); null while live. */
  private String revokedAt(String refresh) {
    return jdbc.queryForObject(
        "SELECT revoked_at::text FROM refresh_tokens WHERE token_hash = ?",
        String.class,
        RefreshTokenService.sha256Hex(refresh));
  }

  private long liveTokenCount(String email) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM refresh_tokens rt JOIN users u ON u.id = rt.user_id "
            + "WHERE u.email = ? AND rt.revoked_at IS NULL",
        Long.class,
        email);
  }

  // ───────────────────────── tests ─────────────────────────

  @Test
  void logoutAll_revokesAllLive_204_andLeavesAlreadyRevokedAndOtherUsersUntouched() {
    register("alice@example.com", "Alice");
    register("bob@example.com", "Bob");

    // Alice: three live tokens + a fourth that she individually logs out (already revoked).
    String r1 = login("alice@example.com").jsonPath().getString("refreshToken");
    String r2 = login("alice@example.com").jsonPath().getString("refreshToken");
    Response third = login("alice@example.com");
    String r3 = third.jsonPath().getString("refreshToken");
    String aliceAccess = third.jsonPath().getString("accessToken");

    Response fourth = login("alice@example.com");
    String r4 = fourth.jsonPath().getString("refreshToken");
    logout(fourth.jsonPath().getString("accessToken"), r4).then().statusCode(204);
    String r4RevokedBefore = revokedAt(r4);
    assertThat(r4RevokedBefore).isNotNull();

    // Bob has his own live token, which must stay live.
    String bobRefresh = login("bob@example.com").jsonPath().getString("refreshToken");

    logoutAll(aliceAccess).then().statusCode(204);

    assertThat(revokedAt(r1)).as("r1 revoked").isNotNull();
    assertThat(revokedAt(r2)).as("r2 revoked").isNotNull();
    assertThat(revokedAt(r3)).as("r3 revoked").isNotNull();
    assertThat(revokedAt(r4)).as("already-revoked token untouched").isEqualTo(r4RevokedBefore);
    assertThat(liveTokenCount("alice@example.com")).as("no live tokens left for Alice").isZero();

    // Cross-user isolation: Bob's token is untouched.
    assertThat(revokedAt(bobRefresh)).as("Bob untouched").isNull();
    assertThat(liveTokenCount("bob@example.com")).isEqualTo(1L);
  }

  @Test
  void logoutAll_withoutBearer_returns401() {
    logoutAll(null).then().statusCode(401);
  }

  @Test
  void afterLogoutAll_refreshWithAnOldToken_returns401() {
    register("alice@example.com", "Alice");
    Response login = login("alice@example.com");
    String refresh = login.jsonPath().getString("refreshToken");
    String access = login.jsonPath().getString("accessToken");

    logoutAll(access).then().statusCode(204);

    given()
        .contentType(ContentType.JSON)
        .body("{\"refreshToken\":\"" + refresh + "\"}")
        .when()
        .post("/api/auth/refresh")
        .then()
        .statusCode(401);
  }

  @Test
  void logoutAll_isIdempotent_secondCallRevokesNothing_204() {
    register("alice@example.com", "Alice");
    Response login = login("alice@example.com");
    String refresh = login.jsonPath().getString("refreshToken");
    String access = login.jsonPath().getString("accessToken");

    logoutAll(access).then().statusCode(204);
    String revokedAfterFirst = revokedAt(refresh);
    assertThat(revokedAfterFirst).isNotNull();

    // The access JWT is stateless (still valid); the second sweep finds nothing live.
    logoutAll(access).then().statusCode(204);
    assertThat(revokedAt(refresh)).as("revoked_at unchanged by the no-op call").isEqualTo(revokedAfterFirst);
    assertThat(liveTokenCount("alice@example.com")).isZero();
  }
}
