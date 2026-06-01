package io.ngss.atlas.auth;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import io.ngss.atlas.Application;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
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

/** AC-4: logout revokes, is idempotent, ownership-checked, and validated. */
@SpringBootTest(
    classes = Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {"BCRYPT_COST=12", "spring.jpa.hibernate.ddl-auto=validate"})
class LogoutIT {

  private static final String SECRET = "logoutit-secret-min-32-characters-long-okay";
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
    jdbc.update("DELETE FROM refresh_tokens");
    jdbc.update("DELETE FROM password_credentials");
    jdbc.update("DELETE FROM users");
  }

  @AfterEach
  void reset() {
    RestAssured.reset();
  }

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

  @Test
  void selfLogoutReturns204AndRevokesThenBlocksRefresh() {
    register("alice@example.com", "Alice");
    Response tokens = login("alice@example.com");
    String access = tokens.jsonPath().getString("accessToken");
    String refresh = tokens.jsonPath().getString("refreshToken");

    logout(access, refresh).then().statusCode(204);

    Object revoked =
        jdbc.queryForMap(
                "SELECT revoked_at FROM refresh_tokens WHERE token_hash = ?",
                RefreshTokenService.sha256Hex(refresh))
            .get("revoked_at");
    assertThat(revoked).isNotNull();

    given()
        .contentType(ContentType.JSON)
        .body("{\"refreshToken\":\"" + refresh + "\"}")
        .when()
        .post("/api/auth/refresh")
        .then()
        .statusCode(401);
  }

  @Test
  void reLogoutIsIdempotent204WithRevokedAtUnchanged() {
    register("alice@example.com", "Alice");
    Response tokens = login("alice@example.com");
    String access = tokens.jsonPath().getString("accessToken");
    String refresh = tokens.jsonPath().getString("refreshToken");
    String hash = RefreshTokenService.sha256Hex(refresh);

    logout(access, refresh).then().statusCode(204);
    String firstRevokedAt =
        jdbc.queryForObject(
            "SELECT revoked_at::text FROM refresh_tokens WHERE token_hash = ?", String.class, hash);

    logout(access, refresh).then().statusCode(204);
    String secondRevokedAt =
        jdbc.queryForObject(
            "SELECT revoked_at::text FROM refresh_tokens WHERE token_hash = ?", String.class, hash);

    assertThat(secondRevokedAt).isEqualTo(firstRevokedAt);
  }

  @Test
  void crossUserLogoutReturns403AndLeavesRowUnmodified() {
    register("alice@example.com", "Alice");
    register("bob@example.com", "Bob");
    String aliceRefresh = login("alice@example.com").jsonPath().getString("refreshToken");
    String bobAccess = login("bob@example.com").jsonPath().getString("accessToken");

    logout(bobAccess, aliceRefresh).then().statusCode(403);

    Object aliceRevoked =
        jdbc.queryForMap(
                "SELECT revoked_at FROM refresh_tokens WHERE token_hash = ?",
                RefreshTokenService.sha256Hex(aliceRefresh))
            .get("revoked_at");
    assertThat(aliceRevoked).as("alice's token untouched").isNull();
  }

  @Test
  void logoutWithoutBearerReturns401() {
    given()
        .contentType(ContentType.JSON)
        .body("{\"refreshToken\":\"whatever\"}")
        .when()
        .post("/api/auth/logout")
        .then()
        .statusCode(401);
  }

  @Test
  void logoutWithBlankRefreshTokenReturns400() {
    register("alice@example.com", "Alice");
    String access = login("alice@example.com").jsonPath().getString("accessToken");
    logout(access, "").then().statusCode(400);
  }

  @Test
  void refreshedAccessTokenKeepsOriginalUserIdSubject() throws Exception {
    register("alice@example.com", "Alice");
    String aliceId =
        jdbc.queryForObject(
            "SELECT id::text FROM users WHERE email = ?", String.class, "alice@example.com");
    String refresh = login("alice@example.com").jsonPath().getString("refreshToken");

    String newAccess =
        given()
            .contentType(ContentType.JSON)
            .body("{\"refreshToken\":\"" + refresh + "\"}")
            .when()
            .post("/api/auth/refresh")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getString("accessToken");

    SignedJWT jwt = SignedJWT.parse(newAccess);
    assertThat(jwt.verify(new MACVerifier(SECRET.getBytes(StandardCharsets.UTF_8)))).isTrue();
    assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo(aliceId);
  }
}
