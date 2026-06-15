package io.ngss.atlas.auth;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.ngss.atlas.Application;
import io.ngss.atlas.BaseIT;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * AC-6 / QG-4 (MANDATORY): two near-simultaneous cookie refreshes of the SAME token yield exactly
 * one 200 (with a new Set-Cookie) and one 401 — the loser hits the step-4 lost-race branch, which
 * is NOT a theft signal, so the user's OTHER live token survives and the {@link
 * TokenTheftRevocationHelper} mass-revoke is NEVER invoked. Spies the helper (the theft-revoke
 * collaborator — {@code RefreshTokenService} itself has no {@code revokeAllLive}).
 */
@SpringBootTest(
    classes = Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {"BCRYPT_COST=12", "spring.jpa.hibernate.ddl-auto=validate"})
class ConcurrentRefreshCookieIT {

  private static final String SECRET = "concurrentcookieit-secret-min-32-chars-okay";
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
  @MockitoSpyBean TokenTheftRevocationHelper theftRevocationHelper;

  @BeforeEach
  void setUp() {
    RestAssured.baseURI = "http://localhost";
    RestAssured.port = port;
    BaseIT.cleanDatabase(jdbc);
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

  private String login() {
    return given()
        .contentType(ContentType.JSON)
        .body("{\"email\":\"" + EMAIL + "\",\"password\":\"" + PW + "\"}")
        .when()
        .post("/api/auth/login")
        .then()
        .statusCode(200)
        .extract()
        .detailedCookie(AuthCookieFactory.COOKIE_NAME)
        .getValue();
  }

  @Test
  void concurrentCookieRefresh_oneWinnerOneLoser_noMassRevoke() throws InterruptedException {
    String t0 = login(); // the token both threads will race on
    String otherDevice = login(); // a SECOND live token for the same user — must survive

    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    AtomicInteger ok = new AtomicInteger();
    AtomicInteger unauthorized = new AtomicInteger();
    AtomicInteger other = new AtomicInteger();
    AtomicReference<String> winnerNewCookie = new AtomicReference<>();

    Runnable task =
        () -> {
          try {
            start.await();
            Response resp = given().cookie(AuthCookieFactory.COOKIE_NAME, t0).post("/api/auth/refresh");
            int sc = resp.statusCode();
            if (sc == 200) {
              ok.incrementAndGet();
              winnerNewCookie.set(resp.getCookie(AuthCookieFactory.COOKIE_NAME));
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

    // The winner rotated to a NEW cookie value (T1 != T0).
    assertThat(winnerNewCookie.get()).as("winner got a new Set-Cookie").isNotBlank().isNotEqualTo(t0);

    // The lost-race is NOT a theft signal: the mass-revoke helper is never called…
    verify(theftRevocationHelper, never()).revokeAllLive(any(), any());

    // …and the user's OTHER device's token stays live.
    Object otherRevoked =
        jdbc.queryForMap(
                "SELECT revoked_at FROM refresh_tokens WHERE token_hash = ?",
                RefreshTokenService.sha256Hex(otherDevice))
            .get("revoked_at");
    assertThat(otherRevoked).as("other-device token survives the lost race").isNull();
  }
}
