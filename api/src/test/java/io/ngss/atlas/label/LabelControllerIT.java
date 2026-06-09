package io.ngss.atlas.label;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
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
 * Label CRUD coverage (T-018 AC-1): create/list/update/delete, case-insensitive
 * duplicate 409, invalid-color 400, admin-vs-member DELETE, delete-cascades-
 * associations, and the SQL-injection-as-literal guarantee.
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {"BCRYPT_COST=12", "spring.jpa.hibernate.ddl-auto=validate"})
class LabelControllerIT {

  private static final String SECRET = "labelit-secret-min-32-characters-long-okay!!";

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

  private UUID userA; // ADMIN of ENG
  private UUID userB; // MEMBER of ENG
  private UUID userC; // stranger
  private String tokenA;
  private String tokenB;
  private String tokenC;
  private String engId;

  @BeforeEach
  void setUp() {
    RestAssured.baseURI = "http://localhost";
    RestAssured.port = port;
    BaseIT.cleanDatabase(jdbc);

    userA = register("usera@example.com", "Alice");
    userB = register("userb@example.com", "Bob");
    userC = register("userc@example.com", "Carol");
    tokenA = sign(userA);
    tokenB = sign(userB);
    tokenC = sign(userC);
    engId = createProject(tokenA, "ENG", "Engineering");
    addMember(tokenA, engId, "userb@example.com", "MEMBER");
  }

  @AfterEach
  void reset() {
    RestAssured.reset();
  }

  // ───────────────────────── AC-1: create / validation ─────────────────────────

  @Test
  void create_returns201_withFields() {
    createLabel(tokenA, engId, "{\"name\":\"Backend\",\"color\":\"#1A2B3C\"}")
        .then()
        .statusCode(201)
        .header("Location", notNullValue())
        .body("id", notNullValue())
        .body("projectId", equalTo(engId))
        .body("name", equalTo("Backend"))
        .body("color", equalTo("#1A2B3C"))
        .body("createdAt", notNullValue());
  }

  @Test
  void create_duplicateName_isCaseInsensitive_returns409() {
    createLabel(tokenA, engId, "{\"name\":\"Backend\"}").then().statusCode(201);
    createLabel(tokenA, engId, "{\"name\":\"backend\"}").then().statusCode(409);
    createLabel(tokenA, engId, "{\"name\":\"BACKEND\"}").then().statusCode(409);

    Integer rows =
        jdbc.queryForObject(
            "SELECT count(*) FROM labels WHERE project_id=?::uuid AND lower(name)='backend'",
            Integer.class,
            engId);
    assertThat(rows).isEqualTo(1);
  }

  @Test
  void create_sameNameDifferentProject_isAllowed() {
    String opsId = createProject(tokenA, "OPS", "Operations");
    createLabel(tokenA, engId, "{\"name\":\"bug\"}").then().statusCode(201);
    createLabel(tokenA, opsId, "{\"name\":\"bug\"}").then().statusCode(201);
  }

  @Test
  void create_invalidHexColor_returns400() {
    createLabel(tokenA, engId, "{\"name\":\"X\",\"color\":\"red\"}").then().statusCode(400);
    createLabel(tokenA, engId, "{\"name\":\"Y\",\"color\":\"#12345\"}").then().statusCode(400);
  }

  @Test
  void create_blankName_returns400() {
    createLabel(tokenA, engId, "{\"name\":\"   \"}").then().statusCode(400);
  }

  @Test
  void create_byNonMember_returns404() {
    createLabel(tokenC, engId, "{\"name\":\"Sneaky\"}").then().statusCode(404);
  }

  // ───────────────────────── list ─────────────────────────

  @Test
  void list_returnsProjectLabelsSortedByName_membersOnly() {
    createLabel(tokenA, engId, "{\"name\":\"zeta\"}").then().statusCode(201);
    createLabel(tokenA, engId, "{\"name\":\"alpha\"}").then().statusCode(201);

    given().header("Authorization", "Bearer " + tokenA).get("/api/projects/" + engId + "/labels")
        .then().statusCode(200).body("size()", equalTo(2))
        .body("[0].name", equalTo("alpha")).body("[1].name", equalTo("zeta"));

    // Stranger → 404.
    given().header("Authorization", "Bearer " + tokenC).get("/api/projects/" + engId + "/labels")
        .then().statusCode(404);
  }

