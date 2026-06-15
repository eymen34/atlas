package io.ngss.atlas.auth;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;

import io.ngss.atlas.Application;
import io.ngss.atlas.BaseIT;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.UUID;
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

  /** T-048: the raw refresh token is delivered as the atlas_refresh cookie, not in the body. */
  private String loginRefreshToken() {
    return login(EMAIL, PW);
  }

  /** T-048: refresh reads the cookie (body-less POST) and rotates a new cookie. */
  private Response refresh(String rawToken) {
    return given()
        .cookie(AuthCookieFactory.COOKIE_NAME, rawToken)
        .when()
        .post("/api/auth/refresh");
  }

  /** Refresh with the cookie and return the NEW rotated refresh token (from the Set-Cookie). */
  private String rotate(String rawToken) {
    return refresh(rawToken)
        .then()
        .statusCode(200)
        .extract()
        .detailedCookie(AuthCookieFactory.COOKIE_NAME)
        .getValue();
  }

  // ───────────────────────── T-031 reuse-detection helpers ─────────────────────────

  private void register(String email, String pw, String displayName) {
    given()
        .contentType(ContentType.JSON)
        .body(
            "{\"email\":\"" + email + "\",\"password\":\"" + pw + "\",\"displayName\":\""
                + displayName + "\"}")
        .when()
        .post("/api/auth/register")
        .then()
        .statusCode(201);
  }

  private String login(String email, String pw) {
    return given()
        .contentType(ContentType.JSON)
        .body("{\"email\":\"" + email + "\",\"password\":\"" + pw + "\"}")
        .when()
        .post("/api/auth/login")
        .then()
        .statusCode(200)
        .extract()
        .detailedCookie(AuthCookieFactory.COOKIE_NAME)
        .getValue();
  }

  /** The {@code revoked_at} of the row behind a raw token (null = live). */
  private Object revokedAt(String rawToken) {
    return jdbc.queryForMap(
            "SELECT revoked_at FROM refresh_tokens WHERE token_hash = ?",
            RefreshTokenService.sha256Hex(rawToken))
        .get("revoked_at");
  }

  private long liveCountForUser(UUID userId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM refresh_tokens WHERE user_id = ?::uuid AND revoked_at IS NULL",
        Long.class,
        userId.toString());
  }

  private UUID userIdOf(String rawToken) {
    return UUID.fromString(
        jdbc.queryForObject(
            "SELECT user_id::text FROM refresh_tokens WHERE token_hash = ?",
            String.class,
            RefreshTokenService.sha256Hex(rawToken)));
  }

  private String idOf(String rawToken) {
    return jdbc.queryForObject(
        "SELECT id::text FROM refresh_tokens WHERE token_hash = ?",
        String.class,
        RefreshTokenService.sha256Hex(rawToken));
  }

  private String replacedByOf(String rawToken) {
    return jdbc.queryForObject(
        "SELECT replaced_by_id::text FROM refresh_tokens WHERE token_hash = ?",
        String.class,
        RefreshTokenService.sha256Hex(rawToken));
  }

  @Test
  void rotateReturnsNewTokensAndMarksOldRevokedAndReplaced() {
    String oldRaw = loginRefreshToken();

    Response resp = refresh(oldRaw);
    resp.then().statusCode(200).body("$", not(hasKey("refreshToken")));
    String newRaw = resp.getDetailedCookie(AuthCookieFactory.COOKIE_NAME).getValue();
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
  void refreshWithJsonBodyReturns415() {
    // QG-3: /api/auth/refresh is body-less (cookie transport); an erroneous JSON body → 415.
    String raw = loginRefreshToken();
    given()
        .cookie(AuthCookieFactory.COOKIE_NAME, raw)
        .contentType(ContentType.JSON)
        .body("{\"refreshToken\":\"x\"}")
        .when()
        .post("/api/auth/refresh")
        .then()
        .statusCode(415);
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

  // ───────────────────────── T-031 reuse detection ─────────────────────────

  /** AC-1 + SEC-2: rotated-token replay → user-scoped mass revoke (T2 AND independent X), 401 body clean. */
  @Test
  void rotatedTokenReplay_revokesAllUserLiveTokens_AC1() {
    String t1 = loginRefreshToken();
    String t2 = rotate(t1);
    String x = loginRefreshToken(); // independent family, SAME user (alice)
    UUID alice = userIdOf(x);
    assertThat(liveCountForUser(alice)).as("T2 + X live before replay").isEqualTo(2L);

    Response replay = refresh(t1);

    // SEC-2: canonical 401 envelope, no token fields leaked.
    String body =
        replay
            .then()
            .statusCode(401)
            .body("status", equalTo(401))
            .body("error", equalTo("Unauthorized"))
            .body("message", equalTo("Invalid credentials"))
            .body("path", equalTo("/api/auth/refresh"))
            .extract()
            .asString();
    assertThat(body).doesNotContain("refreshToken").doesNotContain("accessToken");

    // AC-1: BOTH lineages of the user are revoked (proves user-scoped, not family-scoped).
    assertThat(revokedAt(t2)).as("rotated successor T2 revoked").isNotNull();
    assertThat(revokedAt(x)).as("independent token X revoked").isNotNull();
    assertThat(liveCountForUser(alice)).as("no live tokens remain for the user").isZero();
  }

  /** AC-2 (and the step-2 replacedById==null path of AC-4): logged-out head replay → NO mass revoke. */
  @Test
  void revokedHeadNoSuccessorReplay_noMassRevoke_AC2() {
    String t1 = loginRefreshToken();
    // Forge a logged-out head: revoked with NO successor (replaced_by_id NULL).
    jdbc.update(
        "UPDATE refresh_tokens SET revoked_at = now(), replaced_by_id = NULL WHERE token_hash = ?",
        RefreshTokenService.sha256Hex(t1));
    String x = loginRefreshToken(); // independent live token, same user

    refresh(t1).then().statusCode(401);

    assertThat(revokedAt(x)).as("X stays live — logged-out head replay is not a theft signal").isNull();
  }

  /** AC-3: normal T1→T2→T3 chain — all 200, links intact, exactly one live, no spurious revoke. */
  @Test
  void normalRotationChain_noSpuriousRevoke_AC3() {
    String t1 = loginRefreshToken();
    String t2 = rotate(t1);
    String t3 = rotate(t2);

    assertThat(replacedByOf(t1)).isEqualTo(idOf(t2));
    assertThat(replacedByOf(t2)).isEqualTo(idOf(t3));
    assertThat(revokedAt(t3)).as("head of the chain is live").isNull();
    assertThat(liveCountForUser(userIdOf(t3))).as("exactly one live token").isEqualTo(1L);
  }

  /** AC-5: expired-but-never-revoked replay → step-3 401, nothing revoked anywhere. */
  @Test
  void expiredNeverRevokedToken_noMassRevoke_AC5() {
    String t1 = loginRefreshToken();
    String x = loginRefreshToken();
    jdbc.update(
        "UPDATE refresh_tokens SET expires_at = now() - interval '1 day', revoked_at = NULL "
            + "WHERE token_hash = ?",
        RefreshTokenService.sha256Hex(t1));

    refresh(t1).then().statusCode(401);

    assertThat(revokedAt(t1)).as("expired token not revoked by the replay").isNull();
    assertThat(revokedAt(x)).as("other token stays live (step-3, no escalation)").isNull();
  }

  /** SEC-3: theft replay is user-scoped — userA's tokens revoked, userB's untouched. */
  @Test
  void crossUserIsolation_theftRevokesOnlyOwnerTokens_SEC3() {
    register("bob@example.com", "BobPass123!", "Bob");
    String y = login("bob@example.com", "BobPass123!"); // userB live token

    String t1 = loginRefreshToken(); // userA (alice)
    String t2 = rotate(t1);
    String x = loginRefreshToken();

    refresh(t1).then().statusCode(401);

    assertThat(revokedAt(t2)).as("A's T2 revoked").isNotNull();
    assertThat(revokedAt(x)).as("A's X revoked").isNotNull();
    assertThat(revokedAt(y)).as("B's token untouched (user-scoped revoke)").isNull();
  }

  /** SEC-4: a second replay of an already-mass-revoked token still 401 (idempotent, no 5xx). */
  @Test
  void doubleReplay_secondReplayStill401_SEC4() {
    String t1 = loginRefreshToken();
    refresh(t1).then().statusCode(200); // T1 -> T2
    loginRefreshToken(); // X
    refresh(t1).then().statusCode(401); // first replay: mass revoke
    refresh(t1).then().statusCode(401); // second replay: revokeAllLive is a 0-row no-op, still 401
  }
}
