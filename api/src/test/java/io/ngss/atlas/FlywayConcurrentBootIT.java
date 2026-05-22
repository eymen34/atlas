package io.ngss.atlas;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class FlywayConcurrentBootIT {

  private static PostgreSQLContainer<?> postgres;

  @BeforeAll
  static void startPostgres() {
    postgres =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("atlas")
            .withUsername("atlas")
            .withPassword("atlas");
    postgres.start();
  }

  @AfterAll
  static void stopPostgres() {
    if (postgres != null) {
      postgres.stop();
    }
  }

  @Test
  void twoInstancesBootSafelyAgainstSameFreshDatabase() throws Exception {
    ExecutorService exec = Executors.newFixedThreadPool(2);
    CopyOnWriteArrayList<ConfigurableApplicationContext> live = new CopyOnWriteArrayList<>();
    try {
      CompletableFuture<ConfigurableApplicationContext> ctxA =
          CompletableFuture.supplyAsync(this::bootInstance, exec);
      CompletableFuture<ConfigurableApplicationContext> ctxB =
          CompletableFuture.supplyAsync(this::bootInstance, exec);

      ctxA.thenAccept(live::add);
      ctxB.thenAccept(live::add);

      CompletableFuture.allOf(ctxA, ctxB).get(60, SECONDS);

      ConfigurableApplicationContext a = ctxA.get();
      ConfigurableApplicationContext b = ctxB.get();
      assertThat(a.isActive()).as("instance A active").isTrue();
      assertThat(b.isActive()).as("instance B active").isTrue();

      DataSource dsA = a.getBean(DataSource.class);
      DataSource dsB = b.getBean(DataSource.class);

      assertExactlyOneSuccessfulV1(dsA, "A");
      assertExactlyOneSuccessfulV1(dsB, "B");
      assertUsersTableQueryable(dsA, "A");
      assertUsersTableQueryable(dsB, "B");
      assertLowerEmailIndexPresent(dsA);
    } finally {
      for (ConfigurableApplicationContext c : live) {
        c.close();
      }
      exec.shutdownNow();
    }
  }

  private ConfigurableApplicationContext bootInstance() {
    return new SpringApplicationBuilder(Application.class)
        .web(WebApplicationType.SERVLET)
        .properties(
            "server.port=0",
            "app.database.url=" + postgres.getJdbcUrl(),
            "app.database.username=" + postgres.getUsername(),
            "app.database.password=" + postgres.getPassword(),
            "spring.datasource.url=" + postgres.getJdbcUrl(),
            "spring.datasource.username=" + postgres.getUsername(),
            "spring.datasource.password=" + postgres.getPassword(),
            "spring.datasource.driver-class-name=org.postgresql.Driver",
            "spring.main.banner-mode=off")
        .run();
  }

  private void assertExactlyOneSuccessfulV1(DataSource ds, String label) throws Exception {
    try (Connection c = ds.getConnection();
        Statement s = c.createStatement();
        ResultSet rs =
            s.executeQuery(
                "SELECT version, success FROM flyway_schema_history WHERE version = '1'")) {
      int rows = 0;
      while (rs.next()) {
        rows++;
        assertThat(rs.getBoolean("success"))
            .as("instance %s flyway_schema_history.success for V1", label)
            .isTrue();
      }
      assertThat(rows)
          .as("instance %s flyway_schema_history must contain exactly one V1 row", label)
          .isEqualTo(1);
    }
  }

  private void assertUsersTableQueryable(DataSource ds, String label) throws Exception {
    try (Connection c = ds.getConnection();
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("SELECT count(*) FROM users")) {
      assertThat(rs.next()).as("instance %s users-count result", label).isTrue();
      assertThat(rs.getInt(1)).as("instance %s users row count", label).isEqualTo(0);
    }
  }

  private void assertLowerEmailIndexPresent(DataSource ds) throws Exception {
    try (Connection c = ds.getConnection();
        Statement s = c.createStatement();
        ResultSet rs =
            s.executeQuery(
                "SELECT indexdef FROM pg_indexes "
                    + "WHERE tablename = 'users' AND indexname = 'users_email_lower_key'")) {
      assertThat(rs.next()).as("users_email_lower_key must exist").isTrue();
      String indexDef = rs.getString(1);
      assertThat(indexDef).contains("lower(email");
    }
  }
}
