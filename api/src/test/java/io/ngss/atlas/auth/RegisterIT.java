package io.ngss.atlas.auth;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.ngss.atlas.Application;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * AC-2 / AC-3 / AC-4 / AC-5 / EC-1 / SEC-1 acceptance for POST
 * /api/auth/register. @SpringBootTest on a real PG17 via Testcontainers;
 * self-skips without Docker (CI-only per local_dev_docker_access).
 *
 * <p>ddl-auto=validate asserts the new entities map onto the migrated schema.
 */
@SpringBootTest(
    classes = Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {"BCRYPT_COST=12", "spring.jpa.hibernate.ddl-auto=validate"})
class RegisterIT {

  private static final String VALID_PW = "correcthorsebattery";

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
    registry.add("JWT_SECRET", () -> "registerit-test-secret-min-32-characters-long!");
  }

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    RestAssured.baseURI = "http://localhost";
    RestAssured.port = port;
    // Deterministic isolation: empty the auth tables before each test.
    jdbc.update("DELETE FROM password_credentials");
    jdbc.update("DELETE FROM users");
  }

  @AfterEach
  void reset() {
    RestAssured.reset();
  }

  private static String body(String email, String password, String displayName) {
    return String.format(
        "{\"email\":\"%s\",\"password\":\"%s\",\"displayName\":\"%s\"}",
        email, password, displayName);
  }

  private static int postRegisterStatus(String json) {
    return given()
        .contentType(ContentType.JSON)
        .body(json)
        .when()
        .post("/api/auth/register")
        .then()
        .extract()
        .statusCode();
  }

  // ── AC-2: happy path ──────────────────────────────────────────────────

  @Test
  void happyPath_returns201_andHashIsBcryptCost12() {
    Response resp =
        given()
            .contentType(ContentType.JSON)
            .body(body("Alice@Example.COM", VALID_PW, "Alice"))
            .when()
            .post("/api/auth/register");

    resp.then()
        .statusCode(201)
        .contentType(ContentType.JSON)
        .body("id", notNullValue())
        .body("email", equalTo("alice@example.com"))
        .body("displayName", equalTo("Alice"))
        .body("createdAt", notNullValue())
        .body("$", not(hasKey("password")))
        .body("$", not(hasKey("accessToken")))
        .body("$", not(hasKey("refreshToken")));

    String id = resp.jsonPath().getString("id");
    String hash =
        jdbc.queryForObject(
            "SELECT bcrypt_hash FROM password_credentials WHERE user_id = ?::uuid",
            String.class,
            id);
    assertThat(hash).startsWith("$2a$12$");
    assertThat(hash).hasSize(60);
    assertThat(hash).isNotEqualTo(VALID_PW);
    assertThat(passwordEncoder.matches(VALID_PW, hash)).isTrue();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(1);
  }

  // ── AC-3: duplicate handling ──────────────────────────────────────────

  @Test
  void duplicate_preCheck_returns409() {
    given()
        .contentType(ContentType.JSON)
        .body(body("bob@example.com", VALID_PW, "Bob"))
        .when()
        .post("/api/auth/register")
        .then()
        .statusCode(201);

    given()
        .contentType(ContentType.JSON)
        .body(body("bob@example.com", "anotherlongpassword", "Bobby"))
        .when()
        .post("/api/auth/register")
        .then()
        .statusCode(409)
        .body("status", equalTo(409))
        .body("error", equalTo("Conflict"))
        .body("message", equalTo("email already registered"))
        .body("path", equalTo("/api/auth/register"));

    assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(1);
  }

  @Test
  void duplicate_caseInsensitive_returns409() {
    given()
        .contentType(ContentType.JSON)
        .body(body("carol@example.com", VALID_PW, "Carol"))
        .when()
        .post("/api/auth/register")
        .then()
        .statusCode(201);

    given()
        .contentType(ContentType.JSON)
        .body(body("Carol@EXAMPLE.com", VALID_PW, "Carol2"))
        .when()
        .post("/api/auth/register")
        .then()
        .statusCode(409);

    assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(1);
  }

  @Test
  void duplicate_constraintViolationPath_returns409() throws InterruptedException {
    String json = body("race@example.com", VALID_PW, "Racer");
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    AtomicInteger created = new AtomicInteger();
    AtomicInteger conflict = new AtomicInteger();
    AtomicInteger other = new AtomicInteger();

    Runnable task =
        () -> {
          try {
            start.await();
            int status = postRegisterStatus(json);
            if (status == 201) {
              created.incrementAndGet();
            } else if (status == 409) {
              conflict.incrementAndGet();
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

    assertThat(created.get()).as("exactly one 201").isEqualTo(1);
    assertThat(conflict.get()).as("exactly one 409").isEqualTo(1);
    assertThat(other.get()).as("no 500 / other").isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM password_credentials", Integer.class))
        .isEqualTo(1);
  }

  // ── AC-3: validation ──────────────────────────────────────────────────

  @Test
  void validation_passwordTooShort_returns400() {
    assertThat(postRegisterStatus(body("shorty@example.com", "shortpwd9", "Shorty"))).isEqualTo(400);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
  }

  @Test
  void validation_blankEmail_returns400() {
    assertThat(postRegisterStatus(body("", VALID_PW, "NoEmail"))).isEqualTo(400);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
  }

  @Test
  void validation_blankDisplayName_returns400() {
    assertThat(postRegisterStatus(body("blankdn@example.com", VALID_PW, ""))).isEqualTo(400);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
  }

  @Test
  void validation_oversizedDisplayName_returns400() {
    String tooLong = "x".repeat(81);
    assertThat(postRegisterStatus(body("bigdn@example.com", VALID_PW, tooLong))).isEqualTo(400);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
  }

  // ── SEC-1 / AC-4.3: no plaintext password in logs ─────────────────────

  @Test
  void noPlaintextPasswordInAnyLogLevelDuringRegistration() {
    String sentinel = "DoNotLeakMe77!-" + UUID.randomUUID();
    Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    Level original = root.getLevel();
    root.addAppender(appender);
    root.setLevel(Level.TRACE);
    try {
      given()
          .contentType(ContentType.JSON)
          .body(body("sentinel@example.com", sentinel, "Sentinel"))
          .when()
          .post("/api/auth/register")
          .then()
          .statusCode(201)
          .body("$", not(hasKey("password")));
    } finally {
      root.setLevel(original);
      root.detachAppender(appender);
    }

    List<ILoggingEvent> events = List.copyOf(appender.list);
    assertThat(events)
        .as("no log event at any level may contain the plaintext password")
        .noneSatisfy(event -> assertThat(event.getFormattedMessage()).contains(sentinel));
  }

  // ── AC-5.1: live OpenAPI spec exposes the real 201 schema ─────────────

  @Test
  void openApiSpecContainsRegisterEndpointWith201Schema() {
    String spec =
        given().when().get("/v3/api-docs").then().statusCode(200).extract().asString();
    JsonNode root = objectMapper.readTree(spec);

    JsonNode response201 =
        root.path("paths").path("/api/auth/register").path("post").path("responses").path("201");
    assertThat(response201.isMissingNode()).as("register 201 response present").isFalse();

    String ref =
        response201
            .path("content")
            .path("application/json")
            .path("schema")
            .path("$ref")
            .asString();
    assertThat(ref).contains("UserRegisteredResponse");

    JsonNode schema = root.path("components").path("schemas").path("UserRegisteredResponse");
    JsonNode props = schema.path("properties");
    assertThat(props.has("id")).isTrue();
    assertThat(props.has("email")).isTrue();
    assertThat(props.has("displayName")).isTrue();
    assertThat(props.has("createdAt")).isTrue();
    assertThat(props.has("password")).isFalse();
    assertThat(props.has("accessToken")).isFalse();
    assertThat(props.has("refreshToken")).isFalse();
    assertThat(props.has("bcryptHash")).isFalse();
  }
}
