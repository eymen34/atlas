package io.ngss.atlas.ticket;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;

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
 * PUT /api/tickets/{id}/labels coverage (T-018 AC-2): full idempotent replace,
 * empty-clears, cross-project rejection (400), and non-member 404.
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {"BCRYPT_COST=12", "spring.jpa.hibernate.ddl-auto=validate"})
class TicketLabelAssociationIT {

  private static final String SECRET = "ticketlabelit-secret-min-32-characters-ok!!";

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

  private String tokenA; // ADMIN of P1 and P2
  private String tokenB; // MEMBER of P1 only
  private String tokenC; // stranger
  private String p1;
  private String p2;
  private String t1; // ticket in P1
  private String l1;
  private String l2;
  private String lOld;
  private String lOther; // label in P2

  @BeforeEach
  void setUp() {
    RestAssured.baseURI = "http://localhost";
    RestAssured.port = port;
    BaseIT.cleanDatabase(jdbc);

    UUID userA = register("usera@example.com", "Alice");
    UUID userB = register("userb@example.com", "Bob");
    UUID userC = register("userc@example.com", "Carol");
    tokenA = sign(userA);
    tokenB = sign(userB);
    tokenC = sign(userC);

    p1 = createProject(tokenA, "ENG", "Engineering");
    p2 = createProject(tokenA, "OPS", "Operations");
    addMember(tokenA, p1, "userb@example.com", "MEMBER");

    l1 = createLabelId(tokenA, p1, "l1");
    l2 = createLabelId(tokenA, p1, "l2");
    lOld = createLabelId(tokenA, p1, "old");
    lOther = createLabelId(tokenA, p2, "other");
    t1 = createTicketId(tokenA, p1, "Ticket-1");
  }

  @AfterEach
  void reset() {
    RestAssured.reset();
  }

  // ───────────────────────── AC-2.1: full replace + idempotency ─────────────────────────

  @Test
  void put_replacesLabels_andIsIdempotent() {
    setLabels(tokenA, t1, l1, l2);

    // Re-assign to a different set; the old label is dropped.
    setLabels(tokenA, t1, lOld);
    setLabels(tokenA, t1, l1, l2)
        .then()
        .statusCode(200)
        .body("labelIds.size()", equalTo(2))
        .body("labelIds", hasItems(l1, l2));
    assertThat(ticketLabelCount(t1)).isEqualTo(2);

    // PUT the same payload again — idempotent, no UNIQUE violation, still 2 rows.
    setLabels(tokenA, t1, l1, l2).then().statusCode(200).body("labelIds.size()", equalTo(2));
    assertThat(ticketLabelCount(t1)).isEqualTo(2);

    // Duplicate ids in the request are de-duplicated.
    setLabels(tokenA, t1, l1, l1).then().statusCode(200).body("labelIds.size()", equalTo(1));
    assertThat(ticketLabelCount(t1)).isEqualTo(1);
  }

  @Test
  void put_emptyList_clearsAllLabels() {
    setLabels(tokenA, t1, l1, l2);
    assertThat(ticketLabelCount(t1)).isEqualTo(2);

    given().header("Authorization", "Bearer " + tokenA).contentType(ContentType.JSON)
        .body("{\"labelIds\":[]}").put("/api/tickets/" + t1 + "/labels")
        .then().statusCode(200).body("labelIds.size()", equalTo(0));
    assertThat(ticketLabelCount(t1)).isZero();
  }

  @Test
  void put_nullLabelIds_returns400() {
    given().header("Authorization", "Bearer " + tokenA).contentType(ContentType.JSON)
        .body("{\"labelIds\":null}").put("/api/tickets/" + t1 + "/labels")
        .then().statusCode(400);
  }

  // ───────────────────────── AC-2.3: cross-project guard ─────────────────────────

  @Test
  void put_crossProjectLabel_returns400_andDoesNotMutate() {
    setLabels(tokenA, t1, l1); // start with one label
    // l_other belongs to P2; attaching it to a P1 ticket is rejected.
    given().header("Authorization", "Bearer " + tokenB).contentType(ContentType.JSON)
        .body("{\"labelIds\":[\"" + lOther + "\"]}").put("/api/tickets/" + t1 + "/labels")
        .then().statusCode(400);
    // Unchanged: still exactly the one original label.
    assertThat(ticketLabelCount(t1)).isEqualTo(1);
  }

  @Test
  void put_nonExistentLabel_returns400() {
    given().header("Authorization", "Bearer " + tokenA).contentType(ContentType.JSON)
        .body("{\"labelIds\":[\"" + UUID.randomUUID() + "\"]}").put("/api/tickets/" + t1 + "/labels")
        .then().statusCode(400);
  }

  // ───────────────────────── non-member ─────────────────────────

  @Test
  void put_byNonMember_returns404() {
    given().header("Authorization", "Bearer " + tokenC).contentType(ContentType.JSON)
        .body("{\"labelIds\":[\"" + l1 + "\"]}").put("/api/tickets/" + t1 + "/labels")
        .then().statusCode(404);
  }

  // ───────────────────────── helpers ─────────────────────────

  private Response setLabels(String token, String ticketId, String... labelIds) {
    StringBuilder body = new StringBuilder("{\"labelIds\":[");
    for (int i = 0; i < labelIds.length; i++) {
      body.append(i == 0 ? "" : ",").append("\"").append(labelIds[i]).append("\"");
    }
    body.append("]}");
    return given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body(body.toString())
        .when()
        .put("/api/tickets/" + ticketId + "/labels");
  }

  private int ticketLabelCount(String ticketId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM ticket_labels WHERE ticket_id=?::uuid", Integer.class, ticketId);
    return n == null ? 0 : n;
  }

  private String createLabelId(String token, String projectId, String name) {
    return given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body("{\"name\":\"" + name + "\"}")
        .when()
        .post("/api/projects/" + projectId + "/labels")
        .then()
        .statusCode(201)
        .extract()
        .jsonPath()
        .getString("id");
  }

  private String createTicketId(String token, String projectId, String title) {
    return given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body("{\"title\":\"" + title + "\"}")
        .when()
        .post("/api/projects/" + projectId + "/tickets")
        .then()
        .statusCode(201)
        .extract()
        .jsonPath()
        .getString("id");
  }

  private void addMember(String adminToken, String projectId, String email, String role) {
    given()
        .header("Authorization", "Bearer " + adminToken)
        .contentType(ContentType.JSON)
        .body("{\"email\":\"" + email + "\",\"role\":\"" + role + "\"}")
        .when()
        .post("/api/projects/" + projectId + "/members")
        .then()
        .statusCode(201);
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
