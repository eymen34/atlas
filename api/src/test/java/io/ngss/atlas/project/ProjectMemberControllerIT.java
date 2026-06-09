package io.ngss.atlas.project;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.equalToIgnoringCase;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

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

/** Three-actor (admin/member/stranger) coverage of the member endpoints (T-015). */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {"BCRYPT_COST=12", "spring.jpa.hibernate.ddl-auto=validate"})
class ProjectMemberControllerIT {

  private static final String SECRET = "memberit-secret-min-32-characters-long-okay!";

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

  private UUID adminId;
  private UUID memberId;
  private UUID strangerId;
  private UUID thirdId;
  private String adminToken;
  private String memberToken;
  private String strangerToken;
  private String projectId;

  @BeforeEach
  void setUp() {
    RestAssured.baseURI = "http://localhost";
    RestAssured.port = port;
    BaseIT.cleanDatabase(jdbc);

    adminId = register("admin@example.com", "Admin");
    memberId = register("member@example.com", "Member");
    strangerId = register("stranger@example.com", "Stranger");
    thirdId = register("third@example.com", "Third");
    adminToken = sign(adminId);
    memberToken = sign(memberId);
    strangerToken = sign(strangerId);

    projectId = createProject(adminToken, "TPROJ", "Test Project");
    addMember(adminToken, "member@example.com", "MEMBER").then().statusCode(201);
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

  private io.restassured.response.Response addMember(String token, String email, String role) {
    return given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body("{\"email\":\"" + email + "\",\"role\":\"" + role + "\"}")
        .when()
        .post("/api/projects/" + projectId + "/members");
  }

  private String membersPath() {
    return "/api/projects/" + projectId + "/members";
  }

  // ───────────────────────── AC-2.1 stranger → 404 everywhere ─────────────────────────

  @Test
  void stranger_gets404_onAllProjectScopedEndpoints() {
    String b = "Bearer " + strangerToken;
    given().header("Authorization", b).get("/api/projects/" + projectId).then().statusCode(404);
    given()
        .header("Authorization", b)
        .contentType(ContentType.JSON)
        .body("{\"name\":\"x\"}")
        .patch("/api/projects/" + projectId)
        .then()
        .statusCode(404);
    given().header("Authorization", b).delete("/api/projects/" + projectId).then().statusCode(404);
    given().header("Authorization", b).get(membersPath()).then().statusCode(404);
    given()
        .header("Authorization", b)
        .contentType(ContentType.JSON)
        .body("{\"email\":\"third@example.com\",\"role\":\"MEMBER\"}")
        .post(membersPath())
        .then()
        .statusCode(404);
    given()
        .header("Authorization", b)
        .contentType(ContentType.JSON)
        .body("{\"role\":\"ADMIN\"}")
        .patch(membersPath() + "/" + memberId)
        .then()
        .statusCode(404);
    given()
        .header("Authorization", b)
        .delete(membersPath() + "/" + memberId)
        .then()
        .statusCode(404);
  }

  // ───────────────────────── AC-2.2 member: 403 on admin ops, 200 on reads ─────────────

  @Test
  void member_gets403OnAdminMutations() {
    String b = "Bearer " + memberToken;
    given()
        .header("Authorization", b)
        .contentType(ContentType.JSON)
        .body("{\"name\":\"x\"}")
        .patch("/api/projects/" + projectId)
        .then()
        .statusCode(403);
    given().header("Authorization", b).delete("/api/projects/" + projectId).then().statusCode(403);
    given()
        .header("Authorization", b)
        .contentType(ContentType.JSON)
        .body("{\"email\":\"third@example.com\",\"role\":\"MEMBER\"}")
        .post(membersPath())
        .then()
        .statusCode(403);
    given()
        .header("Authorization", b)
        .contentType(ContentType.JSON)
        .body("{\"role\":\"ADMIN\"}")
        .patch(membersPath() + "/" + adminId)
        .then()
        .statusCode(403);
    given()
        .header("Authorization", b)
        .delete(membersPath() + "/" + adminId)
        .then()
        .statusCode(403);
  }

  @Test
  void member_gets200OnReads() {
    given()
        .header("Authorization", "Bearer " + memberToken)
        .get("/api/projects/" + projectId)
        .then()
        .statusCode(200)
        .body("name", equalTo("Test Project"));
    given()
        .header("Authorization", "Bearer " + memberToken)
        .get(membersPath())
        .then()
        .statusCode(200)
        .body("size()", greaterThanOrEqualTo(2));
  }

  @Test
  void memberSelfRemoval_isForbiddenNotAllowed() {
    given()
        .header("Authorization", "Bearer " + memberToken)
        .delete(membersPath() + "/" + memberId)
        .then()
        .statusCode(403);
  }

  // ───────────────────────── AC-2.3 admin happy path ─────────────────────────

  @Test
  void admin_canAddPromoteAndRemove() {
    addMember(adminToken, "third@example.com", "MEMBER").then().statusCode(201);
    given()
        .header("Authorization", "Bearer " + adminToken)
        .get(membersPath())
        .then()
        .statusCode(200)
        .body("size()", equalTo(3));
    given()
        .header("Authorization", "Bearer " + adminToken)
        .contentType(ContentType.JSON)
        .body("{\"role\":\"ADMIN\"}")
        .patch(membersPath() + "/" + thirdId)
        .then()
        .statusCode(200)
        .body("role", equalTo("ADMIN"));
    given()
        .header("Authorization", "Bearer " + adminToken)
        .delete(membersPath() + "/" + thirdId)
        .then()
        .statusCode(204);
    given()
        .header("Authorization", "Bearer " + adminToken)
        .get(membersPath())
        .then()
        .statusCode(200)
        .body("size()", equalTo(2));
  }

  @Test
  void memberList_hasFullShape() {
    given()
        .header("Authorization", "Bearer " + adminToken)
        .get(membersPath())
        .then()
        .statusCode(200)
        .body("find { it.role == 'ADMIN' }.userId", equalTo(adminId.toString()))
        .body("find { it.role == 'ADMIN' }.email", equalTo("admin@example.com"))
        .body("find { it.role == 'MEMBER' }.userId", equalTo(memberId.toString()));
  }

  // ───────────────────────── T-016 memberCount on project detail ─────────────────────────

  @Test
  void memberCount_onProjectDetail_tracksAddAndRemove() {
    // Baseline from setUp: admin + member = 2.
    given()
        .header("Authorization", "Bearer " + adminToken)
        .get("/api/projects/" + projectId)
        .then()
        .statusCode(200)
        .body("callerRole", equalTo("ADMIN"))
        .body("memberCount", equalTo(2));

    addMember(adminToken, "third@example.com", "MEMBER").then().statusCode(201);
    given()
        .header("Authorization", "Bearer " + adminToken)
        .get("/api/projects/" + projectId)
        .then()
        .statusCode(200)
        .body("memberCount", equalTo(3));

    given()
        .header("Authorization", "Bearer " + adminToken)
        .delete(membersPath() + "/" + thirdId)
        .then()
        .statusCode(204);
    given()
        .header("Authorization", "Bearer " + adminToken)
        .get("/api/projects/" + projectId)
        .then()
        .statusCode(200)
        .body("memberCount", equalTo(2));

    // The non-admin member sees their own role on the same project.
    given()
        .header("Authorization", "Bearer " + memberToken)
        .get("/api/projects/" + projectId)
        .then()
        .statusCode(200)
        .body("callerRole", equalTo("MEMBER"))
        .body("memberCount", equalTo(2));
  }

  // ───────────────────────── AC-3 last-admin guard ─────────────────────────

  @Test
  void lastAdmin_demotion_returns400() {
    given()
        .header("Authorization", "Bearer " + adminToken)
        .contentType(ContentType.JSON)
        .body("{\"role\":\"MEMBER\"}")
        .patch(membersPath() + "/" + adminId)
        .then()
        .statusCode(400)
        .body("message", containsString("ADMIN"));
  }

  @Test
  void lastAdmin_removal_returns400() {
    given()
        .header("Authorization", "Bearer " + adminToken)
        .delete(membersPath() + "/" + adminId)
        .then()
        .statusCode(400)
        .body("message", containsString("ADMIN"));
  }

  @Test
  void secondAdminPresent_demotionSucceeds() {
    // Promote member to ADMIN, then the original admin can be demoted.
    given()
        .header("Authorization", "Bearer " + adminToken)
        .contentType(ContentType.JSON)
        .body("{\"role\":\"ADMIN\"}")
        .patch(membersPath() + "/" + memberId)
        .then()
        .statusCode(200);
    given()
        .header("Authorization", "Bearer " + adminToken)
        .contentType(ContentType.JSON)
        .body("{\"role\":\"MEMBER\"}")
        .patch(membersPath() + "/" + adminId)
        .then()
        .statusCode(200)
        .body("role", equalTo("MEMBER"));
  }

  // ───────────────────────── error/validation paths ─────────────────────────

  @Test
  void addAlreadyMember_returns409() {
    addMember(adminToken, "member@example.com", "MEMBER").then().statusCode(409);
  }

  @Test
  void addCreatorAgain_returns409() {
    addMember(adminToken, "admin@example.com", "MEMBER").then().statusCode(409);
  }

  @Test
  void addByEmailCaseInsensitive_succeeds() {
    addMember(adminToken, "THIRD@Example.COM", "MEMBER")
        .then()
        .statusCode(201)
        .body("email", equalToIgnoringCase("third@example.com"))
        .body("role", equalTo("MEMBER"));
  }

  @Test
  void addUnregisteredEmail_returns404() {
    addMember(adminToken, "nobody@example.com", "MEMBER").then().statusCode(404);
  }

  @Test
  void addInvalidRole_returns400() {
    addMember(adminToken, "third@example.com", "OWNER").then().statusCode(400);
  }

  @Test
  void changeRoleOnNonMember_returns404() {
    given()
        .header("Authorization", "Bearer " + adminToken)
        .contentType(ContentType.JSON)
        .body("{\"role\":\"ADMIN\"}")
        .patch(membersPath() + "/" + strangerId)
        .then()
        .statusCode(404);
  }

  @Test
  void removeNonMember_returns404() {
    given()
        .header("Authorization", "Bearer " + adminToken)
        .delete(membersPath() + "/" + strangerId)
        .then()
        .statusCode(404);
  }

  @Test
  void addMalformedEmail_returns400() {
    addMember(adminToken, "'; DROP TABLE project_members; --@x.com", "MEMBER")
        .then()
        .statusCode(400);
    // Table still present + queryable.
    Integer count = jdbc.queryForObject("SELECT count(*) FROM project_members", Integer.class);
    org.assertj.core.api.Assertions.assertThat(count).isGreaterThanOrEqualTo(2);
  }

  // ───────────────────────── SEC-1 unauthenticated ─────────────────────────

  @Test
  void unauthenticated_returns401OnAllMemberEndpoints() {
    given().get(membersPath()).then().statusCode(401);
    given()
        .contentType(ContentType.JSON)
        .body("{\"email\":\"x@y.com\",\"role\":\"MEMBER\"}")
        .post(membersPath())
        .then()
        .statusCode(401);
    given()
        .contentType(ContentType.JSON)
        .body("{\"role\":\"ADMIN\"}")
        .patch(membersPath() + "/" + memberId)
        .then()
        .statusCode(401);
    given().delete(membersPath() + "/" + memberId).then().statusCode(401);
  }
}
