package io.ngss.atlas.auth;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.ngss.atlas.Application;
import io.ngss.atlas.BaseIT;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
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
 * T-022: registration derives a unique mention_handle. Two users whose emails share
 * a local-part get distinct, suffixed handles ("alice" then "alice-2"), and a slug
 * with disallowed characters is sanitized. Self-skips without Docker.
 */
@SpringBootTest(
    classes = Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {"BCRYPT_COST=12", "spring.jpa.hibernate.ddl-auto=validate"})
class RegistrationHandleBackfillIT {

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
    registry.add("JWT_SECRET", () -> "reghandleit-secret-min-32-characters-long-ok!");
  }

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    RestAssured.baseURI = "http://localhost";
    RestAssured.port = port;
    BaseIT.cleanDatabase(jdbc);
  }

  @AfterEach
  void reset() {
    RestAssured.reset();
  }

  private void register(String email, String displayName) {
    given()
        .contentType(ContentType.JSON)
        .body(
            "{\"email\":\""
                + email
                + "\",\"password\":\"Password123!\",\"displayName\":\""
                + displayName
                + "\"}")
        .post("/api/auth/register")
        .then()
        .statusCode(201);
  }

  private String handleOf(String email) {
    return jdbc.queryForObject(
        "SELECT mention_handle FROM users WHERE lower(email)=lower(?)", String.class, email);
  }

  @Test
  void sameLocalPart_getsDistinctSuffixedHandles() {
    register("alice@example.com", "Alice One");
    register("alice@other.com", "Alice Two");

    assertThat(handleOf("alice@example.com")).isEqualTo("alice");
    assertThat(handleOf("alice@other.com")).isEqualTo("alice-2");
  }

  @Test
  void disallowedCharactersAreStrippedFromHandle() {
    register("a+b.c@example.com", "Punny");
    assertThat(handleOf("a+b.c@example.com")).isEqualTo("ab.c");
  }
}
