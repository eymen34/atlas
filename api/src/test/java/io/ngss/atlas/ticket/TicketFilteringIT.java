package io.ngss.atlas.ticket;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
 * Ticket list filtering + pagination (T-018 AC-3). Uses the REAL enum values
 * (TicketStatus TODO/IN_PROGRESS/IN_REVIEW/DONE; TicketPriority P0..P3) — NOT the
 * placeholder OPEN/CLOSED names. The headline test is the relational-division
 * AND-label filter (HAVING COUNT, not IN), including a near-miss ticket.
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {"BCRYPT_COST=12", "spring.jpa.hibernate.ddl-auto=validate"})
class TicketFilteringIT {

  private static final String SECRET = "ticketfilterit-secret-min-32-characters-ok!";

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
  private String p1;
  private String lA;
  private String lB;
  private String lC;

  @BeforeEach
  void setUp() {
    RestAssured.baseURI = "http://localhost";
    RestAssured.port = port;
    BaseIT.cleanDatabase(jdbc);

    userA = register("usera@example.com", "Alice");
    tokenA = sign(userA);
    p1 = createProject(tokenA, "ENG", "Engineering");
    lA = createLabelId("a");
    lB = createLabelId("b");
    lC = createLabelId("c");
  }

  @AfterEach
  void reset() {
    RestAssured.reset();
  }

  // ───────────────────────── AC-3.5: AND-semantics label filter (CRITICAL) ─────────────

  @Test
  void labelFilter_andSemantics_relationalDivision_withNearMiss() {
    String tAB = createTicket("AB");
    String tA = createTicket("A"); // near-miss: has only L_A
    String tBC = createTicket("BC");
    String tABC = createTicket("ABC");
    setLabels(tAB, lA, lB);
    setLabels(tA, lA);
    setLabels(tBC, lB, lC);
    setLabels(tABC, lA, lB, lC);

    // ?label=L_A&label=L_B → tickets carrying BOTH → {AB, ABC}. A (near-miss) and BC excluded.
    given()
        .header("Authorization", "Bearer " + tokenA)
        .get("/api/projects/" + p1 + "/tickets?label=" + lA + "&label=" + lB)
        .then()
        .statusCode(200)
        .body("total", equalTo(2))
        .body("items.size()", equalTo(2))
        .body("items.title", hasItems("AB", "ABC"))
        .body("items.title", not(hasItem("A")))
        .body("items.title", not(hasItem("BC")));
  }

  // ───────────────────────── AC-3.1: multi-valued status OR ─────────────────────────

  @Test
  void statusFilter_multiValued_or() {
    createTicket("todo"); // stays TODO
    String ip = createTicket("inprogress");
    transition(ip, "IN_PROGRESS");
    String done = createTicket("done");
    transition(done, "DONE");

    given()
        .header("Authorization", "Bearer " + tokenA)
        .get("/api/projects/" + p1 + "/tickets?status=TODO&status=IN_PROGRESS")
        .then()
        .statusCode(200)
        .body("total", equalTo(2))
        .body("items.title", hasItems("todo", "inprogress"))
        .body("items.title", not(hasItem("done")));
  }

  // ───────────────────────── priority OR ─────────────────────────

  @Test
  void priorityFilter_multiValued_or() {
    createTicketWith("crit", "P0", null);
    createTicketWith("high", "P1", null);
    createTicketWith("low", "P3", null);

    given()
        .header("Authorization", "Bearer " + tokenA)
        .get("/api/projects/" + p1 + "/tickets?priority=P0&priority=P1")
        .then()
        .statusCode(200)
        .body("total", equalTo(2))
        .body("items.title", hasItems("crit", "high"))
        .body("items.title", not(hasItem("low")));
  }

  // ───────────────────────── assignee: uuid + unassigned ─────────────────────────

  @Test
  void assigneeFilter_uuidAndUnassignedLiteral() {
    createTicketWith("mine", "P2", userA.toString());
    createTicket("free"); // unassigned

    given()
        .header("Authorization", "Bearer " + tokenA)
        .get("/api/projects/" + p1 + "/tickets?assigneeId=" + userA)
        .then()
        .statusCode(200)
        .body("total", equalTo(1))
        .body("items[0].title", equalTo("mine"));

    given()
        .header("Authorization", "Bearer " + tokenA)
        .get("/api/projects/" + p1 + "/tickets?assigneeId=unassigned")
        .then()
        .statusCode(200)
        .body("total", equalTo(1))
        .body("items[0].title", equalTo("free"));
  }

