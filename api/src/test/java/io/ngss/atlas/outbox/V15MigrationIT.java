package io.ngss.atlas.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * AC-1: V15 applies cleanly — the {@code outbox} table (columns + kind/status CHECK
 * constraints + the partial {@code ix_outbox_status_next_attempt} index) and
 * {@code users.email_notifications_enabled NOT NULL DEFAULT true}. Self-skips without Docker.
 * Self-contained {@code @Container} (one per concrete class — the shared-base singleton trap
 * does not apply here).
 */
@Testcontainers(disabledWithoutDocker = true)
class V15MigrationIT {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"))
          .withDatabaseName("atlas")
          .withUsername("atlas")
          .withPassword("atlas");

  static JdbcTemplate jdbc;

  @BeforeAll
  static void migrateToLatest() {
    DriverManagerDataSource ds = new DriverManagerDataSource();
    ds.setDriverClassName("org.postgresql.Driver");
    ds.setUrl(POSTGRES.getJdbcUrl());
    ds.setUsername(POSTGRES.getUsername());
    ds.setPassword(POSTGRES.getPassword());
    jdbc = new JdbcTemplate(ds);
    Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
  }

  @Test
  void v15IsAppliedSuccessfully() {
    // Existence check (NOT latest==N) per migration_it_no_version_pinning.
    Integer applied =
        jdbc.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE version = '15' AND success = true",
            Integer.class);
    assertThat(applied).isEqualTo(1);
  }

  @Test
  void outboxTableHasExpectedColumns() {
    Integer columns =
        jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.columns WHERE table_name = 'outbox' "
                + "AND column_name IN ('id','kind','status','payload','attempt_count',"
                + "'next_attempt_at','last_error','created_at','updated_at','sent_at')",
            Integer.class);
    assertThat(columns).isEqualTo(10);
  }

  @Test
  void partialIndexExists() {
    Integer index =
        jdbc.queryForObject(
            "SELECT count(*) FROM pg_indexes WHERE indexname = 'ix_outbox_status_next_attempt'",
            Integer.class);
    assertThat(index).isEqualTo(1);
  }

  @Test
  void kindCheckConstraintRejectsUnknownKind() {
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO outbox (id, kind, payload) VALUES (?::uuid, 'INVALID_KIND', '{}'::jsonb)",
                    UUID.randomUUID().toString()))
        .isInstanceOf(DataAccessException.class); // superclass — constraint class is impl detail
  }

  @Test
  void statusCheckConstraintRejectsUnknownStatus() {
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO outbox (id, kind, status, payload) "
                        + "VALUES (?::uuid, 'EMAIL_NOTIFICATION', 'BOGUS', '{}'::jsonb)",
                    UUID.randomUUID().toString()))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void emailNotificationsEnabledColumnIsNotNullDefaultTrue() {
    String isNullable =
        jdbc.queryForObject(
            "SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_name = 'users' AND column_name = 'email_notifications_enabled'",
            String.class);
    String dataType =
        jdbc.queryForObject(
            "SELECT data_type FROM information_schema.columns "
                + "WHERE table_name = 'users' AND column_name = 'email_notifications_enabled'",
            String.class);
    String columnDefault =
        jdbc.queryForObject(
            "SELECT column_default FROM information_schema.columns "
                + "WHERE table_name = 'users' AND column_name = 'email_notifications_enabled'",
            String.class);
    assertThat(isNullable).isEqualTo("NO");
    assertThat(dataType).isEqualTo("boolean");
    assertThat(columnDefault).contains("true");
  }
}
