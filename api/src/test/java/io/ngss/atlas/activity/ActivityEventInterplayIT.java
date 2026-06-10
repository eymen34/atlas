package io.ngss.atlas.activity;

import static org.assertj.core.api.Assertions.assertThat;

import io.ngss.atlas.Application;
import io.ngss.atlas.BaseIT;
import io.ngss.atlas.activity.payload.StatusChangedPayload;
import io.ngss.atlas.domain.ActivityEvent;
import io.ngss.atlas.domain.ActivityEventType;
import io.ngss.atlas.domain.TicketStatus;
import io.ngss.atlas.ticket.TicketController;
import io.ngss.atlas.ticket.dto.TransitionRequest;
import io.ngss.atlas.ticket.event.TicketTransitionedEvent;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.mock.web.MockHttpServletRequest;
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
import tools.jackson.databind.ObjectMapper;

/**
 * EC-1 + REG-2: a transition must produce BOTH a {@link TicketTransitionedEvent}
 * (T-024 fan-out) AND a STATUS_CHANGED activity row — neither suppresses the other;
 * a no-op transition produces NEITHER.
 *
 * <p>{@code @RecordApplicationEvents} records events on the TEST thread, so the
 * transition is invoked directly on {@link TicketController} (synchronously, test
 * thread) with a hand-set SecurityContext + request scope, rather than via a
 * RANDOM_PORT call (which publishes on a Tomcat worker thread the recorder cannot
 * observe). The fixture is seeded via JDBC, so the only activity rows are those the
 * transition writes.
 */
@SpringBootTest(classes = Application.class)
@RecordApplicationEvents
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {"BCRYPT_COST=12", "spring.jpa.hibernate.ddl-auto=validate"})
class ActivityEventInterplayIT {

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
    registry.add("JWT_SECRET", () -> "interplayit-secret-min-32-characters-long-ok");
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired TicketController ticketController;
  @Autowired ActivityEventRepository activityRepo;
  @Autowired ObjectMapper om;
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
        userA.toString(), "alice@example.com", "Alice", "alice");
    jdbc.update(
        "INSERT INTO projects (id, key, name, created_by, created_at, updated_at) "
            + "VALUES (?::uuid,?,?,?::uuid,now(),now())",
        projectId.toString(), "ENG", "Engineering", userA.toString());
    jdbc.update(
        "INSERT INTO project_members (id, project_id, user_id, role, created_at) "
            + "VALUES (?::uuid,?::uuid,?::uuid,'ADMIN',now())",
        UUID.randomUUID().toString(), projectId.toString(), userA.toString());
    jdbc.update(
        "INSERT INTO tickets (id, project_id, number, title, status, priority, reporter_id, "
            + "created_at, updated_at) VALUES (?::uuid,?::uuid,1,?,'TODO','P2',?::uuid,now(),now())",
        ticketId.toString(), projectId.toString(), "Flow", userA.toString());

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
  void realTransition_publishesEventAndWritesStatusChangedRow() {
    ticketController.transition(ticketId, new TransitionRequest(TicketStatus.IN_PROGRESS));

    // Event (T-024 fan-out) still fires.
    assertThat(events.stream(TicketTransitionedEvent.class).count()).isEqualTo(1);
    TicketTransitionedEvent event =
        events.stream(TicketTransitionedEvent.class).findFirst().orElseThrow();
    assertThat(event.fromStatus()).isEqualTo(TicketStatus.TODO);
    assertThat(event.toStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
    assertThat(event.actorId()).isEqualTo(userA);

    // Activity row also written (synchronously, same transaction).
    List<ActivityEvent> rows =
        activityRepo.findByTicketIdOrderByCreatedAtDesc(ticketId, PageRequest.of(0, 100)).getContent();
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getEventType()).isEqualTo(ActivityEventType.STATUS_CHANGED);
    assertThat(rows.get(0).getActorId()).isEqualTo(userA);
    StatusChangedPayload p = om.readValue(rows.get(0).getPayload(), StatusChangedPayload.class);
    assertThat(p.from()).isEqualTo(TicketStatus.TODO);
    assertThat(p.to()).isEqualTo(TicketStatus.IN_PROGRESS);
  }

  @Test
  void noOpTransition_publishesNoEventAndWritesNoRow() {
    ticketController.transition(ticketId, new TransitionRequest(TicketStatus.TODO)); // already TODO

    assertThat(events.stream(TicketTransitionedEvent.class).count()).isZero();
    assertThat(activityRepo.countByTicketId(ticketId)).isZero();
  }
}
