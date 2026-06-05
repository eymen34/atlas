package io.ngss.atlas.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * AC-1: V5 applies cleanly on top of a V4 database and backfills an ADMIN
 * membership for every LIVE project's creator (soft-deleted projects skipped),
 * idempotently. Drives Flyway directly in two phases (target V4 → seed projects →
 * migrate to latest) so the backfill has pre-existing rows to act on — booting the
 * app would run V1–V5 in one shot against an empty DB and exercise nothing.
 *
 * <p>Self-skips without Docker (Testcontainers).
 */
@Testcontainers(disabledWithoutDocker = true)
class ProjectMemberMigrationIT {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"))
          .withDatabaseName("atlas")
          .withUsername("atlas")
          .withPassword("atlas");

  static final UUID ALICE = UUID.randomUUID();
  static final UUID BOB = UUID.randomUUID();
  static final UUID P1_LIVE = UUID.randomUUID();
  static final UUID P2_LIVE = UUID.randomUUID();
  static final UUID P3_DELETED = UUID.randomUUID();

  static JdbcTemplate jdbc;
  static Flyway flyway;

  @BeforeAll
  static void migrateV4_seed_thenV5() {
    DriverManagerDataSource ds = new DriverManagerDataSource();
    ds.setDriverClassName("org.postgresql.Driver");
    ds.setUrl(POSTGRES.getJdbcUrl());
    ds.setUsername(POSTGRES.getUsername());
    ds.setPassword(POSTGRES.getPassword());
    jdbc = new JdbcTemplate(ds);

    // Phase 1: migrate only up to V4.
    Flyway.configure()
        .dataSource(ds)
        .locations("classpath:db/migration")
        .target("4")
        .load()
        .migrate();

    // Seed two users and three projects (two live, one soft-deleted) at the V4 schema.
    jdbc.update(
        "INSERT INTO users (id, email, display_name, created_at, updated_at) "
            + "VALUES (?::uuid,?,?,now(),now())",
        ALICE.toString(),
        "alice@example.com",
        "Alice");
    jdbc.update(
        "INSERT INTO users (id, email, display_name, created_at, updated_at) "
            + "VALUES (?::uuid,?,?,now(),now())",
        BOB.toString(),
        "bob@example.com",
        "Bob");
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
        BOB.toString());
    jdbc.update(
        "INSERT INTO projects (id, key, name, created_by, created_at, updated_at, deleted_at) "
            + "VALUES (?::uuid,?,?,?::uuid,now(),now(),now())",
        P3_DELETED.toString(),
        "PGONE",
        "Gone",
        ALICE.toString());

    // Phase 2: migrate the rest (V5 runs the backfill).
    flyway = Flyway.configure().dataSource(ds).locations("classpath:db/migration").load();
    flyway.migrate();
  }

  @Test
  void v5RecordedSuccessfulInHistory() {
    Integer ok =
        jdbc.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE version='5' AND success=true",
            Integer.class);
    assertThat(ok).isEqualTo(1);
  }

  @Test
  void backfillSeedsAdminForLiveProjectsOnly() {
    Integer total = jdbc.queryForObject("SELECT count(*) FROM project_members", Integer.class);
    assertThat(total).isEqualTo(2);

    Integer p3rows =
        jdbc.queryForObject(
            "SELECT count(*) FROM project_members WHERE project_id=?::uuid",
            Integer.class,
            P3_DELETED.toString());
    assertThat(p3rows).isZero();

    assertThat(
            jdbc.queryForObject(
                "SELECT role FROM project_members WHERE project_id=?::uuid",
                String.class,
                P1_LIVE.toString()))
        .isEqualTo("ADMIN");
    assertThat(
            jdbc.queryForObject(
                "SELECT user_id FROM project_members WHERE project_id=?::uuid",
                String.class,
                P1_LIVE.toString()))
        .isEqualTo(ALICE.toString());
    Integer invitedByNull =
        jdbc.queryForObject(
            "SELECT count(*) FROM project_members WHERE invited_by IS NULL", Integer.class);
    assertThat(invitedByNull).isEqualTo(2);
  }

  @Test
  void surrogateUuidPkAndUniqueConstraintEnforced() {
    String pkType =
        jdbc.queryForObject(
            "SELECT data_type FROM information_schema.columns "
                + "WHERE table_name='project_members' AND column_name='id'",
            String.class);
    assertThat(pkType).isEqualTo("uuid");

    // UNIQUE(project_id, user_id): a duplicate insert for P1/ALICE must be rejected.
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO project_members (id, project_id, user_id, role, created_at) "
                        + "VALUES (gen_random_uuid(),?::uuid,?::uuid,'MEMBER',now())",
                    P1_LIVE.toString(),
                    ALICE.toString()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void foreignKeysUseNoActionOnDelete() {
    java.util.List<String> deleteRules =
        jdbc.queryForList(
            "SELECT rc.delete_rule FROM information_schema.referential_constraints rc "
                + "JOIN information_schema.table_constraints tc "
                + "  ON rc.constraint_name = tc.constraint_name "
                + "WHERE tc.table_name='project_members' AND tc.constraint_type='FOREIGN KEY'",
            String.class);
    assertThat(deleteRules).isNotEmpty();
    assertThat(deleteRules).allSatisfy(rule -> assertThat(rule).isIn("NO ACTION", "RESTRICT"));
  }

  @Test
  void reMigrateIsIdempotent() {
    MigrateResult result = flyway.migrate();
    assertThat(result.migrationsExecuted).isZero();
    Integer total = jdbc.queryForObject("SELECT count(*) FROM project_members", Integer.class);
    assertThat(total).isEqualTo(2);
  }
}
