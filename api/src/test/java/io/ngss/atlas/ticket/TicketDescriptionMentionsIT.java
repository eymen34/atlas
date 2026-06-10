package io.ngss.atlas.ticket;

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
 * AC-4: a ticket's HTML description @mentions are diffed into ticket_mentions on
 * create and on a meaningful PATCH; an absent description field is a no-op; a
 * null↔"" / blank flip is a no-op; clearing the description removes the rows.
 * Self-skips without Docker.
 */
@SpringBootTest(
    classes = Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {"BCRYPT_COST=12", "spring.jpa.hibernate.ddl-auto=validate"})
class TicketDescriptionMentionsIT {

  private static final String SECRET = "descmentionit-secret-min-32-characters-ok!!";

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

  private UUID alice; // creator/admin, handle "alice"
  private UUID bob; // member, handle "bob"
  private String tokenAlice;
  private String engId;

  @BeforeEach
  void setUp() {
    RestAssured.baseURI = "http://localhost";
    RestAssured.port = port;
    BaseIT.cleanDatabase(jdbc);
    alice = register("alice@example.com", "Alice");
    bob = register("bob@example.com", "Bob");
    tokenAlice = sign(alice);
    engId = createProject("ENG", "Engineering");
    addMember("bob@example.com");
  }

  @AfterEach
  void reset() {
    RestAssured.reset();
  }

  private int mentionCount(String ticketId, UUID userId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM ticket_mentions WHERE ticket_id=?::uuid AND user_id=?::uuid",
        Integer.class,
        ticketId,
        userId.toString());
  }

  private int totalMentions(String ticketId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM ticket_mentions WHERE ticket_id=?::uuid", Integer.class, ticketId);
  }

  @Test
  void create_withDescriptionMention_recordsTicketMention() {
    String id = createTicket("{\"title\":\"T\",\"description\":\"<p>cc @bob</p>\"}");
    assertThat(mentionCount(id, bob)).isEqualTo(1);
    assertThat(mentionCount(id, alice)).isZero();
  }

  @Test
  void patch_meaningfulDescriptionChange_rediffsMentions() {
    String id = createTicket("{\"title\":\"T\",\"description\":\"<p>cc @bob</p>\"}");
    patch(id, "{\"description\":\"<p>now @alice</p>\"}");
    assertThat(mentionCount(id, alice)).isEqualTo(1);
    assertThat(mentionCount(id, bob)).isZero();
  }

  @Test
  void patch_absentDescriptionField_leavesMentionsUnchanged() {
    String id = createTicket("{\"title\":\"T\",\"description\":\"<p>cc @bob</p>\"}");
    patch(id, "{\"title\":\"Renamed\"}"); // description field absent
    assertThat(mentionCount(id, bob)).isEqualTo(1);
  }

  @Test
  void patch_clearingDescription_removesMentions() {
    String id = createTicket("{\"title\":\"T\",\"description\":\"<p>cc @bob</p>\"}");
    patch(id, "{\"description\":\"\"}"); // explicit clear → meaningful change
    assertThat(totalMentions(id)).isZero();
  }

  @Test
  void patch_blankToBlankFlip_isNoOp() {
    // Create with blank description (no mentions), then PATCH to "" — both blank, so
    // isMeaningfullyChanged is false and nothing churns.
    String id = createTicket("{\"title\":\"T\",\"description\":\"   \"}");
    assertThat(totalMentions(id)).isZero();
    patch(id, "{\"description\":\"\"}");
    assertThat(totalMentions(id)).isZero();
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

  private String createProject(String key, String name) {
    return given()
        .header("Authorization", "Bearer " + tokenAlice)
        .contentType(ContentType.JSON)
        .body("{\"key\":\"" + key + "\",\"name\":\"" + name + "\"}")
        .post("/api/projects")
        .then()
        .statusCode(201)
        .extract()
        .jsonPath()
        .getString("id");
  }

  private void addMember(String email) {
    given()
        .header("Authorization", "Bearer " + tokenAlice)
        .contentType(ContentType.JSON)
        .body("{\"email\":\"" + email + "\",\"role\":\"MEMBER\"}")
        .post("/api/projects/" + engId + "/members")
        .then()
        .statusCode(201);
  }

  private String createTicket(String body) {
    return given()
        .header("Authorization", "Bearer " + tokenAlice)
        .contentType(ContentType.JSON)
        .body(body)
        .post("/api/projects/" + engId + "/tickets")
        .then()
        .statusCode(201)
        .extract()
        .jsonPath()
        .getString("id");
  }

  private void patch(String ticketId, String body) {
    given()
        .header("Authorization", "Bearer " + tokenAlice)
        .contentType(ContentType.JSON)
        .body(body)
        .patch("/api/tickets/" + ticketId)
        .then()
        .statusCode(200);
  }
}
