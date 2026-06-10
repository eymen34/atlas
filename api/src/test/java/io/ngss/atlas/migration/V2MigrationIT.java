package io.ngss.atlas.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ngss.atlas.Application;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(classes = Application.class)
@Testcontainers(disabledWithoutDocker = true)
class V2MigrationIT {

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
    registry.add("JWT_SECRET", () -> "v2migration-test-secret-min-32-characters-long!");
  }

  @Autowired JdbcTemplate jdbc;

  @Test
  void flywayHistoryShowsV1AndV2Successful() {
    List<Map<String, Object>> history =
        jdbc.queryForList(
            "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank");
    assertThat(history).extracting(r -> r.get("version")).contains("1", "2");
    assertThat(history).allSatisfy(r -> assertThat(r.get("success")).isEqualTo(true));
  }

  @Test
  void refreshTokensTableHasExpectedColumns() {
    Map<String, String> cols = columnTypes("refresh_tokens");
    assertThat(cols).containsEntry("id", "uuid");
    assertThat(cols).containsEntry("user_id", "uuid");
    assertThat(cols).containsEntry("token_hash", "character");
    assertThat(cols).containsEntry("issued_at", "timestamp with time zone");
    assertThat(cols).containsEntry("last_used_at", "timestamp with time zone");
    assertThat(cols).containsEntry("expires_at", "timestamp with time zone");
    assertThat(cols).containsEntry("revoked_at", "timestamp with time zone");
    assertThat(cols).containsEntry("replaced_by_id", "uuid");

    Integer tokenHashLen =
        jdbc.queryForObject(
            "SELECT character_maximum_length FROM information_schema.columns "
                + "WHERE table_name='refresh_tokens' AND column_name='token_hash'",
            Integer.class);
    assertThat(tokenHashLen).isEqualTo(64);
  }

  @Test
  void passwordCredentialsTableHasExpectedColumns() {
    Map<String, String> cols = columnTypes("password_credentials");
    assertThat(cols).containsEntry("user_id", "uuid");
    assertThat(cols).containsEntry("bcrypt_hash", "character varying");
    assertThat(cols).containsEntry("updated_at", "timestamp with time zone");

    Integer bcryptLen =
        jdbc.queryForObject(
            "SELECT character_maximum_length FROM information_schema.columns "
                + "WHERE table_name='password_credentials' AND column_name='bcrypt_hash'",
            Integer.class);
    assertThat(bcryptLen).isEqualTo(60);
  }

  @Test
  void tokenHashUniqueConstraintIsEnforcedAtDatabaseLevel() {
    UUID userId = UUID.randomUUID();
    // display_name is NOT NULL as of V3 (T-011) and mention_handle as of V9 (T-022);
    // this @SpringBootTest runs Flyway to the latest version, so include both.
    jdbc.update(
        "INSERT INTO users (id, email, display_name, mention_handle) VALUES (?, ?, ?, ?)",
        userId,
        "uniq-test@example.com",
        "uniq-test",
        "uniq-test");
    String hash = "a".repeat(64);
    jdbc.update(
        "INSERT INTO refresh_tokens (user_id, token_hash, expires_at) "
            + "VALUES (?, ?, now() + interval '30 days')",
        userId,
        hash);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO refresh_tokens (user_id, token_hash, expires_at) "
                        + "VALUES (?, ?, now() + interval '30 days')",
                    userId,
                    hash))
        .isInstanceOf(DuplicateKeyException.class);
  }

  @Test
  void foreignKeysAndSelfReferenceArePresent() {
    List<Map<String, Object>> fks =
        jdbc.queryForList(
            "SELECT tc.constraint_name, kcu.column_name, ccu.table_name AS ref_table, "
                + "ccu.column_name AS ref_col, rc.delete_rule "
                + "FROM information_schema.table_constraints tc "
                + "JOIN information_schema.key_column_usage kcu "
                + "  ON tc.constraint_name = kcu.constraint_name "
                + "JOIN information_schema.constraint_column_usage ccu "
                + "  ON tc.constraint_name = ccu.constraint_name "
                + "JOIN information_schema.referential_constraints rc "
                + "  ON tc.constraint_name = rc.constraint_name "
                + "WHERE tc.constraint_type='FOREIGN KEY' "
                + "  AND tc.table_name IN ('refresh_tokens','password_credentials')");
    assertThat(fks)
        .anySatisfy(
            row -> {
              assertThat(row.get("column_name")).isEqualTo("user_id");
              assertThat(row.get("ref_table")).isEqualTo("users");
              assertThat(row.get("delete_rule")).isEqualTo("CASCADE");
            });
    assertThat(fks)
        .anySatisfy(
            row -> {
              assertThat(row.get("column_name")).isEqualTo("replaced_by_id");
              assertThat(row.get("ref_table")).isEqualTo("refresh_tokens");
              assertThat(row.get("delete_rule")).isEqualTo("SET NULL");
            });
  }

  @Test
  void indexesArePresentIncludingPartialLiveIndex() {
    List<String> indexes =
        jdbc.queryForList(
            "SELECT indexname FROM pg_indexes WHERE tablename='refresh_tokens'", String.class);
    assertThat(indexes)
        .contains(
            "refresh_tokens_user_id_idx",
            "refresh_tokens_expires_at_idx",
            "refresh_tokens_user_live_idx");

    String partialDef =
        jdbc.queryForObject(
            "SELECT indexdef FROM pg_indexes WHERE indexname='refresh_tokens_user_live_idx'",
            String.class);
    assertThat(partialDef).contains("WHERE").contains("revoked_at IS NULL");
  }

  private Map<String, String> columnTypes(String table) {
    return jdbc
        .queryForList(
            "SELECT column_name, data_type FROM information_schema.columns "
                + "WHERE table_name=? ORDER BY column_name",
            table)
        .stream()
        .collect(
            java.util.stream.Collectors.toMap(
                r -> (String) r.get("column_name"), r -> (String) r.get("data_type")));
  }
}
