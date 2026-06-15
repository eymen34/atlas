package io.ngss.atlas.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.ngss.atlas.Application;
import io.ngss.atlas.BaseIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * T-033 EC-5 / EC-6: the maintenance sweep prunes expired login_attempts rows, retains active
 * lockouts, and does NOT regress the T-053 outbox sweeps. Drives {@link MaintenanceService}
 * directly (it is {@code @Transactional(NEVER)} → called from a non-transactional test method).
 */
@SpringBootTest(classes = Application.class)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {"BCRYPT_COST=12", "spring.jpa.hibernate.ddl-auto=validate"})
class MaintenanceServiceLoginAttemptsIT {

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
    registry.add("JWT_SECRET", () -> "maintloginit-secret-min-32-characters-okay!");
  }

  @Autowired MaintenanceService maintenanceService;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    BaseIT.cleanDatabase(jdbc);
  }

  @AfterEach
  void cleanAfter() {
    BaseIT.cleanDatabase(jdbc);
  }

  private void seedLoginAttempt(String key, String firstAgo, String lockedUntilExpr) {
    jdbc.update(
        "INSERT INTO login_attempts "
            + "(id, attempt_key, key_type, attempt_count, first_attempt_at, locked_until) "
            + "VALUES (gen_random_uuid(), ?, 'ACCOUNT', 5, now() - CAST(? AS interval), "
            + lockedUntilExpr
            + ")",
        key,
        firstAgo);
  }

  private long loginAttemptRowCount() {
    return jdbc.queryForObject("SELECT count(*) FROM login_attempts", Long.class);
  }

  @Test
  void testSweepDeletesExpiredRows() {
    // Window elapsed (20 min > 15 default) AND lockout elapsed → eligible for pruning.
    seedLoginAttempt("expired@example.com", "20 minutes", "now() - interval '5 minutes'");
    assertThat(loginAttemptRowCount()).isEqualTo(1L);

    maintenanceService.runMaintenance();

    assertThat(loginAttemptRowCount()).isZero();
  }

  @Test
  void testSweepRetainsActiveLockoutRows() {
    // Window elapsed BUT still locked (future locked_until) → must be retained.
    seedLoginAttempt("locked@example.com", "20 minutes", "now() + interval '5 minutes'");

    maintenanceService.runMaintenance();

    assertThat(loginAttemptRowCount()).as("active lockout retained").isEqualTo(1L);
  }

  @Test
  void testSweepDoesNotBreakPriorSweeps() {
    // A stuck PROCESSING outbox row (T-053 reclaim target) + an expired login_attempts row.
    jdbc.update(
        "INSERT INTO outbox (id, kind, status, payload, attempt_count, updated_at, next_attempt_at) "
            + "VALUES (gen_random_uuid(), 'EMAIL_NOTIFICATION', 'PROCESSING', '{}'::jsonb, 0, "
            + "        now() - interval '20 minutes', now() - interval '20 minutes')");
    seedLoginAttempt("expired@example.com", "20 minutes", "now() - interval '5 minutes'");

    MaintenanceResult result = maintenanceService.runMaintenance();

    assertThat(result.reclaimedToPending()).as("T-053 reclaim still runs").isEqualTo(1L);
    assertThat(loginAttemptRowCount()).as("login_attempts sweep ran too").isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE status = 'PROCESSING'", Long.class))
        .isZero();
  }
}
