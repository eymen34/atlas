package io.ngss.atlas.search;

import static io.restassured.RestAssured.given;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.ngss.atlas.Application;
import io.ngss.atlas.BaseIT;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared harness for the T-028 search ITs. Guarded SINGLETON Postgres
 * (testcontainers_singleton_shared_base). Tickets are seeded via the real HTTP API so
 * the generated {@code search_doc} column is populated by Postgres (never hand-written).
 * Self-skips without Docker.
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {"BCRYPT_COST=12", "spring.jpa.hibernate.ddl-auto=validate"})
abstract class SearchITBase extends BaseIT {

  static final String SECRET = "search-it-secret-min-32-characters-long-x!";

  @SuppressWarnings("resource")
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"))
          .withDatabaseName("atlas")
          .withUsername("atlas")
          .withPassword("atlas");

  static {
    if (DockerClientFactory.instance().isDockerAvailable()) {
      POSTGRES.start();
    }
  }

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
  void baseSetUp() {
    RestAssured.baseURI = "http://localhost";
    RestAssured.port = port;
    BaseIT.cleanDatabase(jdbc);
  }

  @AfterEach
  void baseReset() {
    RestAssured.reset();
  }

  // ───────────────────────── helpers ─────────────────────────

  record TicketRef(String id, int number) {}

  UUID register(String email, String displayName) {
    String id =
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
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("id");
    return UUID.fromString(id);
  }

  static String sign(UUID subject) {
    try {
      JWTClaimsSet claims =
          new JWTClaimsSet.Builder()
              .subject(subject.toString())
              .expirationTime(Date.from(Instant.now().plusSeconds(900)))
              .build();
      SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
      jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
      return jwt.serialize();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  String createProject(String token, String key, String name) {
    return given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body("{\"key\":\"" + key + "\",\"name\":\"" + name + "\"}")
        .post("/api/projects")
        .then()
        .statusCode(201)
        .extract()
        .jsonPath()
        .getString("id");
  }

  void addMember(String adminToken, String projectId, String email) {
    given()
        .header("Authorization", "Bearer " + adminToken)
        .contentType(ContentType.JSON)
        .body("{\"email\":\"" + email + "\",\"role\":\"MEMBER\"}")
        .post("/api/projects/" + projectId + "/members")
        .then()
        .statusCode(201);
  }

  TicketRef createTicket(String token, String projectId, String title, String description) {
    var jp =
        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body("{\"title\":\"" + title + "\",\"description\":\"" + description + "\"}")
            .post("/api/projects/" + projectId + "/tickets")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath();
    return new TicketRef(jp.getString("id"), jp.getInt("number"));
  }

  Response searchProject(String token, String projectId, String q) {
    return given()
        .header("Authorization", "Bearer " + token)
        .queryParam("q", q)
        .get("/api/projects/" + projectId + "/tickets/search");
  }

  Response searchProject(String token, String projectId, String q, int page, int size) {
    return given()
        .header("Authorization", "Bearer " + token)
        .queryParam("q", q)
        .queryParam("page", page)
        .queryParam("size", size)
        .get("/api/projects/" + projectId + "/tickets/search");
  }

  Response searchGlobal(String token, String q) {
    return given().header("Authorization", "Bearer " + token).queryParam("q", q).get("/api/search/tickets");
  }

  String searchDoc(String ticketId) {
    return jdbc.queryForObject(
        "SELECT search_doc::text FROM tickets WHERE id=?::uuid", String.class, ticketId);
  }
}
