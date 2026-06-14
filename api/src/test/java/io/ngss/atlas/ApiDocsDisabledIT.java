package io.ngss.atlas;

import static io.restassured.RestAssured.given;

import io.restassured.RestAssured;
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

/**
 * AC-2 / D1: with {@code API_DOCS_ENABLED=false}, springdoc deregisters the docs routes, so
 * {@code /v3/api-docs} and {@code /swagger-ui.html} return <b>404</b> (DispatcherServlet — no
 * handler), NOT an auth-gated 401/403. The SecurityConfig permitAll rules stay in place, so a 404
 * here proves the route is simply absent, not blocked by Security.
 *
 * <p>Self-contained {@code @Container} (mirrors {@code openapi.OpenApiDocsIT} — the enabled-path
 * sibling). It deliberately does NOT extend {@code BaseIT} (which is only the cleanDatabase
 * helper, with no container/datasource wiring). Self-skips without Docker → runs in CI.
 *
 * <p>{@code @DirtiesContext} is intentionally ABSENT: {@code properties={"API_DOCS_ENABLED=false"}}
 * gives this a distinct Spring context cache key from the default-enabled ITs, so the two contexts
 * coexist without cross-pollution and no teardown is needed.
 */
@SpringBootTest(
    classes = Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"API_DOCS_ENABLED=false"})
@Testcontainers(disabledWithoutDocker = true)
class ApiDocsDisabledIT {

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
    registry.add("JWT_SECRET", () -> "apidocsdisabledit-test-secret-min-32-characters!");
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

  @Test
  void apiDocsReturns404_notAuthGated_whenApiDocsDisabled() {
    // redirects().follow(false): assert the RAW status. Disabled → route not registered → 404
    // from DispatcherServlet; it must NOT be 401/403 (D1 = springdoc-native, no SecurityConfig
    // auth-gate). Following redirects would mask whether the 404 is direct.
    given()
        .redirects()
        .follow(false)
        .when()
        .get("/v3/api-docs")
        .then()
        .statusCode(404);
  }

  @Test
  void swaggerUiReturns404_notAuthGated_whenApiDocsDisabled() {
    given()
        .redirects()
        .follow(false)
        .when()
        .get("/swagger-ui.html")
        .then()
        .statusCode(404);
  }
}
