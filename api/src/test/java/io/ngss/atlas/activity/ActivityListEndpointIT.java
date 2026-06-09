package io.ngss.atlas.activity;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import com.nimbusds.jose.JOSEException;
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
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;
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
 * AC-3 / AC-4 / SEC-1 / headline: the GET /api/tickets/{id}/activity read endpoint
 * — newest-first ordering, PagedResponse envelope + size clamp, structured JSON
 * payload (not an escaped string), 401 unauthenticated, 404 non-member /
 * non-existent, and the full 5-row lifecycle scenario.
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {"BCRYPT_COST=12", "spring.jpa.hibernate.ddl-auto=validate"})
class ActivityListEndpointIT {

  private static final String SECRET = "activitylistit-secret-min-32-characters-ok!";

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

  private UUID userA;
  private String tokenA;
  private String tokenC; // stranger
  private String engId;
  private String t1;

  @BeforeEach
  void setUp() {
    RestAssured.baseURI = "http://localhost";
    RestAssured.port = port;
    BaseIT.cleanDatabase(jdbc);
    userA = register("usera@example.com", "Alice");
    UUID userC = register("userc@example.com", "Carol");
    tokenA = sign(userA);
    tokenC = sign(userC);
    engId = createProject("ENG", "Engineering");
    t1 = createTicket("{\"title\":\"Flow\",\"priority\":\"P3\"}"); // 1 CREATED row
  }

  @AfterEach
  void reset() {
    RestAssured.reset();
  }

  // ───────────────────────── headline 5-row scenario ─────────────────────────

  @Test
  void headline_fiveRowsNewestFirst() throws InterruptedException {
    transition(t1, "IN_PROGRESS");
    Thread.sleep(3);
    transition(t1, "DONE");
    Thread.sleep(3);
    patch(t1, "{\"assigneeId\":\"" + userA + "\"}");
    Thread.sleep(3);
    patch(t1, "{\"priority\":\"P1\"}");

    given().header("Authorization", "Bearer " + tokenA).get("/api/tickets/" + t1 + "/activity")
        .then().statusCode(200)
        .body("total", equalTo(5))
        .body("items.size()", equalTo(5))
        .body(
            "items.eventType",
            contains(
                "PRIORITY_CHANGED", "ASSIGNEE_CHANGED", "STATUS_CHANGED", "STATUS_CHANGED",
                "CREATED"));
  }

  // ───────────────────────── AC-4: structured JSON payload ─────────────────────────

  @Test
  void payload_isStructuredJsonObject_notEscapedString() {
    Response resp =
        given().header("Authorization", "Bearer " + tokenA).get("/api/tickets/" + t1 + "/activity");
    resp.then()
        .statusCode(200)
        .body("items[0].eventType", equalTo("CREATED"))
        .body("items[0].payload.title", equalTo("Flow"))
        .body("items[0].payload.status", equalTo("TODO"))
        .body("items[0].payload.priority", equalTo("P3"));
    // The payload must be a nested object, never an escaped string literal.
    assertThat(resp.asString()).doesNotContain("\"payload\":\"{");
  }

  // ───────────────────────── AC-3: paged envelope + clamp + ordering ─────────────────

  @Test
  void pagedEnvelope_clampsSize_andOrdersNewestFirst() throws InterruptedException {
    transition(t1, "IN_PROGRESS");
    Thread.sleep(3);
    transition(t1, "DONE");
    Thread.sleep(3);
    transition(t1, "TODO"); // 3 STATUS_CHANGED + 1 CREATED = 4 rows total

    // page 0, size 2.
    Response page0 =
        given().header("Authorization", "Bearer " + tokenA)
            .get("/api/tickets/" + t1 + "/activity?page=0&size=2");
    page0.then().statusCode(200)
        .body("items.size()", equalTo(2)).body("page", equalTo(0)).body("size", equalTo(2))
        .body("total", equalTo(4));
    // Newest-first (tolerant of µs rounding per timestamp_precision_assert).
    Instant first = Instant.parse(page0.jsonPath().getString("items[0].createdAt"));
    Instant second = Instant.parse(page0.jsonPath().getString("items[1].createdAt"));
    assertThat(first.truncatedTo(ChronoUnit.MICROS))
        .isAfterOrEqualTo(second.truncatedTo(ChronoUnit.MICROS));

    // page 1, size 2.
    given().header("Authorization", "Bearer " + tokenA)
        .get("/api/tickets/" + t1 + "/activity?page=1&size=2")
        .then().statusCode(200).body("items.size()", equalTo(2)).body("page", equalTo(1));

    // size clamped to 100.
    given().header("Authorization", "Bearer " + tokenA)
        .get("/api/tickets/" + t1 + "/activity?size=999")
        .then().statusCode(200).body("size", equalTo(100)).body("items.size()", equalTo(4));
  }

  // ───────────────────────── security ─────────────────────────

  @Test
  void nonMember_returns404() {
    given().header("Authorization", "Bearer " + tokenC).get("/api/tickets/" + t1 + "/activity")
        .then().statusCode(404);
  }

  @Test
  void nonExistentTicket_returns404() {
    given().header("Authorization", "Bearer " + tokenA)
        .get("/api/tickets/" + UUID.randomUUID() + "/activity")
        .then().statusCode(404);
  }

  @Test
  void unauthenticated_returns401() {
    given().get("/api/tickets/" + t1 + "/activity").then().statusCode(401);
  }

  // ───────────────────────── helpers ─────────────────────────

  private String createTicket(String body) {
    return given()
        .header("Authorization", "Bearer " + tokenA)
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post("/api/projects/" + engId + "/tickets")
        .then()
        .statusCode(201)
        .extract()
        .jsonPath()
        .getString("id");
  }

  private void transition(String ticketId, String toStatus) {
    given()
        .header("Authorization", "Bearer " + tokenA)
        .contentType(ContentType.JSON)
        .body("{\"toStatus\":\"" + toStatus + "\"}")
        .when()
        .post("/api/tickets/" + ticketId + "/transition")
        .then()
        .statusCode(200);
  }

  private void patch(String ticketId, String body) {
    given()
        .header("Authorization", "Bearer " + tokenA)
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .patch("/api/tickets/" + ticketId)
        .then()
        .statusCode(200);
  }

  private String createProject(String key, String name) {
    return given()
        .header("Authorization", "Bearer " + tokenA)
        .contentType(ContentType.JSON)
        .body("{\"key\":\"" + key + "\",\"name\":\"" + name + "\"}")
        .when()
        .post("/api/projects")
        .then()
        .statusCode(201)
        .extract()
        .jsonPath()
        .getString("id");
  }

  private UUID register(String email, String displayName) {
    String id =
        given()
            .contentType(ContentType.JSON)
            .body(
                "{\"email\":\""
                    + email
                    + "\",\"password\":\"Password123!\",\"displayName\":\""
                    + displayName
                    + "\"}")
            .when()
            .post("/api/auth/register")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("id");
    return UUID.fromString(id);
  }

  private static String sign(UUID subject) {
    try {
      JWTClaimsSet claims =
          new JWTClaimsSet.Builder()
              .subject(subject.toString())
              .expirationTime(Date.from(Instant.now().plusSeconds(900)))
              .build();
      SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
      jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
      return jwt.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }
}