  @Test
  void assigneeFilter_garbageValue_returns400() {
    given()
        .header("Authorization", "Bearer " + tokenA)
        .get("/api/projects/" + p1 + "/tickets?assigneeId=not-a-uuid")
        .then()
        .statusCode(400);
  }

  @Test
  void invalidStatusEnum_returns400() {
    given()
        .header("Authorization", "Bearer " + tokenA)
        .get("/api/projects/" + p1 + "/tickets?status=BOGUS")
        .then()
        .statusCode(400);
  }

  // ───────────────────────── AC-3.6: paged envelope shape ─────────────────────────

  @Test
  void pagedEnvelope_hasItemsPageSizeTotal_andLabelIds() {
    for (int i = 0; i < 25; i++) {
      createTicket("t" + i);
    }
    given()
        .header("Authorization", "Bearer " + tokenA)
        .get("/api/projects/" + p1 + "/tickets?page=0&size=10")
        .then()
        .statusCode(200)
        .body("items.size()", equalTo(10))
        .body("page", equalTo(0))
        .body("size", equalTo(10))
        .body("total", equalTo(25))
        .body("items[0].labelIds", notNullValue());
  }

  @Test
  void sizeAboveHundred_isClampedTo100() {
    createTicket("only");
    given()
        .header("Authorization", "Bearer " + tokenA)
        .get("/api/projects/" + p1 + "/tickets?size=500")
        .then()
        .statusCode(200)
        .body("size", equalTo(100))
        .body("items.size()", equalTo(1))
        .body("total", equalTo(1));
  }

  // ───────────────────────── combined filters ─────────────────────────

  @Test
  void combinedStatusAndLabelFilter() {
    String tMatch = createTicket("match");
    transition(tMatch, "IN_PROGRESS");
    setLabels(tMatch, lA);
    String tWrongStatus = createTicket("wrong-status"); // TODO + L_A
    setLabels(tWrongStatus, lA);
    String tWrongLabel = createTicket("wrong-label"); // IN_PROGRESS, no L_A
    transition(tWrongLabel, "IN_PROGRESS");

    given()
        .header("Authorization", "Bearer " + tokenA)
        .get("/api/projects/" + p1 + "/tickets?status=IN_PROGRESS&label=" + lA)
        .then()
        .statusCode(200)
        .body("total", equalTo(1))
        .body("items[0].title", equalTo("match"));
  }

  // ───────────────────────── helpers ─────────────────────────

  private String createTicket(String title) {
    return createTicketWith(title, null, null);
  }

  private String createTicketWith(String title, String priority, String assigneeId) {
    StringBuilder body = new StringBuilder("{\"title\":\"").append(title).append("\"");
    if (priority != null) {
      body.append(",\"priority\":\"").append(priority).append("\"");
    }
    if (assigneeId != null) {
      body.append(",\"assigneeId\":\"").append(assigneeId).append("\"");
    }
    body.append("}");
    return given()
        .header("Authorization", "Bearer " + tokenA)
        .contentType(ContentType.JSON)
        .body(body.toString())
        .when()
        .post("/api/projects/" + p1 + "/tickets")
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

  private void setLabels(String ticketId, String... labelIds) {
    StringBuilder body = new StringBuilder("{\"labelIds\":[");
    for (int i = 0; i < labelIds.length; i++) {
      body.append(i == 0 ? "" : ",").append("\"").append(labelIds[i]).append("\"");
    }
    body.append("]}");
    given()
        .header("Authorization", "Bearer " + tokenA)
        .contentType(ContentType.JSON)
        .body(body.toString())
        .when()
        .put("/api/tickets/" + ticketId + "/labels")
        .then()
        .statusCode(200);
  }

  private String createLabelId(String name) {
    return given()
        .header("Authorization", "Bearer " + tokenA)
        .contentType(ContentType.JSON)
        .body("{\"name\":\"" + name + "\"}")
        .when()
        .post("/api/projects/" + p1 + "/labels")
        .then()
        .statusCode(201)
        .extract()
        .jsonPath()
        .getString("id");
  }

  private String createProject(String token, String key, String name) {
    return given()
        .header("Authorization", "Bearer " + token)
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
