package io.ngss.atlas.auth;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import io.ngss.atlas.Application;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
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

/** AC-1 / AC-2 / SEC-2: login issues verifiable tokens; refresh token stored hashed. */
@SpringBootTest(
    classes = Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {"BCRYPT_COST=12", "spring.jpa.hibernate.ddl-auto=validate"})
class LoginIT {

  private static final String SECRET = "loginit-secret-min-32-characters-long-okay!";
  private static final String ALICE_EMAIL = "alice@example.com";
  private static final String ALICE_PW = "AlicePass123!";

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

  private String register(String email, String password, String displayName) {
    return given()
        .contentType(ContentType.JSON)
        .body(
            String.format(
                "{\"email\":\"%s\",\"password\":\"%s\",\"displayName\":\"%s\"}",
                email, password, displayName))
        .when()
        .post("/api/auth/register")
        .then()
        .statusCode(201)
        .extract()
        .jsonPath()
        .getString("id");
  }

  private Response login(String email, String password) {
    return given()
        .contentType(ContentType.JSON)
        .body(String.format("{\"email\":\"%s\",\"password\":\"%s\"}", email, password))
        .when()
        .post("/api/auth/login");
  }

  @Test
  void happyLoginReturns200WithVerifiableTokens() throws Exception {
    String aliceId = register(ALICE_EMAIL, ALICE_PW, "Alice");

    Response resp = login(ALICE_EMAIL, ALICE_PW);
    resp.then()
        .statusCode(200)
        .contentType(ContentType.JSON)
        .body("accessToken", notNullValue())
        .body("refreshToken", notNullValue())
        .body("expiresIn", equalTo(900));

    String accessToken = resp.jsonPath().getString("accessToken");
    String refreshToken = resp.jsonPath().getString("refreshToken");
    assertThat(refreshToken).isNotBlank().isNotEqualTo(accessToken);

    SignedJWT jwt = SignedJWT.parse(accessToken);
    assertThat(jwt.verify(new MACVerifier(SECRET.getBytes(StandardCharsets.UTF_8)))).isTrue();
    assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo(aliceId);
    long ttl =
        jwt.getJWTClaimsSet().getExpirationTime().toInstant().getEpochSecond()
            - jwt.getJWTClaimsSet().getIssueTime().toInstant().getEpochSecond();
    assertThat(ttl).isEqualTo(900L);
  }

  @Test
  void accessTokenRoundTripsToMe() {
    String aliceId = register(ALICE_EMAIL, ALICE_PW, "Alice");
    String accessToken = login(ALICE_EMAIL, ALICE_PW).jsonPath().getString("accessToken");

    given()
        .header("Authorization", "Bearer " + accessToken)
        .when()
        .get("/api/auth/me")
        .then()
        .statusCode(200)
        .body("id", equalTo(aliceId))
        .body("email", equalTo(ALICE_EMAIL))
        .body("displayName", equalTo("Alice"))
        .body("createdAt", notNullValue());
  }

  @Test
  void unknownEmailAndWrongPasswordReturnIdentical401() {
    register(ALICE_EMAIL, ALICE_PW, "Alice");

    Response unknown = login("nobody@example.com", ALICE_PW);
    Response wrongPw = login(ALICE_EMAIL, "WrongPass999!");

    unknown.then().statusCode(401);
    wrongPw.then().statusCode(401);
    // Anti-enumeration: byte-for-byte identical bodies.
    assertThat(unknown.asString()).isEqualTo(wrongPw.asString());
  }

  @Test
  void loginPersistsExactlyOneHashedRefreshTokenRow() {
    register(ALICE_EMAIL, ALICE_PW, "Alice");
    String refreshToken = login(ALICE_EMAIL, ALICE_PW).jsonPath().getString("refreshToken");

    List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM refresh_tokens");
    assertThat(rows).hasSize(1);
    Map<String, Object> row = rows.get(0);

    String tokenHash = ((String) row.get("token_hash")).trim();
    assertThat(tokenHash).isEqualTo(RefreshTokenService.sha256Hex(refreshToken));
    assertThat(tokenHash).hasSize(64).matches("^[0-9a-f]{64}$");
    assertThat(row.get("revoked_at")).isNull();

    // Raw token must not appear verbatim in ANY column.
    row.values().forEach(v -> assertThat(String.valueOf(v)).doesNotContain(refreshToken));

    Integer days =
        jdbc.queryForObject(
            "SELECT EXTRACT(DAY FROM (expires_at - issued_at))::int FROM refresh_tokens",
            Integer.class);
    assertThat(days).isBetween(29, 31);
  }
}
