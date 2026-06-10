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
  void migrationReachesV9() {
    String version =
        jdbc.queryForObject(
            "SELECT version FROM flyway_schema_history WHERE success = true "
                + "ORDER BY installed_rank DESC LIMIT 1",
            String.class);
    assertThat(version).isEqualTo("9");
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
