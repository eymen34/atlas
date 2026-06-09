package io.ngss.atlas.activity;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.ngss.atlas.Application;
import io.ngss.atlas.BaseIT;
import io.ngss.atlas.activity.payload.AssigneeChangedPayload;
import io.ngss.atlas.activity.payload.CreatedPayload;
import io.ngss.atlas.activity.payload.LabelsChangedPayload;
import io.ngss.atlas.activity.payload.PriorityChangedPayload;
import io.ngss.atlas.activity.payload.StatusChangedPayload;
import io.ngss.atlas.domain.ActivityEvent;
import io.ngss.atlas.domain.ActivityEventType;
import io.ngss.atlas.domain.TicketPriority;
import io.ngss.atlas.domain.TicketStatus;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

/**
 * AC-1: the activity log is written for the right lifecycle changes (CREATED,
 * STATUS_CHANGED, ASSIGNEE_CHANGED, PRIORITY_CHANGED, LABELS_CHANGED) with the
 * correct snapshot/delta payloads, and NOT written for unlogged edits
 * (title/description) or no-ops. Drives the real HTTP endpoints, asserts against
 * the persisted rows (the writer runs in the request transaction).
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {"BCRYPT_COST=12", "spring.jpa.hibernate.ddl-auto=validate"})
class ActivityWriteIT {

  private static final String SECRET = "activitywriteit-secret-min-32-characters-ok";

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
  @Autowired ActivityEventRepository activityRepo;
  @Autowired ObjectMapper om;

  private UUID userA;
  private UUID userB;
  private String tokenA;
  private String engId;

  @BeforeEach
  void setUp() {
    RestAssured.baseURI = "http://localhost";
    RestAssured.port = port;
    BaseIT.cleanDatabase(jdbc);
    userA = register("usera@example.com", "Alice");
    userB = register("userb@example.com", "Bob");
    tokenA = sign(userA);
    engId = createProject("ENG", "Engineering");
  }

  @AfterEach
  void reset() {
    RestAssured.reset();
  }

  // ───────────────────────── AC-1.1: CREATED ─────────────────────────

  @Test
  void create_writesExactlyOneCreatedRow_withSnapshotPayload() {
    String id = createTicket("{\"title\":\"Build it\",\"priority\":\"P1\"}");

    List<ActivityEvent> rows = activity(id);
    assertThat(rows).hasSize(1);
    ActivityEvent created = rows.get(0);
    assertThat(created.getEventType()).isEqualTo(ActivityEventType.CREATED);
    assertThat(created.getActorId()).isEqualTo(userA);
    CreatedPayload p = om.readValue(created.getPayload(), CreatedPayload.class);
    assertThat(p.title()).isEqualTo("Build it");
    assertThat(p.status()).isEqualTo(TicketStatus.TODO); // create always starts TODO
    assertThat(p.priority()).isEqualTo(TicketPriority.P1);
  }

  // ───────────────────────── STATUS_CHANGED ─────────────────────────

  @Test
  void transition_writesStatusChangedRow() {
    String id = createTicket("{\"title\":\"Flow\"}");
    transition(id, "IN_PROGRESS");

    List<ActivityEvent> rows = activity(id); // newest-first
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).getEventType()).isEqualTo(ActivityEventType.STATUS_CHANGED);
    StatusChangedPayload p = om.readValue(rows.get(0).getPayload(), StatusChangedPayload.class);
    assertThat(p.from()).isEqualTo(TicketStatus.TODO);
    assertThat(p.to()).isEqualTo(TicketStatus.IN_PROGRESS);
  }

  @Test
  void sameStatusTransition_writesNoRow() {
    String id = createTicket("{\"title\":\"NoOp\"}");
    transition(id, "TODO"); // already TODO → no-op
    assertThat(activity(id)).hasSize(1); // only the CREATED row
  }

  // ───────────────────────── AC-1.3: PATCH assignee + priority ─────────────────────────

  @Test
  void patchAssigneeAndPriority_writesTwoRows() {
    String id = createTicket("{\"title\":\"Both\"}"); // assignee=null, priority=P2 default
    patch(id, "{\"assigneeId\":\"" + userB + "\",\"priority\":\"P0\"}");

    List<ActivityEvent> rows = activity(id);
    assertThat(rows).hasSize(3); // CREATED + ASSIGNEE_CHANGED + PRIORITY_CHANGED
    assertThat(rows.stream().map(ActivityEvent::getEventType))
        .contains(ActivityEventType.ASSIGNEE_CHANGED, ActivityEventType.PRIORITY_CHANGED);

    ActivityEvent assignee = firstOfType(rows, ActivityEventType.ASSIGNEE_CHANGED);
    AssigneeChangedPayload ap = om.readValue(assignee.getPayload(), AssigneeChangedPayload.class);
    assertThat(ap.from()).isNull();
    assertThat(ap.to()).isEqualTo(userB);

    ActivityEvent priority = firstOfType(rows, ActivityEventType.PRIORITY_CHANGED);
    PriorityChangedPayload pp = om.readValue(priority.getPayload(), PriorityChangedPayload.class);
    assertThat(pp.from()).isEqualTo(TicketPriority.P2);
    assertThat(pp.to()).isEqualTo(TicketPriority.P0);
  }

  // ───────────────────────── AC-1.4: PATCH title/description only ─────────────────────────

  @Test
  void patchTitleAndDescriptionOnly_writesNoRow() {
    String id = createTicket("{\"title\":\"Before\"}");
    patch(id, "{\"title\":\"After\",\"description\":\"new desc\"}");
    assertThat(activity(id)).hasSize(1); // only the CREATED row; title/desc are not logged
  }

  @Test
  void patchSameValues_writesNoRow() {
    String id = createTicket("{\"title\":\"T\",\"priority\":\"P2\"}");
    // Re-supply the SAME priority and no assignee change → nothing logged.
    patch(id, "{\"priority\":\"P2\"}");
    assertThat(activity(id)).hasSize(1);
  }

  // ───────────────────────── LABELS_CHANGED ─────────────────────────

  @Test
  void setLabels_writesLabelsChangedRow_andNoOpReplaceWritesNone() {
    String labelId = createLabel("backend");
    String id = createTicket("{\"title\":\"Labelled\"}");

    setLabels(id, labelId); // add one
    List<ActivityEvent> afterAdd = activity(id);
    assertThat(afterAdd).hasSize(2); // CREATED + LABELS_CHANGED
    ActivityEvent labels = firstOfType(afterAdd, ActivityEventType.LABELS_CHANGED);
    LabelsChangedPayload lp = om.readValue(labels.getPayload(), LabelsChangedPayload.class);
    assertThat(lp.added()).containsExactly(UUID.fromString(labelId));
    assertThat(lp.removed()).isEmpty();

    // Identical replace → no new row.
    setLabels(id, labelId);
    assertThat(activity(id)).hasSize(2);

    // Clear → a LABELS_CHANGED with removed only.
    setLabels(id); // empty
    List<ActivityEvent> afterClear = activity(id);
    assertThat(afterClear).hasSize(3);
    LabelsChangedPayload cleared =
        om.readValue(firstOfType(afterClear, ActivityEventType.LABELS_CHANGED).getPayload(),
            LabelsChangedPayload.class);
    assertThat(cleared.added()).isEmpty();
    assertThat(cleared.removed()).containsExactly(UUID.fromString(labelId));
  }

  // ───────────────────────── helpers ─────────────────────────

  private List<ActivityEvent> activity(String ticketId) {
    return activityRepo
        .findByTicketIdOrderByCreatedAtDesc(UUID.fromString(ticketId), PageRequest.of(0, 100))
        .getContent();
  }

  private static ActivityEvent firstOfType(List<ActivityEvent> rows, ActivityEventType type) {
    return rows.stream().filter(r -> r.getEventType() == type).findFirst().orElseThrow();
  }

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

  private String createLabel(String name) {
    return given()
        .header("Authorization", "Bearer " + tokenA)
        .contentType(ContentType.JSON)
        .body("{\"name\":\"" + name + "\"}")
        .when()
        .post("/api/projects/" + engId + "/labels")
        .then()
        .statusCode(201)
        .extract()
        .jsonPath()
        .getString("id");
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
