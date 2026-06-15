package io.ngss.atlas.auth;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;

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
 * T-048 cookie contract for the auth endpoints: the Set-Cookie attribute matrix on login
 * (HttpOnly + SameSite=Lax + Path=/api/auth + Max-Age + Secure-per-profile), absent-cookie behaviour
 * on /refresh (401) and /logout (204 + clear), the body-less 415 guard on both, and logout-all
 * clearing the cookie while revoking every live token. {@code app.auth.cookie.secure=false} (test
 * profile) so the Secure attribute is asserted ABSENT (there is no TLS on localhost).
 */
@SpringBootTest(
    classes = Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(
    properties = {
      "BCRYPT_COST=12",
      "spring.jpa.hibernate.ddl-auto=validate",
      "app.auth.cookie.secure=false"
    })
class AuthControllerCookieIT {

  private static final String SECRET = "authcookieit-secret-min-32-characters-long!";
  private static final String EMAIL = "alice@example.com";
  private static final String PW = "AlicePass123!";
  // 30 days (the REFRESH_TOKEN_TTL_DAYS default the cookie Max-Age tracks) in seconds.
  private static final long EXPECTED_MAX_AGE_SECONDS = 30L * 24 * 60 * 60;

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

  private Response login() {
    return given()
        .contentType(ContentType.JSON)
        .body("{\"email\":\"" + EMAIL + "\",\"password\":\"" + PW + "\"}")
        .when()
        .post("/api/auth/login")
        .then()
        .statusCode(200)
        .extract()
        .response();
  }

  /** The raw {@code Set-Cookie} header value for atlas_refresh (null if none). */
  private static String refreshSetCookie(Response resp) {
    return resp.getHeaders().getValues("Set-Cookie").stream()
        .filter(h -> h.startsWith(AuthCookieFactory.COOKIE_NAME + "="))
        .findFirst()
        .orElse(null);
  }

  @Test
  void login_setsCookieWithFullAttributeMatrix_andNoBodyToken() {
    Response resp = login();
    resp.then().body("$", not(hasKey("refreshToken"))); // QG-2: token not in the body

    String setCookie = refreshSetCookie(resp);
    assertThat(setCookie).as("Set-Cookie atlas_refresh present").isNotNull();
    assertThat(setCookie).contains("HttpOnly");
    assertThat(setCookie).contains("SameSite=Lax");
    assertThat(setCookie).contains("Path=/api/auth");
    assertThat(setCookie).contains("Max-Age=" + EXPECTED_MAX_AGE_SECONDS);
    // app.auth.cookie.secure=false in the test profile → no Secure attribute.
    assertThat(setCookie).doesNotContain("Secure");
    assertThat(resp.getDetailedCookie(AuthCookieFactory.COOKIE_NAME).getMaxAge())
        .isEqualTo(EXPECTED_MAX_AGE_SECONDS);
  }

  @Test
  void refreshWithoutCookieReturns401_noSetCookie() {
    Response resp = given().when().post("/api/auth/refresh");
    resp.then().statusCode(401);
    assertThat(refreshSetCookie(resp)).as("no Set-Cookie on a 401 refresh").isNull();
    resp.then().body("$", not(hasKey("accessToken")));
  }

  @Test
  void logoutWithoutCookieReturns204AndClearingCookie() {
    String access = login().jsonPath().getString("accessToken");
    Response resp =
        given().header("Authorization", "Bearer " + access).when().post("/api/auth/logout");
    resp.then().statusCode(204);
    String setCookie = refreshSetCookie(resp);
    assertThat(setCookie).as("clearing Set-Cookie present").isNotNull();
    assertThat(setCookie).contains("Max-Age=0");
  }

  @Test
  void refreshWithJsonBodyReturns415() {
    Response login = login();
    given()
        .cookie(AuthCookieFactory.COOKIE_NAME, login.getCookie(AuthCookieFactory.COOKIE_NAME))
        .contentType(ContentType.JSON)
        .body("{\"refreshToken\":\"anything\"}")
        .when()
        .post("/api/auth/refresh")
        .then()
        .statusCode(415);
  }

  @Test
  void logoutWithJsonBodyReturns415() {
    Response login = login();
    given()
        .header("Authorization", "Bearer " + login.jsonPath().getString("accessToken"))
        .cookie(AuthCookieFactory.COOKIE_NAME, login.getCookie(AuthCookieFactory.COOKIE_NAME))
        .contentType(ContentType.JSON)
        .body("{\"refreshToken\":\"anything\"}")
        .when()
        .post("/api/auth/logout")
        .then()
        .statusCode(415);
  }

  @Test
  void logoutAllClearsCookieAndRevokesEveryLiveToken() {
    login(); // a first live token
    Response second = login(); // a second live token (same user)
    String access = second.jsonPath().getString("accessToken");

    Response resp =
        given().header("Authorization", "Bearer " + access).when().post("/api/auth/logout-all");
    resp.then().statusCode(204);
    String setCookie = refreshSetCookie(resp);
    assertThat(setCookie).as("logout-all clears this browser's cookie").isNotNull();
    assertThat(setCookie).contains("Max-Age=0");

    Long live =
        jdbc.queryForObject(
            "SELECT count(*) FROM refresh_tokens rt JOIN users u ON u.id = rt.user_id "
                + "WHERE u.email = ? AND rt.revoked_at IS NULL",
            Long.class,
            EMAIL);
    assertThat(live).as("all of the user's live tokens revoked").isZero();
  }
}
