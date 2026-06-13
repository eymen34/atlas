package io.ngss.atlas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.flywaydb.core.Flyway;
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
 * AC-1: V9 applies cleanly on top of a V8 database and backfills a unique,
 * lowercase, deterministically-suffixed {@code mention_handle} for every existing
 * user. Drives Flyway in two phases (target V8 → seed users → migrate to latest) so
 * the backfill has pre-existing rows to act on. Self-skips without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class FlywayV9MigrationIT {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"))
          .withDatabaseName("atlas")
          .withUsername("atlas")
          .withPassword("atlas");

  static final UUID ALICE_1 = UUID.randomUUID(); // alice@example.com  → "alice"
  static final UUID ALICE_2 = UUID.randomUUID(); // Alice@other.com    → "alice-2"
  static final UUID WEIRD = UUID.randomUUID(); // a+b!c@x.io          → "abc"
  static final UUID EMPTY = UUID.randomUUID(); // +++@x.io            → "user"

  static JdbcTemplate jdbc;

  @BeforeAll
  static void migrateV8_seed_thenV9() {
    DriverManagerDataSource ds = new DriverManagerDataSource();
    ds.setDriverClassName("org.postgresql.Driver");
    ds.setUrl(POSTGRES.getJdbcUrl());
    ds.setUsername(POSTGRES.getUsername());
    ds.setPassword(POSTGRES.getPassword());
    jdbc = new JdbcTemplate(ds);

    // Phase 1: migrate only up to V8 (before mention_handle exists).
    Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("8").load().migrate();

    // Seed users at the V8 schema. created_at controls the collision-suffix order
    // (ROW_NUMBER ... ORDER BY created_at, id): ALICE_1 is earliest → "alice".
    seed(ALICE_1, "alice@example.com", "2026-01-01T00:00:00Z");
    seed(ALICE_2, "Alice@other.com", "2026-01-02T00:00:00Z"); // same slug, later
    seed(WEIRD, "a+b!c@x.io", "2026-01-03T00:00:00Z");
    seed(EMPTY, "+++@x.io", "2026-01-04T00:00:00Z");

    // Phase 2: migrate to latest (applies V9).
    Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
  }

  private static void seed(UUID id, String email, String createdAt) {
    jdbc.update(
        "INSERT INTO users (id, email, display_name, created_at, updated_at) "
            + "VALUES (?::uuid,?,?,?::timestamptz,?::timestamptz)",
        id.toString(),
        email,
        "Name",
        createdAt,
        createdAt);
  }

  private static String handle(UUID id) {
    return jdbc.queryForObject(
        "SELECT mention_handle FROM users WHERE id=?::uuid", String.class, id.toString());
  }

  @Test
  void v9IsAppliedSuccessfully() {
    // Assert the V9 row exists and succeeded — NOT that V9 is the LATEST version.
    // Pinning latest==N makes every future migration break this test, exactly how
    // V10 (T-023) broke the old latest==9 assertion (migration_it_no_version_pinning).
    Integer v9Applied =
        jdbc.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE version = '9' AND success = true",
            Integer.class);
    assertThat(v9Applied).isEqualTo(1);
  }

  @Test
  void v11NotificationsMigrationIsApplied() {
    // Existence check (not latest==N) per migration_it_no_version_pinning. V11 is the
    // notifications table (T-024); the Phase-2 migrate-to-latest above applies it.
    Integer v11Applied =
        jdbc.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE version = '11' AND success = true",
            Integer.class);
    assertThat(v11Applied).isEqualTo(1);

    Integer notificationsTable =
        jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.tables "
                + "WHERE table_name = 'notifications'",
            Integer.class);
    assertThat(notificationsTable).isEqualTo(1);
  }

  @Test
  void v12AttachmentsMigrationIsApplied() {
    // Existence check (not latest==N) per migration_it_no_version_pinning. V12 is the
    // attachments table (T-025); the Phase-2 migrate-to-latest above applies it.
    Integer v12Applied =
        jdbc.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE version = '12' AND success = true",
            Integer.class);
    assertThat(v12Applied).isEqualTo(1);

    Integer attachmentsTable =
        jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_name = 'attachments'",
            Integer.class);
    assertThat(attachmentsTable).isEqualTo(1);
  }

  @Test
  void v13TicketLinksMigrationIsApplied() {
    // Existence check (not latest==N) per migration_it_no_version_pinning. V13 is the
    // ticket_links table (T-026).
    Integer v13Applied =
        jdbc.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE version = '13' AND success = true",
            Integer.class);
    assertThat(v13Applied).isEqualTo(1);

    Integer ticketLinksTable =
        jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_name = 'ticket_links'",
            Integer.class);
    assertThat(ticketLinksTable).isEqualTo(1);
  }

  @Test
  void backfillIsLowercaseDeterministicAndCollisionSuffixed() {
    assertThat(handle(ALICE_1)).isEqualTo("alice");
    assertThat(handle(ALICE_2)).isEqualTo("alice-2"); // uppercase email → lowercased + suffixed
    assertThat(handle(WEIRD)).isEqualTo("abc"); // disallowed chars stripped
    assertThat(handle(EMPTY)).isEqualTo("user"); // empty slug fallback
  }

  @Test
  void everyHandleIsNonNullAndLowercase() {
    List<String> all = jdbc.queryForList("SELECT mention_handle FROM users", String.class);
    assertThat(all).hasSize(4);
    assertThat(all)
        .allSatisfy(
            h -> {
              assertThat(h).isNotNull();
              assertThat(h).isEqualTo(h.toLowerCase(Locale.ROOT));
            });
  }

  @Test
  void uniqueIndexRejectsDuplicateHandle() {
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO users (id, email, display_name, mention_handle, created_at, updated_at) "
                        + "VALUES (?::uuid,?,?,?,now(),now())",
                    UUID.randomUUID().toString(),
                    "dup@example.com",
                    "Dup",
                    "alice"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
