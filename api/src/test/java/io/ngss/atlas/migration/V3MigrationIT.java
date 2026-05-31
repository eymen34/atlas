package io.ngss.atlas.migration;

import static org.assertj.core.api.Assertions.assertThat;

import io.ngss.atlas.Application;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * AC-1: V3 migration applies cleanly on a fresh PG17 and adds display_name as
 * a NOT NULL varchar(80). Boots the full app so Flyway runs V1-V3 automatically
 * (same pattern as V2MigrationIT). Self-skips without Docker.
 */
@SpringBootTest(classes = Application.class)
@Testcontainers(disabledWithoutDocker = true)
class V3MigrationIT {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"))
          .withDatabaseName("atlas")
          .withUsername("atlas")
          .withPassword("atlas");

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("app.database.url", POSTGRES::getJdbcUrl);
    registry.add("app.database.username", POSTGRES::getUsername);
    registry.add("app.database.password", POSTGRES::getPassword);
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("JWT_SECRET", () -> "v3migration-test-secret-min-32-characters-long!");
  }

  @Autowired JdbcTemplate jdbc;

  @Test
  void flywayHistoryShowsV1V2V3Successful() {
    List<Map<String, Object>> history =
        jdbc.queryForList(
            "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank");
    assertThat(history).extracting(r -> r.get("version")).contains("1", "2", "3");
    assertThat(history).allSatisfy(r -> assertThat(r.get("success")).isEqualTo(true));
  }

  @Test
  void displayNameColumnExistsWithCorrectDefinition() {
    String dataType =
        jdbc.queryForObject(
            "SELECT data_type FROM information_schema.columns "
                + "WHERE table_name='users' AND column_name='display_name'",
            String.class);
    Integer maxLength =
        jdbc.queryForObject(
            "SELECT character_maximum_length FROM information_schema.columns "
                + "WHERE table_name='users' AND column_name='display_name'",
            Integer.class);
    String isNullable =
        jdbc.queryForObject(
            "SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_name='users' AND column_name='display_name'",
            String.class);

    assertThat(dataType).isEqualTo("character varying");
    assertThat(maxLength).isEqualTo(80);
    assertThat(isNullable).isEqualTo("NO");
  }
}
