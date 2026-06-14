package io.ngss.atlas.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.ngss.atlas.Application;
import io.ngss.atlas.BaseIT;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * T-034: the registration duplicate pre-check ({@link UserRepository#existsByEmailLower})
 * must (a) be case-insensitive and (b) be able to use the V1 {@code users_email_lower_key}
 * functional unique index (an index on {@code lower(email)}), unlike the previous derived
 * {@code existsByEmailIgnoreCase} which compiled to {@code UPPER(email)=UPPER(?)} and could
 * not.
 *
 * <p>Guarded SINGLETON Postgres (testcontainers_singleton_shared_base); self-skips without
 * Docker (CI-only per local_dev_docker_access). {@code ddl-auto=validate} also asserts the
 * 17 entities still map onto the migrated schema.
 */
@SpringBootTest(classes = Application.class)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {"BCRYPT_COST=12", "spring.jpa.hibernate.ddl-auto=validate"})
class UserEmailLowerIT extends BaseIT {

  @SuppressWarnings("resource")
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"))
          .withDatabaseName("atlas")
          .withUsername("atlas")
          .withPassword("atlas");

  static {
    if (DockerClientFactory.instance().isDockerAvailable()) {
      POSTGRES.start();
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("app.database.url", POSTGRES::getJdbcUrl);
    registry.add("app.database.username", POSTGRES::getUsername);
    registry.add("app.database.password", POSTGRES::getPassword);
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("JWT_SECRET", () -> "user-email-lower-it-secret-min-32-characters-long!");
  }

  // Mixed-case email persisted once per test; the queries below vary only by case.
  private static final String STORED_EMAIL = "Bob@X.com";

  @Autowired UserRepository userRepository;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void seed() {
    BaseIT.cleanDatabase(jdbc);
    // created_at/updated_at default now(); email_notifications_enabled defaults true (V15).
    // Only id/email/display_name/mention_handle are NOT NULL without a DB default.
    jdbc.update(
        "INSERT INTO users (id, email, display_name, mention_handle) VALUES (?::uuid, ?, ?, ?)",
        UUID.randomUUID().toString(),
        STORED_EMAIL,
        "Bob",
        "bobx-emaillowerit");
  }

  @AfterEach
  void tearDown() {
    BaseIT.cleanDatabase(jdbc);
  }

  // ── AC1 (required): case-insensitive boolean correctness ──────────────

  @Test
  void existsByEmailLower_isCaseInsensitive() {
    // Same as stored, all-lower, all-upper → all true; absent → false.
    assertThat(userRepository.existsByEmailLower("Bob@X.com")).isTrue();
    assertThat(userRepository.existsByEmailLower("bob@x.com")).isTrue();
    assertThat(userRepository.existsByEmailLower("BOB@X.COM")).isTrue();
    assertThat(userRepository.existsByEmailLower("absent@x.com")).isFalse();
  }

  // ── D2b (proof): the lower(email) predicate can use the functional index ──

  @Test
  void lowerEmailPredicateUsesTheFunctionalIndex() {
    // On a tiny table the planner prefers a seq scan, so a plain EXPLAIN would be flaky.
    // Disabling seq scans forces the planner to reveal whether an index access path EXISTS
    // for `lower(email) = ?`; it can only satisfy that via users_email_lower_key. The SET,
    // the EXPLAIN, and the restore all run on ONE pooled connection (a fresh JdbcTemplate
    // call could grab a different connection where enable_seqscan is still on).
    List<String> plan =
        jdbc.execute(
            (Connection con) -> {
              try (Statement st = con.createStatement()) {
                st.execute("SET enable_seqscan = off");
                try {
                  List<String> lines = new ArrayList<>();
                  try (ResultSet rs =
                      st.executeQuery(
                          "EXPLAIN SELECT count(*) FROM users "
                              + "WHERE lower(email) = lower('Bob@X.com')")) {
                    while (rs.next()) {
                      lines.add(rs.getString(1));
                    }
                  }
                  return lines;
                } finally {
                  st.execute("SET enable_seqscan = on");
                }
              }
            });

    assertThat(plan).isNotEmpty();
    assertThat(String.join("\n", plan))
        .as("EXPLAIN plan must reach the lower(email) functional index")
        .contains("users_email_lower_key");
  }
}
