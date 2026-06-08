package io.ngss.atlas.project;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.ngss.atlas.Application;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.persistence.EntityManagerFactory;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
 * End-to-end Project CRUD coverage (T-014; authorization widened to membership in
 * T-015). Two users exercise the membership model: a non-member sees 404
 * (existence-leak prevention), a member sees 200 on reads but 403 on admin-only
 * mutations, and the creator is auto-seeded as the first ADMIN.
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(
    properties = {
      "BCRYPT_COST=12",
      "spring.jpa.hibernate.ddl-auto=validate",
      // PERF-1: needed by list_issuesExactlyOneQuery_noNPlusOne.
      "spring.jpa.properties.hibernate.generate_statistics=true"
    })
class ProjectControllerIT {

  private static final String SECRET = "projectit-secret-min-32-characters-long-okay";

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
  @Autowired EntityManagerFactory entityManagerFactory;

  private UUID userA;
  private UUID userB;
  private String tokenA;
  private String tokenB;

  @BeforeEach
  void setUp() {
    RestAssured.baseURI = "http://localhost";
    RestAssured.port = port;
    // FK-ordered teardown (child → parent): tickets + counters reference projects.
    jdbc.update("DELETE FROM tickets");
    jdbc.update("DELETE FROM project_ticket_counters");
    jdbc.update("DELETE FROM project_members");
    jdbc.update("DELETE FROM projects");
    jdbc.update("DELETE FROM refresh_tokens");
    jdbc.update("DELETE FROM password_credentials");
    jdbc.update("DELETE FROM users");
    userA = register("usera@example.com", "Alice");
    userB = register("userb@example.com", "Bob");
    tokenA = sign(userA.toString(), Instant.now().plusSeconds(900));
    tokenB = sign(userB.toString(), Instant.now().plusSeconds(900));
  }

