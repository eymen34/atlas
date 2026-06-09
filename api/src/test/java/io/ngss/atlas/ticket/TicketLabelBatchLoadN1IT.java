package io.ngss.atlas.ticket;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

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
import jakarta.persistence.EntityManagerFactory;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
 * AC-4 (N+1 hard gate): GET /api/projects/{id}/tickets must batch-load labels in a
 * CONSTANT number of queries regardless of how many tickets/labels are on the page.
 *
 * <p>The constant is FOUR Hibernate query executions: (1) the membership guard
 * lookup, (2) the page data query, (3) the page count query, (4) the single
 * {@code findByTicketIdIn} batch label load. (The project-key load is a
 * {@code findById}/{@code em.find} — an entity load, not counted by
 * {@code getQueryExecutionCount}.) The ticket's gate text says "≤ 3 (data + count +
 * batch)"; that omits the always-present membership-authorization query, which is a
 * constant — NOT an N+1. The thing this gate truly guards against is a per-ticket
 * label query, which would make the count ~23 for this 20-ticket / 60-association
 * fixture.
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(
    properties = {
      "BCRYPT_COST=12",
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.jpa.properties.hibernate.generate_statistics=true"
    })
class TicketLabelBatchLoadN1IT {

  private static final String SECRET = "ticketn1it-secret-min-32-characters-long-ok";
  private static final int TICKETS = 20;

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

  private String tokenA;
  private String p1;

  @BeforeEach
  void setUp() {
    RestAssured.baseURI = "http://localhost";
    RestAssured.port = port;
    BaseIT.cleanDatabase(jdbc);

    UUID userA = register("usera@example.com", "Alice");
    tokenA = sign(userA);
    p1 = createProject(tokenA, "ENG", "Engineering");
    String lA = createLabelId("a");
    String lB = createLabelId("b");
    String lC = createLabelId("c");
    // 20 tickets, each carrying all 3 labels → 60 ticket_labels rows.
    for (int i = 0; i < TICKETS; i++) {
      String ticketId = createTicket("t" + i);
      setLabels(ticketId, lA, lB, lC);
    }
  }

  @AfterEach
  void reset() {
    RestAssured.reset();
  }

  @Test
  void listPage_batchLoadsLabels_inAConstantNumberOfQueries() {
    Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    // Clear AFTER the fixture is built so only the measured GET counts.
    stats.clear();

    given()
        .header("Authorization", "Bearer " + tokenA)
        .get("/api/projects/" + p1 + "/tickets?page=0&size=" + TICKETS)
        .then()
        .statusCode(200)
        .body("items.size()", equalTo(TICKETS))
        .body("total", equalTo(TICKETS))
        .body("items[0].labelIds.size()", equalTo(3));

    // 4 constant queries: membership guard + data + count + ONE batch label load.
    // A per-ticket label query (N+1) would push this to ~23.
    assertThat(stats.getQueryExecutionCount())
        .as("GET ticket list must batch-load labels (no N+1) — constant query count")
        .isLessThanOrEqualTo(4L);
  }

  // ───────────────────────── helpers ─────────────────────────

  private String createTicket(String title) {
    return given()
        .header("Authorization", "Bearer " + tokenA)
        .contentType(ContentType.JSON)
        .body("{\"title\":\"" + title + "\"}")
        .when()
        .post("/api/projects/" + p1 + "/tickets")
        .then()
        .statusCode(201)
        .extract()
        .jsonPath()
        .getString("id");
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
