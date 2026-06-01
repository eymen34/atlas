package io.ngss.atlas.auth;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.ngss.atlas.Application;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
 * AC-6c: across the full auth flow, application code (io.ngss.atlas) never logs
 * the plaintext password, a raw refresh token, or an access JWT. Capture is
 * scoped to the application logger (NOT ROOT) per log_security_test_scoping,
 * and we first assert the appender saw ≥1 event so the negative assertion can't
 * pass vacuously on a mis-wired appender.
 */
@SpringBootTest(
    classes = Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {"BCRYPT_COST=12", "spring.jpa.hibernate.ddl-auto=validate"})
class AuthLogDisciplineIT {

  private static final String SECRET = "logdiscipline-secret-min-32-characters-ok";
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
  }

  @AfterEach
  void reset() {
    RestAssured.reset();
  }

  @Test
  void applicationLoggersNeverEmitSecretsAcrossFullFlow() {
    Logger appLogger = (Logger) LoggerFactory.getLogger("io.ngss.atlas");
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    Level original = appLogger.getLevel();
    appLogger.addAppender(appender);
    appLogger.setLevel(Level.DEBUG);

    String refresh1;
    String refresh2;
    String access1;
    String access2;
    try {
      given()
          .contentType(ContentType.JSON)
          .body(
              "{\"email\":\"" + EMAIL + "\",\"password\":\"" + PW + "\",\"displayName\":\"Alice\"}")
          .when()
          .post("/api/auth/register")
          .then()
          .statusCode(201);

      // A failed login guarantees ≥1 application log event (GlobalExceptionHandler
      // INFO "authentication failure") so the wiring assertion below is meaningful.
      given()
          .contentType(ContentType.JSON)
          .body("{\"email\":\"" + EMAIL + "\",\"password\":\"WrongPass999!\"}")
          .when()
          .post("/api/auth/login")
          .then()
          .statusCode(401);

      Response loginResp =
          given()
              .contentType(ContentType.JSON)
              .body("{\"email\":\"" + EMAIL + "\",\"password\":\"" + PW + "\"}")
              .when()
              .post("/api/auth/login")
              .then()
              .statusCode(200)
              .extract()
              .response();
      access1 = loginResp.jsonPath().getString("accessToken");
      refresh1 = loginResp.jsonPath().getString("refreshToken");

      Response refreshResp =
          given()
              .contentType(ContentType.JSON)
              .body("{\"refreshToken\":\"" + refresh1 + "\"}")
              .when()
              .post("/api/auth/refresh")
              .then()
              .statusCode(200)
              .extract()
              .response();
      access2 = refreshResp.jsonPath().getString("accessToken");
      refresh2 = refreshResp.jsonPath().getString("refreshToken");

      given()
          .header("Authorization", "Bearer " + access1)
          .when()
          .get("/api/auth/me")
          .then()
          .statusCode(200);

      given()
          .header("Authorization", "Bearer " + access1)
          .contentType(ContentType.JSON)
          .body("{\"refreshToken\":\"" + refresh2 + "\"}")
          .when()
          .post("/api/auth/logout")
          .then()
          .statusCode(204);
    } finally {
      appLogger.detachAppender(appender);
      appLogger.setLevel(original);
    }

    List<ILoggingEvent> events = List.copyOf(appender.list);
    assertThat(events).as("appender is wired (captured ≥1 application event)").isNotEmpty();

    for (String secret : List.of(PW, refresh1, refresh2, access1, access2)) {
      assertThat(events)
          .as("no application log event may contain a secret")
          .noneSatisfy(event -> assertThat(event.getFormattedMessage()).contains(secret));
    }
  }
}
