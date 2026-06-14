package io.ngss.atlas.ticket;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

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
 * End-to-end Ticket CRUD coverage (T-017): create + per-project numbering, get by
 * id/key, list + filters, PATCH, transition (any→any + no-op), admin soft-delete,
 * and the project-scoped authorization split (non-member 404, member-non-admin 403
 * on DELETE). Concurrency (AC3) and event publication (AC8) live in dedicated ITs.
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {"BCRYPT_COST=12", "spring.jpa.hibernate.ddl-auto=validate"})
class TicketControllerIT {

  private static final String SECRET = "ticketit-secret-min-32-characters-long-okay!";

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

  private UUID userA; // creator → ADMIN
  private UUID userB; // added as MEMBER where needed
  private UUID userC; // stranger → never a member
  private String tokenA;
  private String tokenB;
  private String tokenC;
  private String engId; // project ENG, owned by A

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
  }

  @AfterEach
  void reset() {
    RestAssured.reset();
  }

  // ───────────────────────── AC-1: create ─────────────────────────

  @Test
  void create_returns201_number1_keyEng1_reporterCaller_statusTodo_priorityDefaultP2() {
    createTicket(tokenA, engId, "{\"title\":\"First ticket\"}")
        .then()
        .statusCode(201)
        .header("Location", notNullValue())
        .body("id", notNullValue())
        .body("number", equalTo(1))
        .body("key", equalTo("ENG-1"))
        .body("title", equalTo("First ticket"))
        .body("status", equalTo("TODO"))
        .body("priority", equalTo("P2"))
        .body("reporterId", equalTo(userA.toString()))
        .body("assigneeId", nullValue())
        .body("createdAt", notNullValue())
        .body("updatedAt", notNullValue());
  }

  @Test
  void create_honorsSuppliedPriorityAndAssignee() {
    createTicket(
            tokenA,
            engId,
            "{\"title\":\"Urgent\",\"priority\":\"P0\",\"assigneeId\":\"" + userA + "\"}")
        .then()
        .statusCode(201)
        .body("priority", equalTo("P0"))
        .body("assigneeId", equalTo(userA.toString()));
  }

  @Test
  void create_blankOrTooLongTitle_returns400() {
    createTicket(tokenA, engId, "{\"title\":\"   \"}").then().statusCode(400);
    createTicket(tokenA, engId, "{\"title\":\"" + "x".repeat(201) + "\"}").then().statusCode(400);
  }

  @Test
  void create_byNonMember_returns404() {
    createTicket(tokenC, engId, "{\"title\":\"sneaky\"}").then().statusCode(404);
  }

  @Test
  void create_inSoftDeletedProject_returns404() {
    // A soft-deletes ENG, then attempts to create a ticket in it.
    given().header("Authorization", "Bearer " + tokenA).delete("/api/projects/" + engId).then().statusCode(204);
    createTicket(tokenA, engId, "{\"title\":\"ghost\"}").then().statusCode(404);
  }

  // ───────────────────────── AC-2: per-project monotonic numbering ─────────────────────────

  @Test
  void numbering_isMonotonicAndIndependentPerProject() {
    String opsId = createProject(tokenA, "OPS", "Operations");

    assertThat(ticketKey(tokenA, engId, "E1")).isEqualTo("ENG-1");
    assertThat(ticketKey(tokenA, engId, "E2")).isEqualTo("ENG-2");
    assertThat(ticketKey(tokenA, engId, "E3")).isEqualTo("ENG-3");
    assertThat(ticketKey(tokenA, opsId, "O1")).isEqualTo("OPS-1");
    assertThat(ticketKey(tokenA, opsId, "O2")).isEqualTo("OPS-2");
  }

  // ───────────────────────── AC-4: get by id / key ─────────────────────────

  @Test
  void get_byKeyAndByUuid_bothResolve_andLeakNothingToNonMembers() {
    Response created = createTicket(tokenA, engId, "{\"title\":\"Findable\"}");
    String id = created.then().statusCode(201).extract().jsonPath().getString("id");

    given().header("Authorization", "Bearer " + tokenA).get("/api/tickets/ENG-1")
        .then().statusCode(200).body("id", equalTo(id)).body("title", equalTo("Findable"));
    given().header("Authorization", "Bearer " + tokenA).get("/api/tickets/" + id)
        .then().statusCode(200).body("key", equalTo("ENG-1"));

    // Non-member → 404 (existence-leak prevention).
    given().header("Authorization", "Bearer " + tokenC).get("/api/tickets/" + id)
        .then().statusCode(404);
  }

  @Test
  void get_nonExistentOrGarbage_returns404() {
    given().header("Authorization", "Bearer " + tokenA).get("/api/tickets/ENG-999").then().statusCode(404);
    given()
        .header("Authorization", "Bearer " + tokenA)
        .get("/api/tickets/00000000-0000-0000-0000-000000000099")
        .then()
        .statusCode(404);
    given().header("Authorization", "Bearer " + tokenA).get("/api/tickets/not-a-uuid-or-key")
        .then().statusCode(404);
  }

  @Test
  void get_softDeletedTicket_returns404() {
    String id = createTicketId(tokenA, engId, "Doomed");
    given().header("Authorization", "Bearer " + tokenA).delete("/api/tickets/" + id).then().statusCode(204);
    given().header("Authorization", "Bearer " + tokenA).get("/api/tickets/" + id).then().statusCode(404);
    given().header("Authorization", "Bearer " + tokenA).get("/api/tickets/ENG-1").then().statusCode(404);
  }

  // ───────────────────────── AC-5: list + filters ─────────────────────────

  @Test
  void list_filtersByStatusAssigneePriority_acceptsQAndLabel_excludesSoftDeleted() {
    String t1 = createTicketId(tokenA, engId, "Plain"); // P2, TODO, unassigned
    createTicket(tokenA, engId, "{\"title\":\"High\",\"priority\":\"P0\"}").then().statusCode(201);
    createTicket(
            tokenA, engId, "{\"title\":\"Assigned\",\"assigneeId\":\"" + userA + "\"}")
        .then()
        .statusCode(201);
    // Move t1 to IN_PROGRESS so the status filter distinguishes it.
    transition(tokenA, t1, "IN_PROGRESS").then().statusCode(200);

    // All three live tickets (T-018 PagedResponse envelope).
    given().header("Authorization", "Bearer " + tokenA).get("/api/projects/" + engId + "/tickets")
        .then().statusCode(200)
        .body("items.size()", equalTo(3))
        .body("total", equalTo(3))
        .body("page", equalTo(0))
        .body("size", equalTo(20))
        .body("items[0].labelIds", notNullValue()); // labelIds always present (array)

    // status filter.
    given().header("Authorization", "Bearer " + tokenA)
        .get("/api/projects/" + engId + "/tickets?status=IN_PROGRESS")
        .then().statusCode(200).body("items.size()", equalTo(1)).body("items[0].title", equalTo("Plain"));

    // priority filter.
    given().header("Authorization", "Bearer " + tokenA)
        .get("/api/projects/" + engId + "/tickets?priority=P0")
        .then().statusCode(200).body("items.size()", equalTo(1)).body("items[0].title", equalTo("High"));

    // assignee filter.
    given().header("Authorization", "Bearer " + tokenA)
        .get("/api/projects/" + engId + "/tickets?assigneeId=" + userA)
        .then().statusCode(200).body("items.size()", equalTo(1)).body("items[0].title", equalTo("Assigned"));

    // q is accepted without error (no-op stub; T-018 out of scope).
    given().header("Authorization", "Bearer " + tokenA)
        .get("/api/projects/" + engId + "/tickets?q=anything")
        .then().statusCode(200).body("items.size()", equalTo(3));

    // Soft-delete t1 → excluded.
    given().header("Authorization", "Bearer " + tokenA).delete("/api/tickets/" + t1).then().statusCode(204);
    given().header("Authorization", "Bearer " + tokenA).get("/api/projects/" + engId + "/tickets")
        .then().statusCode(200).body("items.size()", equalTo(2)).body("total", equalTo(2));
  }

  @Test
  void list_defaultSortIsUpdatedAtDescending() throws InterruptedException {
    String t1 = createTicketId(tokenA, engId, "One");
    Thread.sleep(10);
    createTicketId(tokenA, engId, "Two");
    Thread.sleep(10);
    createTicketId(tokenA, engId, "Three");
    // Patch t1 last → its updatedAt becomes the newest, so it sorts first.
    Thread.sleep(10);
    given().header("Authorization", "Bearer " + tokenA).contentType(ContentType.JSON)
        .body("{\"title\":\"One-edited\"}").patch("/api/tickets/" + t1).then().statusCode(200);

    given().header("Authorization", "Bearer " + tokenA).get("/api/projects/" + engId + "/tickets")
        .then().statusCode(200).body("items[0].title", equalTo("One-edited"));
  }

  @Test
  void list_byNonMember_returns404() {
    createTicketId(tokenA, engId, "Secret");
    given().header("Authorization", "Bearer " + tokenC).get("/api/projects/" + engId + "/tickets")
        .then().statusCode(404);
  }

  // ───────────────────────── AC-6: PATCH ─────────────────────────

  @Test
  void patch_updatesFields_advancesUpdatedAt_keepsCreatedAtAndStatus() throws InterruptedException {
    Response created = createTicket(tokenA, engId, "{\"title\":\"Before\",\"priority\":\"P3\"}");
    String id = created.then().statusCode(201).extract().jsonPath().getString("id");
    String createdAt = created.jsonPath().getString("createdAt");
    Thread.sleep(15);

    Response patched =
        given()
            .header("Authorization", "Bearer " + tokenA)
            .contentType(ContentType.JSON)
            .body(
                "{\"title\":\"After\",\"description\":\"new desc\",\"priority\":\"P1\",\"assigneeId\":\""
                    + userA
                    + "\"}")
            .patch("/api/tickets/" + id);
    patched
        .then()
        .statusCode(200)
        .body("title", equalTo("After"))
        .body("description", equalTo("new desc"))
        .body("priority", equalTo("P1"))
        .body("assigneeId", equalTo(userA.toString()))
        .body("status", equalTo("TODO")); // PATCH never changes status

    // createdAt is preserved. The create response carries the in-memory Instant
    // (nanos); the patched response re-reads it from Postgres (timestamptz rounds
    // to micros), so compare within a tolerance rather than exact-equality — same
    // as ProjectControllerIT. (truncatedTo(MICROS) would be fragile: PG rounds
    // while truncatedTo floors, so they can disagree by 1µs on a boundary.)
    assertThat(Instant.parse(patched.jsonPath().getString("createdAt")))
        .isCloseTo(Instant.parse(createdAt), within(1, ChronoUnit.MILLIS));
    assertThat(Instant.parse(patched.jsonPath().getString("updatedAt")))
        .isAfter(Instant.parse(createdAt));
  }

  @Test
  void patch_blankTitle_returns400() {
    String id = createTicketId(tokenA, engId, "Name");
    given().header("Authorization", "Bearer " + tokenA).contentType(ContentType.JSON)
        .body("{\"title\":\"   \"}").patch("/api/tickets/" + id).then().statusCode(400);
  }

  @Test
  void patch_byNonMember_returns404() {
    String id = createTicketId(tokenA, engId, "Owned");
    given().header("Authorization", "Bearer " + tokenC).contentType(ContentType.JSON)
        .body("{\"title\":\"Hijack\"}").patch("/api/tickets/" + id).then().statusCode(404);
  }

  // ───────────────────────── T-041: unassign (DELETE /{id}/assignee) ─────────────────────────

  @Test
  void unassign_onAssignedTicket_clearsAssignee_andRecordsAssigneeChangedToNull() {
    String id =
        createTicket(tokenA, engId, "{\"title\":\"Assigned\",\"assigneeId\":\"" + userA + "\"}")
            .then()
            .statusCode(201)
            .body("assigneeId", equalTo(userA.toString()))
            .extract()
            .jsonPath()
            .getString("id");

    // Clears the assignee; mirrors the PATCH/transition shape (200 + the updated ticket).
    unassign(tokenA, id)
        .then()
        .statusCode(200)
        .body("id", equalTo(id))
        .body("key", equalTo("ENG-1"))
        .body("assigneeId", nullValue());

    assertThat(jdbc.queryForObject("SELECT assignee_id FROM tickets WHERE id=?::uuid", String.class, id))
        .isNull();

    // Activity: exactly one ASSIGNEE_CHANGED row, new value null (assign path mirrored, inverted).
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM activity_events WHERE ticket_id=?::uuid AND event_type='ASSIGNEE_CHANGED'",
                Integer.class,
                id))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT (payload::jsonb)->>'to' FROM activity_events WHERE ticket_id=?::uuid AND event_type='ASSIGNEE_CHANGED'",
                String.class,
                id))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT (payload::jsonb)->>'from' FROM activity_events WHERE ticket_id=?::uuid AND event_type='ASSIGNEE_CHANGED'",
                String.class,
                id))
        .isEqualTo(userA.toString());
  }

  @Test
  void unassign_onAlreadyUnassignedTicket_isIdempotent_andWritesNoActivity() {
    String id = createTicketId(tokenA, engId, "Plain"); // created with no assignee

    unassign(tokenA, id).then().statusCode(200).body("assigneeId", nullValue());
    // A second call is still a success (idempotent no-op).
    unassign(tokenA, id).then().statusCode(200).body("assigneeId", nullValue());

    // No real change ever happened → no ASSIGNEE_CHANGED row (mirrors the transition no-op).
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM activity_events WHERE ticket_id=?::uuid AND event_type='ASSIGNEE_CHANGED'",
                Integer.class,
                id))
        .isZero();
  }

  @Test
  void unassign_byNonMember_returns404_andLeavesAssigneeUntouched() {
    String id =
        createTicket(tokenA, engId, "{\"title\":\"Owned\",\"assigneeId\":\"" + userA + "\"}")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("id");

    unassign(tokenC, id).then().statusCode(404); // stranger → existence-leak prevention

    given()
        .header("Authorization", "Bearer " + tokenA)
        .get("/api/tickets/" + id)
        .then()
        .statusCode(200)
        .body("assigneeId", equalTo(userA.toString())); // unchanged
  }

  @Test
  void unassign_unauthenticated_returns401() {
    String id = createTicketId(tokenA, engId, "X");
    given().delete("/api/tickets/" + id + "/assignee").then().statusCode(401);
  }

  // ───────────────────────── AC-7: transition ─────────────────────────

  @Test
  void transition_anyToAny_advancesUpdatedAt_andNoOpIsAllowed() throws InterruptedException {
    Response created = createTicket(tokenA, engId, "{\"title\":\"Flow\"}");
    String id = created.then().statusCode(201).extract().jsonPath().getString("id");
    String createdUpdatedAt = created.jsonPath().getString("updatedAt");
    Thread.sleep(15);

    transition(tokenA, id, "IN_PROGRESS").then().statusCode(200).body("status", equalTo("IN_PROGRESS"));
    // DONE → TODO proves there is no state-machine restriction (any → any).
    transition(tokenA, id, "DONE").then().statusCode(200).body("status", equalTo("DONE"));
    Response back = transition(tokenA, id, "TODO");
    back.then().statusCode(200).body("status", equalTo("TODO"));
    assertThat(Instant.parse(back.jsonPath().getString("updatedAt")))
        .isAfter(Instant.parse(createdUpdatedAt));

    // Same-status transition is a 200 no-op.
    transition(tokenA, id, "TODO").then().statusCode(200).body("status", equalTo("TODO"));
  }

  @Test
  void transition_byNonMember_returns404() {
    String id = createTicketId(tokenA, engId, "Locked");
    transition(tokenC, id, "IN_PROGRESS").then().statusCode(404);
  }

  // ───────────────────────── AC-9: delete (admin soft-delete) ─────────────────────────

  @Test
  void delete_byAdmin_softDeletes_thenHiddenAnd404() {
    String id = createTicketId(tokenA, engId, "Del");
    given().header("Authorization", "Bearer " + tokenA).delete("/api/tickets/" + id).then().statusCode(204);

    Instant deletedAt =
        jdbc.queryForObject("SELECT deleted_at FROM tickets WHERE id=?::uuid", Instant.class, id);
    assertThat(deletedAt).isNotNull();
    given().header("Authorization", "Bearer " + tokenA).get("/api/tickets/" + id).then().statusCode(404);
    // Already-deleted → 404.
    given().header("Authorization", "Bearer " + tokenA).delete("/api/tickets/" + id).then().statusCode(404);
  }

  @Test
  void delete_byMemberNonAdmin_returns403_byNonMember_returns404() {
    String id = createTicketId(tokenA, engId, "Guarded");
    // Add B as a plain MEMBER of ENG.
    given()
        .header("Authorization", "Bearer " + tokenA)
        .contentType(ContentType.JSON)
        .body("{\"email\":\"userb@example.com\",\"role\":\"MEMBER\"}")
        .post("/api/projects/" + engId + "/members")
        .then()
        .statusCode(201);

    // Member (not admin) → 403.
    given().header("Authorization", "Bearer " + tokenB).delete("/api/tickets/" + id).then().statusCode(403);
    // Stranger → 404 (existence-leak prevention).
    given().header("Authorization", "Bearer " + tokenC).delete("/api/tickets/" + id).then().statusCode(404);
    // Still live.
    given().header("Authorization", "Bearer " + tokenA).get("/api/tickets/" + id).then().statusCode(200);
  }

  // ───────────────────────── security ─────────────────────────

  @Test
  void unauthenticated_returns401() {
    given().get("/api/projects/" + engId + "/tickets").then().statusCode(401);
    given().contentType(ContentType.JSON).body("{\"title\":\"x\"}")
        .post("/api/projects/" + engId + "/tickets").then().statusCode(401);
    given().get("/api/tickets/ENG-1").then().statusCode(401);
  }

  // ───────────────────────── helpers ─────────────────────────

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

  private Response createTicket(String token, String projectId, String body) {
    return given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post("/api/projects/" + projectId + "/tickets");
  }

  private String createTicketId(String token, String projectId, String title) {
    return createTicket(token, projectId, "{\"title\":\"" + title + "\"}")
        .then()
        .statusCode(201)
        .extract()
        .jsonPath()
        .getString("id");
  }

  private String ticketKey(String token, String projectId, String title) {
    return createTicket(token, projectId, "{\"title\":\"" + title + "\"}")
        .then()
        .statusCode(201)
        .extract()
        .jsonPath()
        .getString("key");
  }

  private Response transition(String token, String ticketId, String toStatus) {
    return given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body("{\"toStatus\":\"" + toStatus + "\"}")
        .when()
        .post("/api/tickets/" + ticketId + "/transition");
  }

  private Response unassign(String token, String ticketId) {
    return given()
        .header("Authorization", "Bearer " + token)
        .when()
        .delete("/api/tickets/" + ticketId + "/assignee");
  }
}
