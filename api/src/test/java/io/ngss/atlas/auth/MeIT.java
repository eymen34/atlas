package io.ngss.atlas.auth;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.ngss.atlas.Application;
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

/** AC-5: GET /me with valid / missing / invalid / expired / non-UUID tokens. */
@SpringBootTest(
    classes = Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {"BCRYPT_COST=12", "spring.jpa.hibernate.ddl-auto=validate"})
class MeIT {

  private static final String SECRET = "meit-secret-min-32-characters-long-okay-yes";
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
    jdbc.update("DELETE FROM refresh_tokens");
    jdbc.update("DELETE FROM password_credentials");
    jdbc.update("DELETE FROM users");
    given()
        .contentType(ContentType.JSON)
        .body("{\"email\":\"" + EMAIL + "\",\"password\":\"" + PW + "\",\"displayName\":\"Alice\"}")
        .when()
        .post("/api/auth/register")
        .then()
        .statusCode(201);
  }

  @AfterEach
  void reset() {
    RestAssured.reset();
  }

  private String validAccessToken() {
    return given()
        .contentType(ContentType.JSON)
        .body("{\"email\":\"" + EMAIL + "\",\"password\":\"" + PW + "\"}")
        .when()
        .post("/api/auth/login")
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getString("accessToken");
  }

  private static String sign(String subject, Instant exp) throws JOSEException {
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder().subject(subject).expirationTime(Date.from(exp)).build();
    SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
    jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
    return jwt.serialize();
  }

  @Test
  void validBearerReturns200WithAllFields() {
    given()
        .header("Authorization", "Bearer " + validAccessToken())
        .when()
        .get("/api/auth/me")
        .then()
        .statusCode(200)
        .body("id", notNullValue())
        .body("email", equalTo(EMAIL))
        .body("displayName", equalTo("Alice"))
        .body("createdAt", notNullValue());
  }

  @Test
  void noBearerReturns401() {
    given().when().get("/api/auth/me").then().statusCode(401);
  }

  @Test
  void garbageBearerReturns401() {
    given()
        .header("Authorization", "Bearer not.a.valid.jwt")
        .when()
        .get("/api/auth/me")
        .then()
        .statusCode(401);
  }

  @Test
  void expiredBearerReturns401() throws JOSEException {
    String expired = sign(UUID.randomUUID().toString(), Instant.now().minusSeconds(60));
    given()
        .header("Authorization", "Bearer " + expired)
        .when()
        .get("/api/auth/me")
        .then()
        .statusCode(401);
  }

  @Test
  void nonUuidSubjectReturns401() throws JOSEException {
    // Validly signed, unexpired, but sub is not a UUID → currentUserId() → 401.
    String nonUuid = sign("not-a-uuid", Instant.now().plusSeconds(600));
    given()
        .header("Authorization", "Bearer " + nonUuid)
        .when()
        .get("/api/auth/me")
        .then()
        .statusCode(401);
  }
}