  @AfterEach
  void reset() {
    RestAssured.reset();
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

  private static String sign(String subject, Instant exp) {
    try {
      JWTClaimsSet claims =
          new JWTClaimsSet.Builder().subject(subject).expirationTime(Date.from(exp)).build();
      SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
      jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
      return jwt.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  private Response createProject(String token, String body) {
    return given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post("/api/projects");
  }

  private String createProjectId(String token, String key, String name) {
    return createProject(token, "{\"key\":\"" + key + "\",\"name\":\"" + name + "\"}")
        .then()
        .statusCode(201)
        .extract()
        .jsonPath()
        .getString("id");
  }

  // ───────────────────────── AC-1: create + service timestamps ─────────────────────────

  @Test
  void create_returns201WithServiceStampedTimestamps() {
    Response resp =
        createProject(tokenA, "{\"key\":\"ALPHA\",\"name\":\"Alpha\",\"description\":\"First\"}");
    String id =
        resp.then()
            .statusCode(201)
            .header("Location", notNullValue())
            .body("id", notNullValue())
            .body("key", equalTo("ALPHA"))
            .body("name", equalTo("Alpha"))
            .body("createdBy", equalTo(userA.toString()))
            .body("createdAt", notNullValue())
            .body("updatedAt", notNullValue())
            // T-016: creator is ADMIN of a one-member project at creation time.
            .body("callerRole", equalTo("ADMIN"))
            .body("memberCount", equalTo(1))
            .extract()
            .jsonPath()
            .getString("id");

    String createdAt = resp.jsonPath().getString("createdAt");
    String updatedAt = resp.jsonPath().getString("updatedAt");
    assertThat(Instant.parse(createdAt)).isEqualTo(Instant.parse(updatedAt));

    Instant dbCreated =
        jdbc.queryForObject(
            "SELECT created_at FROM projects WHERE id = ?::uuid", Instant.class, id);
    // DB timestamptz truncates to microseconds; the response carries the original
    // in-memory Instant, so compare within a small tolerance rather than exact.
    assertThat(dbCreated).isCloseTo(Instant.parse(createdAt), within(1, ChronoUnit.MILLIS));
  }

  @Test
  void create_withoutDescription_storesNull() {
    createProject(tokenA, "{\"key\":\"NODESC\",\"name\":\"No Desc\"}")
        .then()
        .statusCode(201)
        .body("description", nullValue());
  }

  // ───────────────────────── AC-2: key validation + uniqueness ─────────────────────────

  @ParameterizedTest
  @ValueSource(strings = {"a", "abc", "A", "AAAAAAAAAAA", "1ABC", "AB-CD", "AB CD", "ab1"})
  void create_invalidKey_returns400(String key) {
    createProject(tokenA, "{\"key\":\"" + key + "\",\"name\":\"Test\"}")
        .then()
        .statusCode(400)
        .body("status", equalTo(400))
        .body("path", equalTo("/api/projects"));
  }

  @Test
  void create_duplicateLiveKey_returns409() {
    createProjectId(tokenA, "DUPL", "First");
    // Same key, even by a different user, conflicts while the first is live.
    createProject(tokenB, "{\"key\":\"DUPL\",\"name\":\"Second\"}")
        .then()
        .statusCode(409)
        .body("status", equalTo(409));
    Integer live =
        jdbc.queryForObject(
            "SELECT count(*) FROM projects WHERE key='DUPL' AND deleted_at IS NULL", Integer.class);
    assertThat(live).isEqualTo(1);
  }

  @Test
  void create_softDeletedKey_canBeReused() {
    String firstId = createProjectId(tokenA, "REUSE", "First");
    given().header("Authorization", "Bearer " + tokenA).delete("/api/projects/" + firstId).then().statusCode(204);

    String secondId = createProjectId(tokenA, "REUSE", "Second");
    assertThat(secondId).isNotEqualTo(firstId);
    Integer rows = jdbc.queryForObject("SELECT count(*) FROM projects WHERE key='REUSE'", Integer.class);
    assertThat(rows).isEqualTo(2);
  }

  @Test
  void create_lengthLimits() {
    String name201 = "x".repeat(201);
    createProject(tokenA, "{\"key\":\"LENA\",\"name\":\"" + name201 + "\"}")
        .then()
        .statusCode(400);

    String desc1001 = "y".repeat(1001);
    createProject(tokenA, "{\"key\":\"LENB\",\"name\":\"ok\",\"description\":\"" + desc1001 + "\"}")
        .then()
        .statusCode(400);

    String name200 = "x".repeat(200);
    String desc1000 = "y".repeat(1000);
    createProject(
            tokenA,
            "{\"key\":\"LENC\",\"name\":\"" + name200 + "\",\"description\":\"" + desc1000 + "\"}")
        .then()
        .statusCode(201);
  }

  // ───────────────────────── AC-3: list scoped to caller ─────────────────────────

  @Test
  void list_returnsOnlyCallersLiveProjects() {
    String a1 = createProjectId(tokenA, "AONE", "A1");
    createProjectId(tokenA, "ATWO", "A2");
    createProjectId(tokenB, "BONE", "B1");
    // A soft-deletes A1 → excluded from A's list.
    given().header("Authorization", "Bearer " + tokenA).delete("/api/projects/" + a1).then().statusCode(204);

    given()
        .header("Authorization", "Bearer " + tokenA)
        .when()
        .get("/api/projects")
        .then()
        .statusCode(200)
        .body("size()", equalTo(1))
        .body("[0].key", equalTo("ATWO"))
        .body("[0].createdBy", equalTo(userA.toString()));

    given()
        .header("Authorization", "Bearer " + tokenB)
        .when()
        .get("/api/projects")
        .then()
        .statusCode(200)
        .body("size()", equalTo(1))
        .body("[0].key", equalTo("BONE"));
  }

  // ───────────────────────── AC-4: ownership + PATCH/DELETE ─────────────────────────

  @Test
  void patch_byNonCreator_returns404_andDoesNotMutate() {
    String id = createProjectId(tokenA, "OWNED", "Owned");
    given()
        .header("Authorization", "Bearer " + tokenB)
        .contentType(ContentType.JSON)
        .body("{\"name\":\"Hijack\"}")
        .when()
        .patch("/api/projects/" + id)
        .then()
        .statusCode(404)
        .body("status", equalTo(404));
    String name = jdbc.queryForObject("SELECT name FROM projects WHERE id=?::uuid", String.class, id);
    assertThat(name).isEqualTo("Owned");
  }

  @Test
  void delete_byNonCreator_returns404_andDoesNotDelete() {
    String id = createProjectId(tokenA, "OWNED2", "Owned");
    given()
        .header("Authorization", "Bearer " + tokenB)
        .when()
        .delete("/api/projects/" + id)
        .then()
        .statusCode(404);
    Instant deletedAt =
        jdbc.queryForObject("SELECT deleted_at FROM projects WHERE id=?::uuid", Instant.class, id);
    assertThat(deletedAt).isNull();
  }

  @Test
  void patch_byCreator_advancesUpdatedAt_keepsCreatedAt() throws InterruptedException {
    Response created = createProject(tokenA, "{\"key\":\"UPD\",\"name\":\"Before\"}");
    String id = created.then().statusCode(201).extract().jsonPath().getString("id");
    String createdAt = created.jsonPath().getString("createdAt");
    Thread.sleep(50);

    Response patched =
        given()
            .header("Authorization", "Bearer " + tokenA)
            .contentType(ContentType.JSON)
            .body("{\"name\":\"After\"}")
            .when()
            .patch("/api/projects/" + id);
    patched.then().statusCode(200).body("name", equalTo("After"));
    // createdAt is preserved (compare as instants — DB re-read is micro-truncated).
    assertThat(Instant.parse(patched.jsonPath().getString("createdAt")))
        .isCloseTo(Instant.parse(createdAt), within(1, ChronoUnit.MILLIS));
    assertThat(Instant.parse(patched.jsonPath().getString("updatedAt")))
        .isAfter(Instant.parse(createdAt));
  }

  @Test
  void patch_emptyBody_keepsFieldsButAdvancesUpdatedAt() throws InterruptedException {
    Response created = createProject(tokenA, "{\"key\":\"NOOP\",\"name\":\"Same\",\"description\":\"d\"}");
    String id = created.then().statusCode(201).extract().jsonPath().getString("id");
    String createdAt = created.jsonPath().getString("createdAt");
    Thread.sleep(50);

    Response patched =
        given()
            .header("Authorization", "Bearer " + tokenA)
            .contentType(ContentType.JSON)
            .body("{}")
            .when()
            .patch("/api/projects/" + id);
    patched
        .then()
        .statusCode(200)
        .body("name", equalTo("Same"))
        .body("description", equalTo("d"));
    assertThat(Instant.parse(patched.jsonPath().getString("updatedAt")))
        .isAfter(Instant.parse(createdAt));
  }

  @Test
  void patch_blankName_returns400() {
    String id = createProjectId(tokenA, "BLANK", "Name");
    given()
        .header("Authorization", "Bearer " + tokenA)
        .contentType(ContentType.JSON)
        .body("{\"name\":\"   \"}")
        .when()
        .patch("/api/projects/" + id)
        .then()
        .statusCode(400);
  }

  @Test
  void patch_emptyDescription_clearsIt() {
    String id =
        createProject(tokenA, "{\"key\":\"CLR\",\"name\":\"n\",\"description\":\"has desc\"}")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("id");
    given()
        .header("Authorization", "Bearer " + tokenA)
        .contentType(ContentType.JSON)
        .body("{\"description\":\"\"}")
        .when()
        .patch("/api/projects/" + id)
        .then()
        .statusCode(200)
        .body("name", equalTo("n"))
        .body("description", equalTo(""));
  }

  @Test
  void delete_byCreator_softDeletes_thenGone() {
    String id = createProjectId(tokenA, "DEL", "Del");
    given().header("Authorization", "Bearer " + tokenA).delete("/api/projects/" + id).then().statusCode(204);

    Instant deletedAt =
        jdbc.queryForObject("SELECT deleted_at FROM projects WHERE id=?::uuid", Instant.class, id);
    assertThat(deletedAt).isNotNull();

    given()
        .header("Authorization", "Bearer " + tokenA)
        .when()
        .get("/api/projects/" + id)
        .then()
        .statusCode(404);
  }

  @Test
  void delete_alreadyDeleted_returns404() {
    String id = createProjectId(tokenA, "DBL", "Del");
    given().header("Authorization", "Bearer " + tokenA).delete("/api/projects/" + id).then().statusCode(204);
    given()
        .header("Authorization", "Bearer " + tokenA)
        .when()
        .delete("/api/projects/" + id)
        .then()
        .statusCode(404);
  }

  // ───────────────────────── GET by id/key + edge cases ─────────────────────────

  @Test
  void get_byIdAndByKey_bothResolve() {
    String id = createProjectId(tokenA, "FIND", "Findable");

    given()
        .header("Authorization", "Bearer " + tokenA)
        .when()
        .get("/api/projects/" + id)
        .then()
        .statusCode(200)
        .body("key", equalTo("FIND"));

    given()
        .header("Authorization", "Bearer " + tokenA)
        .when()
        .get("/api/projects/FIND")
        .then()
        .statusCode(200)
        .body("id", equalTo(id));
  }

  @Test
  void get_byNonCreator_returns404() {
    String id = createProjectId(tokenA, "SECRET", "Secret");
    given()
        .header("Authorization", "Bearer " + tokenB)
        .when()
        .get("/api/projects/" + id)
        .then()
        .statusCode(404);
  }

  @ParameterizedTest
  @ValueSource(strings = {"00000000-0000-0000-0000-000000000099", "GHOST", "not-a-uuid-or-key"})
  void get_missing_returns404(String segment) {
    given()
        .header("Authorization", "Bearer " + tokenA)
        .when()
        .get("/api/projects/" + segment)
        .then()
        .statusCode(404);
  }

  @Test
  void patchDelete_nonUuidSegment_return400() {
    given()
        .header("Authorization", "Bearer " + tokenA)
        .contentType(ContentType.JSON)
        .body("{\"name\":\"X\"}")
        .when()
        .patch("/api/projects/ACME")
        .then()
        .statusCode(400)
        .body("status", equalTo(400));

    given()
        .header("Authorization", "Bearer " + tokenA)
        .when()
        .delete("/api/projects/ACME")
        .then()
        .statusCode(400)
        .body("status", equalTo(400));
  }

  // ───────────────────────── security ─────────────────────────

  @Test
  void unauthenticated_returns401() {
    given().when().get("/api/projects").then().statusCode(401);
    given()
        .contentType(ContentType.JSON)
        .body("{\"key\":\"NOPE\",\"name\":\"x\"}")
        .when()
        .post("/api/projects")
        .then()
        .statusCode(401);
    given().when().get("/api/projects/anything").then().statusCode(401);
  }

  @Test
  void nonCreator_mutations_leakNoProjectDetail() {
    String id = createProjectId(tokenA, "LEAK", "Confidential Name");

    String getBody =
        given().header("Authorization", "Bearer " + tokenB).when().get("/api/projects/" + id).asString();
    assertThat(getBody).doesNotContain("Confidential Name").doesNotContain("LEAK");
  }

  // ───────────────────────── T-015 membership ─────────────────────────

  @Test
  void create_autoSeedsCreatorAsAdminMember() {
    String id = createProjectId(tokenA, "SEED", "Seeded");
    given()
        .header("Authorization", "Bearer " + tokenA)
        .when()
        .get("/api/projects/" + id + "/members")
        .then()
        .statusCode(200)
        .body("size()", equalTo(1))
        .body("[0].userId", equalTo(userA.toString()))
        .body("[0].role", equalTo("ADMIN"))
        .body("[0].invitedBy", nullValue());
  }

  @Test
  void member_seesProjectInListAndById_butCannotMutate() {
    String id = createProjectId(tokenA, "SHARED", "Shared");
    // Admin (A) adds B as a MEMBER.
    given()
        .header("Authorization", "Bearer " + tokenA)
        .contentType(ContentType.JSON)
        .body("{\"email\":\"userb@example.com\",\"role\":\"MEMBER\"}")
        .when()
        .post("/api/projects/" + id + "/members")
        .then()
        .statusCode(201);

    // B now sees it in their list and by id (REG-6).
    given()
        .header("Authorization", "Bearer " + tokenB)
        .when()
        .get("/api/projects")
        .then()
        .statusCode(200)
        .body("key", hasItem("SHARED"));
    given()
        .header("Authorization", "Bearer " + tokenB)
        .when()
        .get("/api/projects/" + id)
        .then()
        .statusCode(200)
        .body("key", equalTo("SHARED"));

    // But B (MEMBER, not ADMIN) cannot mutate the project (REG-9).
    given()
        .header("Authorization", "Bearer " + tokenB)
        .contentType(ContentType.JSON)
        .body("{\"name\":\"Renamed by member\"}")
        .when()
        .patch("/api/projects/" + id)
        .then()
        .statusCode(403)
        .body("status", equalTo(403));
    given()
        .header("Authorization", "Bearer " + tokenB)
        .when()
        .delete("/api/projects/" + id)
        .then()
        .statusCode(403)
        .body("status", equalTo(403));
  }

  // ───────────────────────── T-016 callerRole + memberCount ─────────────────────────

  @Test
  void callerRoleAndMemberCount_reflectMembershipForAdminAndMember() {
    String id = createProjectId(tokenA, "ROLES", "Roles");

    // Solo project: creator sees ADMIN + a single member, on both detail and list.
    given()
        .header("Authorization", "Bearer " + tokenA)
        .when()
        .get("/api/projects/" + id)
        .then()
        .statusCode(200)
        .body("callerRole", equalTo("ADMIN"))
        .body("memberCount", equalTo(1));
    given()
        .header("Authorization", "Bearer " + tokenA)
        .when()
        .get("/api/projects")
        .then()
        .statusCode(200)
        .body("find { it.key == 'ROLES' }.callerRole", equalTo("ADMIN"))
        .body("find { it.key == 'ROLES' }.memberCount", equalTo(1));

    // A adds B as a MEMBER → memberCount becomes 2 for everyone.
    given()
        .header("Authorization", "Bearer " + tokenA)
        .contentType(ContentType.JSON)
        .body("{\"email\":\"userb@example.com\",\"role\":\"MEMBER\"}")
        .when()
        .post("/api/projects/" + id + "/members")
        .then()
        .statusCode(201);

    given()
        .header("Authorization", "Bearer " + tokenA)
        .when()
        .get("/api/projects/" + id)
        .then()
        .statusCode(200)
        .body("callerRole", equalTo("ADMIN"))
        .body("memberCount", equalTo(2));

    // B is a MEMBER and sees the same count, on both detail and list.
    given()
        .header("Authorization", "Bearer " + tokenB)
        .when()
        .get("/api/projects/" + id)
        .then()
        .statusCode(200)
        .body("callerRole", equalTo("MEMBER"))
        .body("memberCount", equalTo(2));
    given()
        .header("Authorization", "Bearer " + tokenB)
        .when()
        .get("/api/projects")
        .then()
        .statusCode(200)
        .body("find { it.key == 'ROLES' }.callerRole", equalTo("MEMBER"))
        .body("find { it.key == 'ROLES' }.memberCount", equalTo(2));
  }

  /**
   * PERF-1 quality gate: the listing must NOT issue per-project queries. The
   * correlated COUNT is inlined into a single statement and the JWT filter never
   * touches the DB, so GET /api/projects is exactly one prepared statement
   * regardless of how many projects the caller owns. An N+1 regression would
   * surface here as 51 instead of 1.
   */
  @Test
  void list_issuesExactlyOneQuery_noNPlusOne() {
    Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    for (int i = 0; i < 50; i++) {
      createProjectId(tokenA, "PERF" + i, "Perf " + i);
    }

    stats.clear();
    given()
        .header("Authorization", "Bearer " + tokenA)
        .when()
        .get("/api/projects")
        .then()
        .statusCode(200)
        .body("size()", equalTo(50));

    assertThat(stats.getPrepareStatementCount())
        .as("GET /api/projects must be a single SQL statement (no N+1)")
        .isEqualTo(1L);
  }
}
