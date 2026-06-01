package io.ngss.atlas.security;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.ngss.atlas.Application;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
    classes = Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class SecurityIT {

  private static final String TEST_SECRET = "securityit-test-secret-min-32-characters-long-ok!";

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
    registry.add("JWT_SECRET", () -> TEST_SECRET);
  }

  @LocalServerPort int port;

  @BeforeEach
  void configureRestAssured() {
    RestAssured.baseURI = "http://localhost";
    RestAssured.port = port;
  }

  @AfterEach
  void resetRestAssured() {
    RestAssured.reset();
  }

  // ── AC-1.1: existing observability endpoints stay unauthenticated ──────

  @Test
  void healthReturns200WithoutAuth() {
    given().when().get("/health").then().statusCode(200).body("status", equalTo("UP"));
  }

  @Test
  void readyReturns200WithoutAuth() {
    given().when().get("/ready").then().statusCode(200).body("status", equalTo("READY"));
  }

  @Test
  void prometheusReturns200WithoutAuth() {
    given().when().get("/actuator/prometheus").then().statusCode(200);
  }

  // ── AC-2.1: 401 JSON with requestId == response header ────────────────

  @Test
  void unauthenticatedRequestToProtectedRouteReturns401JsonWithMatchingRequestId() {
    Response resp = given().when().get("/api/nonexistent");
    resp.then()
        .statusCode(401)
        .contentType("application/json")
        .body("error", notNullValue())
        .body("requestId", notNullValue())
        .body("message", notNullValue());
    String bodyRequestId = resp.jsonPath().getString("requestId");
    String headerRequestId = resp.getHeader("X-Request-Id");
    assertThat(bodyRequestId).isNotBlank();
    assertThat(bodyRequestId).isEqualTo(headerRequestId);
  }

  @Test
  void actuatorInfoRequiresAuthentication() {
    given().when().get("/actuator/info").then().statusCode(401);
  }

  // ── AC-4.1 / AC-4.2: stub auth endpoints + auth requirement ───────────

  @Test
  void loginUnknownUserReturns401() {
    // T-012 made login real. Unknown user on an empty DB → 401 (generic).
    given()
        .contentType("application/json")
        .body("{\"email\":\"nobody@example.com\",\"password\":\"password123\"}")
        .when()
        .post("/api/auth/login")
        .then()
        .statusCode(401)
        .contentType("application/json");
  }

  @Test
  void registerReachableUnauthenticatedReturns400OnInvalidBody() {
    // T-011 turned register into a real endpoint (was a 501 stub). This stays a
    // SECURITY assertion: register is permitted without auth, so an invalid body
    // (missing displayName) reaches bean validation and returns 400 — proving it
    // is NOT blocked by the security filter (401/403). Full registration
    // behaviour is covered by RegisterIT.
    given()
        .contentType("application/json")
        .body("{\"email\":\"a@b.com\",\"password\":\"password123\"}")
        .when()
        .post("/api/auth/register")
        .then()
        .statusCode(400)
        .contentType("application/json");
  }

  @Test
  void refreshInvalidTokenReturns401() {
    // T-012 made refresh real. An unknown/garbage refresh token → 401.
    given()
        .contentType("application/json")
        .body("{\"refreshToken\":\"abc\"}")
        .when()
        .post("/api/auth/refresh")
        .then()
        .statusCode(401)
        .contentType("application/json");
  }

  @Test
  void meRequires401WithoutBearerToken() {
    given().when().get("/api/auth/me").then().statusCode(401);
  }

  @Test
  void logoutRequires401WithoutBearerToken() {
    given().when().post("/api/auth/logout").then().statusCode(401);
  }

  // ── AC-2.x: /internal/** is denyAll ───────────────────────────────────

  @Test
  void internalEndpointReturns401Or403Unauthenticated() {
    int status = given().when().get("/internal/anything").then().extract().statusCode();
    assertThat(status).isIn(401, 403);
  }

  // ── SEC-* JWT verification edge cases ─────────────────────────────────

  @Test
  void expiredJwtReturns401() throws JOSEException {
    String token =
        sign(
            new JWTClaimsSet.Builder()
                .subject("expired-user")
                .expirationTime(Date.from(Instant.now().minusSeconds(60)))
                .build(),
            TEST_SECRET);
    given()
        .header("Authorization", "Bearer " + token)
        .when()
        .get("/api/auth/me")
        .then()
        .statusCode(401);
  }

  @Test
  void wrongKeyJwtReturns401() throws JOSEException {
    String token =
        sign(
            new JWTClaimsSet.Builder()
                .subject("wrong-key-user")
                .expirationTime(Date.from(Instant.now().plusSeconds(600)))
                .build(),
            "different-secret-also-32-chars-long-aaaaaa");
    given()
        .header("Authorization", "Bearer " + token)
        .when()
        .get("/api/auth/me")
        .then()
        .statusCode(401);
  }

  @Test
  void algNoneJwtReturns401() {
    // Hand-rolled alg:none JWT. SignedJWT.parse rejects it (PlainJWT only),
    // so even if a future bug bypassed the algorithm check, parsing fails.
    String unsigned =
        java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8))
            + "."
            + java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(
                    "{\"sub\":\"attacker\",\"exp\":9999999999}".getBytes(StandardCharsets.UTF_8))
            + ".";
    given()
        .header("Authorization", "Bearer " + unsigned)
        .when()
        .get("/api/auth/me")
        .then()
        .statusCode(401);
  }

  @Test
  void validJwtOnInternalRouteReturns403NotGranted() throws JOSEException {
    String token =
        sign(
            new JWTClaimsSet.Builder()
                .subject("authenticated-user")
                .expirationTime(Date.from(Instant.now().plusSeconds(600)))
                .build(),
            TEST_SECRET);
    given()
        .header("Authorization", "Bearer " + token)
        .when()
        .get("/internal/anything")
        .then()
        .statusCode(403);
  }

  // ── EC-4: CORS preflight on denyAll path ──────────────────────────────

  @Test
  void corsPreflightOnInternalPathReturns200() {
    given()
        .header("Origin", "http://localhost:5173")
        .header("Access-Control-Request-Method", "GET")
        .when()
        .options("/internal/admin")
        .then()
        .statusCode(200)
        .header("Access-Control-Allow-Credentials", equalTo("true"));
  }

  // ── 401 body never leaks a stack trace ────────────────────────────────

  @Test
  void unauthorizedBodyHasNoStackTraceField() {
    given()
        .when()
        .get("/api/nonexistent")
        .then()
        .statusCode(401)
        .body("$", is(notNullValue()))
        .body("$", is(notNullValue()))
        .body(org.hamcrest.Matchers.not(hasKey("trace")))
        .body(org.hamcrest.Matchers.not(hasKey("stackTrace")));
  }

  private static String sign(JWTClaimsSet claims, String secret) throws JOSEException {
    SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
    jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
    return jwt.serialize();
  }
}
