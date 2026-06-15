package io.ngss.atlas.config;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import io.ngss.atlas.Application;
import io.restassured.RestAssured;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Public-config endpoint + security permit-ordering (T-023, AC-4). The config
 * endpoint is reachable unauthenticated; EVERY other /api/** path still requires a
 * token (the permanent SecurityConfig guard — never delete the 401 assertion).
 * Self-skips without Docker.
 */
@SpringBootTest(
    classes = Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {"BCRYPT_COST=12", "spring.jpa.hibernate.ddl-auto=validate"})
class PublicConfigControllerIT {

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
    registry.add("JWT_SECRET", () -> "publicconfigit-secret-min-32-characters-long!");
  }

  @LocalServerPort int port;

  @BeforeEach
  void setUp() {
    RestAssured.baseURI = "http://localhost";
    RestAssured.port = port;
  }

  @AfterEach
  void reset() {
    RestAssured.reset();
  }

  @Test
  void publicConfig_isReachableUnauthenticated_andReportsFeatures() {
    given()
        .get("/api/config/public")
        .then()
        .statusCode(200)
        .body("features.watchers", equalTo(true)); // default flag on
  }

  @Test
  void publicConfig_doesNotExposeInlineThumbnailsFlag() {
    // SEC-1 (T-040): the inline-thumbnails flag is INTERNAL (server-side behaviour only) and must
    // never leak into the unauthenticated public config — in any naming variant.
    String body = given().get("/api/config/public").then().statusCode(200).extract().asString();
    assertThat(body)
        .doesNotContain("inline-thumbnails")
        .doesNotContain("inlineThumbnails")
        .doesNotContain("FEATURE_INLINE_THUMBNAILS_ENABLED");
  }

  @Test
  void everyOtherApiPath_stillRequiresAuth() {
    // PERMANENT guard for SecurityConfig permit-ordering — never delete. The
    // /api/config/public permit must NOT widen to other /api/** paths.
    given().get("/api/tickets/" + UUID.randomUUID()).then().statusCode(401);
  }
}
