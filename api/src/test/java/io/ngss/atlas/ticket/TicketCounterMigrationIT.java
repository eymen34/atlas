package io.ngss.atlas.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * AC-11: V6 applies cleanly on top of a V5 database and backfills a ticket counter
 * (next_number = 1) for every LIVE project (soft-deleted projects skipped),
 * idempotently. Drives Flyway directly in two phases (target V5 → seed projects →
 * migrate to latest) so the V6 backfill has pre-existing rows to act on.
 *
 * <p>Self-skips without Docker (Testcontainers).
 */
@Testcontainers(disabledWithoutDocker = true)
class TicketCounterMigrationIT {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"))
          .withDatabaseName("atlas")
          .withUsername("atlas")
          .withPassword("atlas");

  static final UUID ALICE = UUID.randomUUID();
  static final UUID P1_LIVE = UUID.randomUUID();
  static final UUID P2_LIVE = UUID.randomUUID();
  static final UUID P3_DELETED = UUID.randomUUID();

  static JdbcTemplate jdbc;
  static Flyway flyway;

  @BeforeAll
  static void migrateV5_seed_thenV6() {
    DriverManagerDataSource ds = new DriverManagerDataSource();
    ds.setDriverClassName("org.postgresql.Driver");
    ds.setUrl(POSTGRES.getJdbcUrl());
    ds.setUsername(POSTGRES.getUsername());
    ds.setPassword(POSTGRES.getPassword());
    jdbc = new JdbcTemplate(ds);

    // Phase 1: migrate only up to V5.
    Flyway.configure()
        .dataSource(ds)
        .locations("classpath:db/migration")
        .target("5")
        .load()
        .migrate();

    // Seed one user and three projects (two live, one soft-deleted) at the V5 schema.
    jdbc.update(
        "INSERT INTO users (id, email, display_name, created_at, updated_at) "
            + "VALUES (?::uuid,?,?,now(),now())",
        ALICE.toString(),
        "alice@example.com",
        "Alice");
    jdbc.update(
        "INSERT INTO projects (id, key, name, created_by, created_at, updated_at) "
            + "VALUES (?::uuid,?,?,?::uuid,now(),now())",
        P1_LIVE.toString(),
        "PALPHA",
        "Alpha",
        ALICE.toString());
    jdbc.update(
        "INSERT INTO projects (id, key, name, created_by, created_at, updated_at) "
            + "VALUES (?::uuid,?,?,?::uuid,now(),now())",
        P2_LIVE.toString(),
        "PBETA",
        "Beta",
        ALICE.toString());
    jdbc.update(
        "INSERT INTO projects (id, key, name, created_by, created_at, updated_at, deleted_at) "
            + "VALUES (?::uuid,?,?,?::uuid,now(),now(),now())",
        P3_DELETED.toString(),
        "PGONE",
        "Gone",
        ALICE.toString());

    // Phase 2: migrate the rest (V6 runs the counter backfill).
    flyway = Flyway.configure().dataSource(ds).locations("classpath:db/migration").load();
    flyway.migrate();
  }

  @Test
  void v6RecordedSuccessfulInHistory() {
    Integer ok =
        jdbc.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE version='6' AND success=true",
            Integer.class);
    assertThat(ok).isEqualTo(1);
  }

  @Test
  void backfillSeedsCounterForLiveProjectsOnly_withNextNumberOne() {
    Integer total =
        jdbc.queryForObject("SELECT count(*) FROM project_ticket_counters", Integer.class);
    assertThat(total).isEqualTo(2);

    Integer deletedRows =
        jdbc.queryForObject(
            "SELECT count(*) FROM project_ticket_counters WHERE project_id=?::uuid",
            Integer.class,
            P3_DELETED.toString());
    assertThat(deletedRows).isZero();

    assertThat(
            jdbc.queryForObject(
                "SELECT next_number FROM project_ticket_counters WHERE project_id=?::uuid",
                Integer.class,
                P1_LIVE.toString()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT next_number FROM project_ticket_counters WHERE project_id=?::uuid",
                Integer.class,
                P2_LIVE.toString()))
        .isEqualTo(1);
  }

  @Test
  void reMigrateIsIdempotent() {
    MigrateResult result = flyway.migrate();
    assertThat(result.migrationsExecuted).isZero();
    Integer total =
        jdbc.queryForObject("SELECT count(*) FROM project_ticket_counters", Integer.class);
    assertThat(total).isEqualTo(2);
  }
}
