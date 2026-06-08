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
import io.ngss.atlas.domain.TicketStatus;
import io.ngss.atlas.ticket.event.TicketTransitionedEvent;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * AC-8: a real status transition publishes exactly one {@link TicketTransitionedEvent}
 * (correct from/to/actor); a same-status no-op publishes none. This proves the
 * T-019 activity-log bridge.
 *
 * <p>The event is published on the Tomcat worker thread (RANDOM_PORT), so a
 * thread-local {@code @RecordApplicationEvents} would NOT see it — a thread-safe
 * recording {@code @EventListener} bean (registered via a nested
 * {@code @TestConfiguration}) captures events regardless of publishing thread.
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {"BCRYPT_COST=12", "spring.jpa.hibernate.ddl-auto=validate"})
class TicketTransitionEventIT {

  private static final String SECRET = "ticketevt-secret-min-32-characters-long-okay";

  @TestConfiguration
  static class RecorderConfig {
    @org.springframework.context.annotation.Bean
    TicketEventRecorder ticketEventRecorder() {
      return new TicketEventRecorder();
    }
  }

  /** Thread-safe recorder; @EventListener fires synchronously on the publishing thread. */
  static class TicketEventRecorder {
    final List<TicketTransitionedEvent> events = new CopyOnWriteArrayList<>();

    @EventListener
    void on(TicketTransitionedEvent event) {
      events.add(event);
    }

    void clear() {
      events.clear();
    }
  }

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
  @Autowired TicketEventRecorder recorder;

  private UUID userA;
  private String tokenA;
  private String ticketId;
  private UUID projectUuid;

  @BeforeEach
  void setUp() {
    RestAssured.baseURI = "http://localhost";
    RestAssured.port = port;
    jdbc.update("DELETE FROM tickets");
    jdbc.update("DELETE FROM project_ticket_counters");
    jdbc.update("DELETE FROM project_members");
    jdbc.update("DELETE FROM projects");
    jdbc.update("DELETE FROM refresh_tokens");
    jdbc.update("DELETE FROM password_credentials");
    jdbc.update("DELETE FROM users");

    userA = register("usera@example.com", "Alice");
    tokenA = sign(userA);
    String projectId =
        given()
            .header("Authorization", "Bearer " + tokenA)
            .contentType(ContentType.JSON)
            .body("{\"key\":\"ENG\",\"name\":\"Engineering\"}")
            .when()
            .post("/api/projects")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("id");
    projectUuid = UUID.fromString(projectId);
    ticketId =
        given()
            .header("Authorization", "Bearer " + tokenA)
            .contentType(ContentType.JSON)
            .body("{\"title\":\"Flow\"}")
            .when()
            .post("/api/projects/" + projectId + "/tickets")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("id");
    recorder.clear();
  }

  @AfterEach
  void reset() {
    RestAssured.reset();
  }

  @Test
  void realTransition_publishesExactlyOneEventWithCorrectFromToActor() {
    transition("IN_PROGRESS").then().statusCode(200);

    assertThat(recorder.events).hasSize(1);
    TicketTransitionedEvent event = recorder.events.get(0);
    assertThat(event.ticketId()).isEqualTo(UUID.fromString(ticketId));
    assertThat(event.projectId()).isEqualTo(projectUuid);
    assertThat(event.fromStatus()).isEqualTo(TicketStatus.TODO);
    assertThat(event.toStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
    assertThat(event.actorId()).isEqualTo(userA);
    assertThat(event.occurredAt()).isNotNull();
  }

  @Test
  void sameStatusTransition_publishesNoEvent() {
    // Ticket is TODO; transitioning to TODO is a no-op.
    transition("TODO").then().statusCode(200);
    assertThat(recorder.events).isEmpty();
  }

  private io.restassured.response.Response transition(String toStatus) {
    return given()
        .header("Authorization", "Bearer " + tokenA)
        .contentType(ContentType.JSON)
        .body("{\"toStatus\":\"" + toStatus + "\"}")
        .when()
        .post("/api/tickets/" + ticketId + "/transition");
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
