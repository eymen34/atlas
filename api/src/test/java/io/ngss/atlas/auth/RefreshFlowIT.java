package io.ngss.atlas.auth;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.ngss.atlas.Application;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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

/** AC-3 / EC-2: refresh rotation, reuse/expiry/garbage → 401, race-safety. */
@SpringBootTest(
    classes = Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {"BCRYPT_COST=12", "spring.jpa.hibernate.ddl-auto=validate"})
class RefreshFlowIT {

  private static final String SECRET = "refreshit-secret-min-32-characters-long-ok";
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

  private String loginRefreshToken() {
    return given()
        .contentType(ContentType.JSON)
        .body("{\"email\":\"" + EMAIL + "\",\"password\":\"" + PW + "\"}")
        .when()
        .post("/api/auth/login")
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getString("refreshToken");
  }

  private Response refresh(String rawToken) {
    return given()
        .contentType(ContentType.JSON)
        .body("{\"refreshToken\":\"" + rawToken + "\"}")
        .when()
        .post("/api/auth/refresh");
  }

  @Test
  void rotateReturnsNewTokensAndMarksOldRevokedAndReplaced() {
    String oldRaw = loginRefreshToken();

    Response resp = refresh(oldRaw);
    resp.then().statusCode(200);
    String newRaw = resp.jsonPath().getString("refreshToken");
    assertThat(newRaw).isNotBlank().isNotEqualTo(oldRaw);

    String oldHash = RefreshTokenService.sha256Hex(oldRaw);
    String newHash = RefreshTokenService.sha256Hex(newRaw);

    Object oldRevoked =
        jdbc.queryForMap("SELECT revoked_at, replaced_by_id, id FROM refresh_tokens WHERE token_hash = ?", oldHash)
            .get("revoked_at");
    assertThat(oldRevoked).as("old token revoked").isNotNull();

    String newId =
        jdbc.queryForObject(
            "SELECT id::text FROM refresh_tokens WHERE token_hash = ?", String.class, newHash);
    String replacedBy =
        jdbc.queryForObject(
            "SELECT replaced_by_id::text FROM refresh_tokens WHERE token_hash = ?",
            String.class,
            oldHash);
    assertThat(replacedBy).isEqualTo(newId);

    Object newRevoked =
        jdbc.queryForMap("SELECT revoked_at FROM refresh_tokens WHERE token_hash = ?", newHash)
            .get("revoked_at");
    assertThat(newRevoked).as("new token live").isNull();
  }

  @Test
  void replayOfRotatedTokenReturns401AndCreatesNoRow() {
    String oldRaw = loginRefreshToken();
    refresh(oldRaw).then().statusCode(200);
    long before = jdbc.queryForObject("SELECT count(*) FROM refresh_tokens", Long.class);

    refresh(oldRaw).then().statusCode(401);

    long after = jdbc.queryForObject("SELECT count(*) FROM refresh_tokens", Long.class);
    assertThat(after).isEqualTo(before);
  }

  @Test
  void expiredTokenReturns401() {
    String raw = loginRefreshToken();
    jdbc.update(
        "UPDATE refresh_tokens SET expires_at = now() - interval '1 day' WHERE token_hash = ?",
        RefreshTokenService.sha256Hex(raw));
    refresh(raw).then().statusCode(401);
  }

  @Test
  void revokedTokenReturns401() {
    String raw = loginRefreshToken();
    jdbc.update(
        "UPDATE refresh_tokens SET revoked_at = now() WHERE token_hash = ?",
        RefreshTokenService.sha256Hex(raw));
    refresh(raw).then().statusCode(401);
  }

  @Test
  void garbageTokenReturns401() {
    refresh("not-a-real-token").then().statusCode(401);
  }

  @Test
  void concurrentRotateYieldsExactlyOneWinner() throws InterruptedException {
    String oldRaw = loginRefreshToken();
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    AtomicInteger ok = new AtomicInteger();
    AtomicInteger unauthorized = new AtomicInteger();
    AtomicInteger other = new AtomicInteger();

    Runnable task =
        () -> {
          try {
            start.await();
            int sc = refresh(oldRaw).then().extract().statusCode();
            if (sc == 200) {
              ok.incrementAndGet();
            } else if (sc == 401) {
              unauthorized.incrementAndGet();
            } else {
              other.incrementAndGet();
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        };
    pool.submit(task);
    pool.submit(task);
    start.countDown();
    pool.shutdown();
    assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

    assertThat(ok.get()).as("exactly one 200").isEqualTo(1);
    assertThat(unauthorized.get()).as("exactly one 401").isEqualTo(1);
    assertThat(other.get()).as("no 5xx").isZero();
    // original (revoked) + exactly one new row
    assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens", Long.class)).isEqualTo(2L);
  }
}
