package io.ngss.atlas.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ngss.atlas.Application;
import io.ngss.atlas.BaseIT;
import io.ngss.atlas.activity.payload.CreatedPayload;
import io.ngss.atlas.domain.ActivityEventType;
import io.ngss.atlas.domain.TicketPriority;
import io.ngss.atlas.domain.TicketStatus;
import io.ngss.atlas.ticket.TicketService;
import io.ngss.atlas.ticket.dto.CreateTicketRequest;
import java.time.Instant;
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
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * AC-2 (atomicity): the activity writer is bound to the originating change's
 * transaction.
 *
 * <ul>
 *   <li>EC-8: {@code record()} outside any transaction throws
 *       {@link IllegalTransactionStateException} (propagation MANDATORY) and writes
 *       nothing.
 *   <li>EC-7 / AC-2.1: a transaction that creates a ticket and then throws rolls
 *       BOTH the ticket and its CREATED activity row back — zero of each persist.
 * </ul>
 */
@SpringBootTest(classes = Application.class)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {"BCRYPT_COST=12", "spring.jpa.hibernate.ddl-auto=validate"})
class ActivityAtomicityIT {

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
    registry.add("JWT_SECRET", () -> "atomicityit-secret-min-32-characters-long-ok");
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired ActivityEventWriter writer;
  @Autowired TicketService ticketService;
  @Autowired PlatformTransactionManager txManager;

  private UUID userA;
  private UUID projectId;

  @BeforeEach
  void setUp() {
    BaseIT.cleanDatabase(jdbc);
    userA = UUID.randomUUID();
    projectId = UUID.randomUUID();
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
        "INSERT INTO project_ticket_counters (project_id, next_number) VALUES (?::uuid, 1)",
        projectId.toString());

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
  void record_withNoActiveTransaction_throwsAndWritesNothing() {
    assertThatThrownBy(
            () ->
                writer.record(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    ActivityEventType.CREATED,
                    new CreatedPayload("x", TicketStatus.TODO, TicketPriority.P2),
                    Instant.now()))
        .isInstanceOf(IllegalTransactionStateException.class);
    assertThat(count("activity_events")).isZero();
  }

  @Test
  void rollbackOfOriginatingChange_discardsTicketAndActivity() {
    TransactionTemplate tx = new TransactionTemplate(txManager);
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      ticketService.create(
                          projectId,
                          new CreateTicketRequest("Doomed", null, null, null),
                          userA);
                      // The ticket + its CREATED activity row are now pending in this txn.
                      throw new RuntimeException("forced rollback");
                    }))
        .isInstanceOf(RuntimeException.class);

    // Atomic rollback: neither the ticket nor the activity row survives.
    assertThat(count("tickets")).isZero();
    assertThat(count("activity_events")).isZero();
  }

  private int count(String table) {
    Integer n = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    return n == null ? 0 : n;
  }
}