  // ───────────────────────── update ─────────────────────────

  @Test
  void patch_updatesNameAndColor_returns200() {
    String id = createLabelId(tokenA, engId, "{\"name\":\"Old\",\"color\":\"#000000\"}");
    given().header("Authorization", "Bearer " + tokenB).contentType(ContentType.JSON)
        .body("{\"name\":\"New\",\"color\":\"#FFFFFF\"}").patch("/api/labels/" + id)
        .then().statusCode(200).body("name", equalTo("New")).body("color", equalTo("#FFFFFF"));
  }

  @Test
  void patch_bothFieldsNull_returns400() {
    String id = createLabelId(tokenA, engId, "{\"name\":\"Solo\"}");
    given().header("Authorization", "Bearer " + tokenA).contentType(ContentType.JSON)
        .body("{}").patch("/api/labels/" + id).then().statusCode(400);
  }

  @Test
  void patch_byNonMember_returns404() {
    String id = createLabelId(tokenA, engId, "{\"name\":\"Owned\"}");
    given().header("Authorization", "Bearer " + tokenC).contentType(ContentType.JSON)
        .body("{\"name\":\"Hijack\"}").patch("/api/labels/" + id).then().statusCode(404);
  }

  // ───────────────────────── delete (admin) + cascade ─────────────────────────

  @Test
  void delete_byMemberNonAdmin_403_byAdmin_204() {
    String id = createLabelId(tokenA, engId, "{\"name\":\"Doomed\"}");
    given().header("Authorization", "Bearer " + tokenB).delete("/api/labels/" + id).then().statusCode(403);
    given().header("Authorization", "Bearer " + tokenC).delete("/api/labels/" + id).then().statusCode(404);
    given().header("Authorization", "Bearer " + tokenA).delete("/api/labels/" + id).then().statusCode(204);

    Integer rows =
        jdbc.queryForObject("SELECT count(*) FROM labels WHERE id=?::uuid", Integer.class, id);
    assertThat(rows).isZero();
  }

  @Test
  void delete_cascadesTicketLabels() {
    String labelId = createLabelId(tokenA, engId, "{\"name\":\"Flaky\"}");
    String ticketId = createTicketId(tokenA, engId, "Has-label");
    // Attach the label to the ticket.
    given().header("Authorization", "Bearer " + tokenA).contentType(ContentType.JSON)
        .body("{\"labelIds\":[\"" + labelId + "\"]}").put("/api/tickets/" + ticketId + "/labels")
        .then().statusCode(200);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ticket_labels WHERE label_id=?::uuid", Integer.class, labelId))
        .isEqualTo(1);

    // Admin deletes the label → association rows are removed too (delete ordering).
    given().header("Authorization", "Bearer " + tokenA).delete("/api/labels/" + labelId).then().statusCode(204);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ticket_labels WHERE label_id=?::uuid", Integer.class, labelId))
        .isZero();
  }

  // ───────────────────────── SEC-5: injection treated as a literal ─────────────────────────

  @Test
  void create_labelNameWithSqlPayload_isStoredAsLiteral() {
    createLabel(tokenA, engId, "{\"name\":\"'; DROP TABLE labels; --\"}").then().statusCode(201);
    // The labels table still exists (this query would fail otherwise) and holds the literal.
    Integer rows =
        jdbc.queryForObject(
            "SELECT count(*) FROM labels WHERE name = ?", Integer.class, "'; DROP TABLE labels; --");
    assertThat(rows).isEqualTo(1);
  }

  // ───────────────────────── helpers ─────────────────────────

  private Response createLabel(String token, String projectId, String body) {
    return given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post("/api/projects/" + projectId + "/labels");
  }

  private String createLabelId(String token, String projectId, String body) {
    return createLabel(token, projectId, body)
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
