package io.ngss.atlas.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import io.ngss.atlas.Application;
import io.ngss.atlas.BaseIT;
import io.ngss.atlas.domain.TicketStatus;
import io.ngss.atlas.ticket.dto.TransitionRequest;
import io.ngss.atlas.ticket.event.TicketTransitionedEvent;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * AC-8: a real status transition publishes exactly one {@link TicketTransitionedEvent}
 * (correct from/to/actor); a same-status no-op publishes none. This proves the
 * T-019 activity-log bridge.
 *
 * <p>Uses Spring's {@code @RecordApplicationEvents} + injected {@link ApplicationEvents}
 * (no custom listener bean). {@code ApplicationEvents} records events published on
 * the TEST thread, so the transition is invoked directly on {@link TicketController}
 * (synchronously, on the test thread) rather than through a RANDOM_PORT HTTP call
 * (which would publish on a Tomcat worker thread the recorder cannot observe). A
 * minimal {@code SecurityContext} (the caller id) and request scope (for the
 * {@code @RequestScope} ProjectAccessGuard) are established by hand; the fixture is
 * seeded via JDBC.
 */
@SpringBootTest(classes = Application.class)
@RecordApplicationEvents
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {"BCRYPT_COST=12", "spring.jpa.hibernate.ddl-auto=validate"})
class TicketTransitionEventIT {

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
    registry.add("JWT_SECRET", () -> "ticketevt-secret-min-32-characters-long-okay");
  }

  @Autowired TicketController ticketController;
  @Autowired JdbcTemplate jdbc;
  @Autowired ApplicationEvents events;

  private UUID userA;
  private UUID projectId;
  private UUID ticketId;

  @BeforeEach
  void setUp() {
    BaseIT.cleanDatabase(jdbc);

    userA = UUID.randomUUID();
    projectId = UUID.randomUUID();
    ticketId = UUID.randomUUID();

    jdbc.update(
        "INSERT INTO users (id, email, display_name, mention_handle, created_at, updated_at) "
            + "VALUES (?::uuid,?,?,?,now(),now())",
        userA.toString(),
        "usera@example.com",
        "Alice",
        "usera");
    jdbc.update(
        "INSERT INTO projects (id, key, name, created_by, created_at, updated_at) "
            + "VALUES (?::uuid,?,?,?::uuid,now(),now())",
        projectId.toString(),
        "ENG",
        "Engineering",
        userA.toString());
    jdbc.update(
        "INSERT INTO project_members (id, project_id, user_id, role, created_at) "
            + "VALUES (?::uuid,?::uuid,?::uuid,'ADMIN',now())",
        UUID.randomUUID().toString(),
        projectId.toString(),
        userA.toString());
    jdbc.update(
        "INSERT INTO tickets (id, project_id, number, title, status, priority, reporter_id, "
            + "created_at, updated_at) VALUES (?::uuid,?::uuid,1,?,'TODO','P2',?::uuid,now(),now())",
        ticketId.toString(),
        projectId.toString(),
        "Flow",
        userA.toString());

    // The transition runs on the test thread (direct controller call), so establish
    // the SecurityContext (CurrentUser → actor + guard membership lookup) and a
    // request scope (for the @RequestScope ProjectAccessGuard) by hand.
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(userA.toString(), null, List.of()));
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void realTransition_publishesExactlyOneEventWithCorrectFromToActor() {
    ticketController.transition(ticketId, new TransitionRequest(TicketStatus.IN_PROGRESS));

    assertThat(events.stream(TicketTransitionedEvent.class).count()).isEqualTo(1);
    TicketTransitionedEvent event =
        events.stream(TicketTransitionedEvent.class).findFirst().orElseThrow();
    assertThat(event.ticketId()).isEqualTo(ticketId);
    assertThat(event.projectId()).isEqualTo(projectId);
    assertThat(event.fromStatus()).isEqualTo(TicketStatus.TODO);
    assertThat(event.toStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
    assertThat(event.actorId()).isEqualTo(userA);
    assertThat(event.occurredAt()).isNotNull();
  }

  @Test
  void sameStatusTransition_publishesNoEvent() {
    // Ticket is TODO; transitioning to TODO is a no-op.
    ticketController.transition(ticketId, new TransitionRequest(TicketStatus.TODO));
    assertThat(events.stream(TicketTransitionedEvent.class).count()).isZero();
  }
}
